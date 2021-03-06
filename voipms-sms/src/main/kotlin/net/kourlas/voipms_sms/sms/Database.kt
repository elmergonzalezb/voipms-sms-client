/*
 * VoIP.ms SMS
 * Copyright (C) 2017-2018 Michael Kourlas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.kourlas.voipms_sms.sms

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.drawable.Icon
import android.os.Build
import android.os.ParcelFileDescriptor
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.sms.services.SyncService
import net.kourlas.voipms_sms.utils.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*


/**
 * Provides access to the application's database.
 */
class Database private constructor(private val context: Context) {
    private val databaseHelper = DatabaseHelper(context)
    private var database = databaseHelper.writableDatabase

    /**
     * Deletes the message with the specified DID, database ID, and optionally
     * VoIP.ms ID from the database.
     */
    fun deleteMessage(did: String, databaseId: Long,
                      voipId: Long?): Unit = synchronized(this)
    {
        try {
            database.beginTransaction()

            if (voipId != null) {
                insertVoipIdDeleted(did, voipId)
            }
            database.delete(TABLE_MESSAGE,
                            "$COLUMN_DATABASE_ID=?",
                            arrayOf(databaseId.toString()))

            database.setTransactionSuccessful()

            removeFromIndex(Message.getMessageUrl(databaseId))
            updateShortcuts(context)
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Deletes all messages that are not associated with the specified DIDs.
     */
    fun deleteMessages(dids: Set<String>) = synchronized(this) {
        try {
            database.beginTransaction()

            val query = dids.joinToString(" AND ") { "$COLUMN_DID!=?" }
            val queryArgs = dids.toTypedArray()
            database.delete(TABLE_MESSAGE, query, queryArgs)
            database.delete(TABLE_DELETED, query, queryArgs)
            database.delete(TABLE_DRAFT, query, queryArgs)
            database.delete(TABLE_ARCHIVED, query, queryArgs)

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Deletes the messages in the specified conversation from the database.
     * Also deletes any draft message if one exists.
     */
    fun deleteMessages(conversationId: ConversationId) = synchronized(this) {
        try {
            database.beginTransaction()

            val messages = getMessagesConversation(conversationId)
            for (message in messages) {
                if (message.voipId != null) {
                    insertVoipIdDeleted(conversationId.did, message.voipId)
                }
            }
            val where = "$COLUMN_DID=? AND $COLUMN_CONTACT=?"
            val whereArgs = arrayOf(conversationId.did, conversationId.contact)
            database.delete(TABLE_MESSAGE, where, whereArgs)
            database.delete(TABLE_DRAFT, where, whereArgs)
            database.delete(TABLE_ARCHIVED, where, whereArgs)

            insertMessageDraftWithoutLock(conversationId, "")

            database.setTransactionSuccessful()

            for (message in messages) {
                removeFromIndex(message.messageUrl)
            }
            updateShortcuts(context)
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Deletes all rows in the deleted messages table from the database.
     */
    fun deleteTableDeleted() = synchronized(this) {
        try {
            database.beginTransaction()

            database.delete(TABLE_DELETED, null, null)

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Deletes all rows in all tables in the database.
     */
    fun deleteTablesAll(): Unit = synchronized(this) {
        try {
            database.beginTransaction()

            database.delete(TABLE_MESSAGE, null, null)
            database.delete(TABLE_DELETED, null, null)
            database.delete(TABLE_DRAFT, null, null)
            database.delete(TABLE_ARCHIVED, null, null)

            database.setTransactionSuccessful()

            removeAllFromIndex()
            updateShortcuts(context)
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Exports the database to the specified file descriptor.
     */
    fun export(exportFd: ParcelFileDescriptor) = synchronized(this) {
        val dbFile = context.getDatabasePath(DATABASE_NAME)

        try {
            // Close database to persist it to disk before export
            database.close()

            val exportStream = FileOutputStream(exportFd.fileDescriptor)
            val dbStream = FileInputStream(dbFile)
            exportStream.channel.truncate(0)
            val buffer = ByteArray(1024)
            do {
                val length = dbStream.read(buffer)
                if (length > 0) {
                    exportStream.write(buffer, 0, length)
                } else {
                    break
                }
            } while (true)
            exportStream.close()
            dbStream.close()
        } finally {
            // Refresh database after export
            database.close()
            database = databaseHelper.writableDatabase
        }
    }

    /**
     * Gets all conversation IDs in the database associated with the specified
     * DIDs.
     */
    fun getConversationIds(
        dids: Set<String>): Set<ConversationId> = synchronized(this)
    {
        return getMessagesMostRecentFilteredWithoutLock(dids)
            .map { it.conversationId }
            .toSet()
    }

    /**
     * Gets all DIDs used in the database.
     */
    fun getDids(): Set<String> = synchronized(this) {
        val cursor = database.query(
            true, TABLE_MESSAGE, arrayOf(COLUMN_DID),
            null, null, null, null, null, null)
        val dids = mutableListOf<String>()
        cursor.moveToFirst()
        while (!cursor.isAfterLast) {
            dids.add(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DID)))
            cursor.moveToNext()
        }
        cursor.close()
        return dids.toSet()
    }

    /**
     * Retrieves the message with the specified database ID from the database.
     *
     * @return Null if the message does not exist.
     */
    fun getMessageDatabaseId(databaseId: Long): Message? = synchronized(this) {
        return getMessageDatabaseIdWithoutLock(databaseId)
    }

    /**
     * Gets the draft message for the specified conversation.
     *
     * @return Null if the message does not exist.
     */
    fun getMessageDraft(
        conversationId: ConversationId): Message? = synchronized(this)
    {
        return getMessageDraftWithoutLock(conversationId)
    }

    /**
     * Gets the most recent message in the set of messages associated with the
     * specified DIDs.
     *
     * @return Null if the message does not exist.
     */
    fun getMessageMostRecent(dids: Set<String>): Message? = synchronized(this) {
        val messages = getMessagesCursor(
            database.query(
                TABLE_MESSAGE,
                messageColumns,
                dids.joinToString(" OR ") { "$COLUMN_DID=?" },
                dids.toTypedArray(),
                null, null,
                "$COLUMN_DATE DESC", "1"))
        if (messages.size > 0) {
            return messages[0]
        }
        return null
    }

    /**
     * Gets all of the messages in the message table with the specified DIDs.
     * The resulting list is sorted by database ID in descending order.
     */
    fun getMessagesAll(dids: Set<String>): List<Message> = synchronized(this) {
        return getMessagesCursor(
            database.query(
                TABLE_MESSAGE,
                messageColumns,
                dids.joinToString(" OR ") { "$COLUMN_DID=?" },
                dids.toTypedArray(),
                null, null,
                "$COLUMN_DATABASE_ID DESC"))
    }

    /**
     * Gets all messages in a specified conversation that match a specified
     * filter constraint. The resulting list is sorted by date, from least
     * recent to most recent.
     */
    fun getMessagesConversationFiltered(
        conversationId: ConversationId,
        filterConstraint: String): List<Message> = synchronized(this)
    {
        return getMessagesCursor(
            database.query(
                TABLE_MESSAGE,
                messageColumns,
                "$COLUMN_DID=? AND $COLUMN_CONTACT=?"
                + " AND $COLUMN_MESSAGE LIKE ?",
                arrayOf(conversationId.did, conversationId.contact,
                        "%$filterConstraint%"),
                null, null,
                "$COLUMN_DATE ASC, $COLUMN_DATABASE_ID DESC"))
    }

    /**
     * Gets the most recent message in each conversation associated with the
     * specified DIDs that matches a specified filter constraint. The resulting
     * list is sorted by date, from most recent to least recent.
     */
    fun getMessagesMostRecentFiltered(
        dids: Set<String>,
        filterConstraint: String,
        contactNameCache: MutableMap<String,
            String>? = null): List<Message> = synchronized(this)
    {
        return getMessagesMostRecentFilteredWithoutLock(dids, filterConstraint,
                                                        contactNameCache)
    }

    /**
     * Gets all unread messages that chronologically follow the most recent
     * outgoing message for the specified conversation.
     *
     * The resulting list is sorted by date, from least recent to most recent.
     */
    fun getMessagesUnread(
        conversationId: ConversationId): List<Message> = synchronized(this)
    {
        // Retrieve the most recent outgoing message
        var cursor = database.query(
            TABLE_MESSAGE,
            arrayOf("COALESCE(MAX($COLUMN_DATE), 0) AS $COLUMN_DATE"),
            "$COLUMN_DID=? AND $COLUMN_CONTACT=? AND $COLUMN_INCOMING=0",
            arrayOf(conversationId.did, conversationId.contact),
            null, null, null)
        cursor.moveToFirst()
        var date: Long = 0
        if (!cursor.isAfterLast) {
            date = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_DATE))
        }
        cursor.close()

        // Retrieve all unread messages with a date equal to or after the
        // most recent outgoing message
        cursor = database.query(
            TABLE_MESSAGE,
            messageColumns,
            "$COLUMN_DID=?"
            + " AND $COLUMN_CONTACT=?"
            + " AND $COLUMN_INCOMING=1"
            + " AND $COLUMN_DATE>=$date"
            + " AND $COLUMN_UNREAD=1",
            arrayOf(conversationId.did, conversationId.contact),
            null, null,
            "$COLUMN_DATE ASC, $COLUMN_DATABASE_ID DESC")
        return getMessagesCursor(cursor)
    }

    /**
     * Imports the database from the specified file descriptor.
     */
    fun import(importFd: ParcelFileDescriptor) = synchronized(this) {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        val backupFile = File("${dbFile.absolutePath}.backup")

        try {
            // Close database to persist it to disk before import
            database.close()

            // Try importing database, but restore from backup on failure
            dbFile.copyTo(backupFile, overwrite = true)
            try {
                val importStream = FileInputStream(importFd.fileDescriptor)
                val dbStream = FileOutputStream(dbFile)

                dbStream.channel.truncate(0)

                val buffer = ByteArray(1024)
                do {
                    val length = importStream.read(buffer)
                    if (length > 0) {
                        dbStream.write(buffer, 0, length)
                    } else {
                        break
                    }
                } while (true)
                importStream.close()
                dbStream.close()

                // Try refreshing database
                database = databaseHelper.writableDatabase

                replaceIndexOnNewThread(context)
            } catch (e: Exception) {
                backupFile.copyTo(dbFile, overwrite = true)
                throw e
            }
        } finally {
            // Refresh database
            database.close()
            database = databaseHelper.writableDatabase

            backupFile.delete()
        }
    }

    /**
     * Inserts a new outgoing message into the database with the specified
     * conversation ID and text. This message is marked as in the process of
     * being delivered.
     *
     * @return The database ID of the inserted message.
     */
    fun insertMessageDeliveryInProgress(
        conversationId: ConversationId,
        texts: List<String>): List<Long> = synchronized(this)
    {
        try {
            database.beginTransaction()

            val databaseIds = texts.map {
                val values = ContentValues()
                values.putNull(COLUMN_VOIP_ID)
                values.put(COLUMN_DATE, Date().time / 1000L)
                values.put(COLUMN_INCOMING, 0L)
                values.put(COLUMN_DID, conversationId.did)
                values.put(COLUMN_CONTACT, conversationId.contact)
                values.put(COLUMN_MESSAGE, it)
                values.put(COLUMN_UNREAD, 0L)
                values.put(COLUMN_DELIVERED, 0L)
                values.put(COLUMN_DELIVERY_IN_PROGRESS, 1L)

                val databaseId = database.insertOrThrow(TABLE_MESSAGE, null,
                                                        values)
                if (databaseId == -1L) {
                    throw Exception("Returned database ID was -1")
                }
                databaseId
            }

            database.setTransactionSuccessful()

            for (databaseId in databaseIds) {
                val message = getMessageDatabaseIdWithoutLock(databaseId)
                if (message != null) {
                    addMessageToIndexOnNewThread(context, message)
                }
            }
            updateShortcuts(context)

            return databaseIds
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Inserts a new draft message into the database associated with the
     * specified conversation and containing the specified text.
     *
     * Any existing draft message with the specified conversation is
     * automatically removed. If an empty message is inserted, any existing
     * message is removed from the database.
     */
    fun insertMessageDraft(conversationId: ConversationId,
                           text: String) = synchronized(this)
    {
        try {
            database.beginTransaction()

            insertMessageDraftWithoutLock(conversationId, text)

            database.setTransactionSuccessful()

            updateShortcuts(context)
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Inserts new messages from the VoIP.ms API.
     *
     * @param retrieveDeletedMessages If true, then any existing messages that
     * have the same VoIP.ms ID as a message in the list of new messages are
     * marked as not deleted.
     * @return The conversation IDs associated with the newly added messages.
     */
    fun insertMessagesVoipMsApi(
        incomingMessages: List<SyncService.IncomingMessage>,
        retrieveDeletedMessages: Boolean)
        : Set<ConversationId> = synchronized(this)
    {
        try {
            database.beginTransaction()

            val addedConversationIds = mutableSetOf<ConversationId>()
            val addedDatabaseIds = mutableListOf<Long>()
            for (incomingMessage in incomingMessages) {
                if (retrieveDeletedMessages) {
                    // Retrieve deleted messages is true, so we should
                    // remove this message from our list of deleted messages
                    removeDeletedVoipId(setOf(incomingMessage.did),
                                        incomingMessage.voipId)
                } else if (isVoipIdDeleted(incomingMessage.did,
                                           incomingMessage.voipId)) {
                    // Retrieve deleted messages is not true and this
                    // message has been previously deleted, so we
                    // shouldn't add it back
                    continue
                }

                val databaseId = getMessageDatabaseIdVoipId(
                    incomingMessage.did, incomingMessage.voipId)
                if (databaseId != null) {
                    // Don't add the message if it already exists in our
                    // database
                    continue
                }

                // Add new message to database
                val values = ContentValues()
                values.put(COLUMN_VOIP_ID, incomingMessage.voipId)
                values.put(COLUMN_DATE, incomingMessage.date.time / 1000L)
                values.put(COLUMN_INCOMING,
                           if (incomingMessage.isIncoming) 1L else 0L)
                values.put(COLUMN_DID, incomingMessage.did)
                values.put(COLUMN_CONTACT, incomingMessage.contact)
                values.put(COLUMN_MESSAGE, incomingMessage.text)
                values.put(COLUMN_UNREAD,
                           if (incomingMessage.isIncoming) 1L else 0L)
                values.put(COLUMN_DELIVERED, 1L)
                values.put(COLUMN_DELIVERY_IN_PROGRESS, 0L)

                val newId = database.insertOrThrow(TABLE_MESSAGE, null,
                                                   values)
                if (newId == -1L) {
                    throw Exception("Returned database ID was -1")
                }
                addedConversationIds.add(
                    ConversationId(
                        incomingMessage.did,
                        incomingMessage.contact))
                addedDatabaseIds.add(newId)

                // Mark conversation as unarchived
                database.delete(TABLE_ARCHIVED,
                                "$COLUMN_DID=? AND $COLUMN_CONTACT=?",
                                arrayOf(incomingMessage.did,
                                        incomingMessage.contact))
            }

            database.setTransactionSuccessful()

            addedDatabaseIds
                .mapNotNull { getMessageDatabaseIdWithoutLock(it) }
                .forEach {
                    addMessageToIndexOnNewThread(context, it)
                }
            if (addedDatabaseIds.isNotEmpty()) {
                updateShortcuts(context)
            }

            return addedConversationIds
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Returns whether the specified conversation is archived.
     */
    fun isConversationArchived(
        conversationId: ConversationId): Boolean = synchronized(this)
    {
        val cursor = database.query(TABLE_ARCHIVED,
                                    archivedColumns,
                                    "$COLUMN_DID=? AND $COLUMN_CONTACT=?",
                                    arrayOf(conversationId.did,
                                            conversationId.contact),
                                    null, null, null)
        cursor.moveToFirst()
        val archived = !cursor.isAfterLast
        cursor.close()
        return archived
    }

    /**
     * Returns whether the specified conversation has any messages or drafts.
     */
    fun isConversationEmpty(
        conversationId: ConversationId): Boolean = synchronized(this)
    {
        val cursor = database.query(TABLE_MESSAGE,
                                    messageColumns,
                                    "$COLUMN_DID=? AND $COLUMN_CONTACT=?",
                                    arrayOf(conversationId.did,
                                            conversationId.contact),
                                    null, null, null)
        cursor.moveToFirst()
        val hasMessages =
            !cursor.isAfterLast
            || getMessageDraftWithoutLock(conversationId) != null
        cursor.close()
        return hasMessages
    }

    /**
     * Marks the specified conversation as archived.
     */
    fun markConversationArchived(
        conversationId: ConversationId) = synchronized(this)
    {
        try {
            database.beginTransaction()

            val databaseId = getArchivedDatabaseIdConversation(conversationId)

            val values = ContentValues()
            if (databaseId != null) {
                values.put(COLUMN_DATABASE_ID, databaseId)
            }
            values.put(COLUMN_DID, conversationId.did)
            values.put(COLUMN_CONTACT, conversationId.contact)
            values.put(COLUMN_ARCHIVED, "1")

            database.replaceOrThrow(TABLE_ARCHIVED, null, values)

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Marks the specified conversation as read.
     **/
    fun markConversationRead(
        conversationId: ConversationId) = synchronized(this)
    {
        try {
            database.beginTransaction()

            val contentValues = ContentValues()
            contentValues.put(COLUMN_UNREAD, "0")

            database.update(TABLE_MESSAGE,
                            contentValues,
                            "$COLUMN_DID=? AND $COLUMN_CONTACT=?",
                            arrayOf(conversationId.did, conversationId.contact))

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Marks the specified conversation as unarchived.
     */
    fun markConversationUnarchived(
        conversationId: ConversationId) = synchronized(this)
    {
        try {
            database.beginTransaction()

            database.delete(TABLE_ARCHIVED,
                            "$COLUMN_DID=? AND $COLUMN_CONTACT=?",
                            arrayOf(conversationId.did, conversationId.contact))

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Marks the specified conversation as unread.
     */
    fun markConversationUnread(
        conversationId: ConversationId) = synchronized(this)
    {
        try {
            database.beginTransaction()

            val contentValues = ContentValues()
            contentValues.put(COLUMN_UNREAD, "1")

            database.update(TABLE_MESSAGE,
                            contentValues,
                            "$COLUMN_DID=? AND $COLUMN_CONTACT=?",
                            arrayOf(conversationId.did, conversationId.contact))

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Marks the message with the specified database ID as in the process of
     * being delivered.
     */
    fun markMessageDeliveryInProgress(databaseId: Long) = synchronized(this) {
        try {
            database.beginTransaction()

            val contentValues = ContentValues()
            contentValues.put(COLUMN_DELIVERED, "0")
            contentValues.put(COLUMN_DELIVERY_IN_PROGRESS, "1")

            database.update(TABLE_MESSAGE,
                            contentValues,
                            "$COLUMN_DATABASE_ID=?",
                            arrayOf(databaseId.toString()))

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Marks the message with the specified database ID as having failed to
     * be sent.
     */
    fun markMessageNotSent(databaseId: Long) = synchronized(this) {
        try {
            database.beginTransaction()

            val contentValues = ContentValues()
            contentValues.put(COLUMN_DELIVERED, "0")
            contentValues.put(COLUMN_DELIVERY_IN_PROGRESS, "0")

            database.update(TABLE_MESSAGE,
                            contentValues,
                            "$COLUMN_DATABASE_ID=?",
                            arrayOf(databaseId.toString()))

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Marks the message with the specified database ID as having been sent.
     * In addition, adds the specified VoIP.ms ID to the message.
     */
    fun markMessageSent(databaseId: Long, voipId: Long) = synchronized(this) {
        try {
            database.beginTransaction()

            val contentValues = ContentValues()
            contentValues.put(COLUMN_VOIP_ID, voipId)
            contentValues.put(COLUMN_DELIVERED, "1")
            contentValues.put(COLUMN_DELIVERY_IN_PROGRESS, "0")
            contentValues.put(COLUMN_DATE, Date().time / 1000L)

            database.update(TABLE_MESSAGE,
                            contentValues,
                            "$COLUMN_DATABASE_ID=?",
                            arrayOf(databaseId.toString()))

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Gets the database ID for the row in the archived table for the specified
     * conversation.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     *
     * @return Null if the row does not exist.
     */
    private fun getArchivedDatabaseIdConversation(
        conversationId: ConversationId): Long? {
        val cursor = database.query(TABLE_ARCHIVED,
                                    archivedColumns,
                                    "$COLUMN_DID=? AND $COLUMN_CONTACT=?",
                                    arrayOf(conversationId.did,
                                            conversationId.contact),
                                    null, null, null)
        if (cursor.moveToFirst()) {
            val databaseId = cursor.getLong(
                cursor.getColumnIndexOrThrow(
                    COLUMN_DATABASE_ID))
            cursor.close()
            return databaseId
        }
        cursor.close()
        return null
    }

    /**
     * Gets the database ID for the row in the draft table for the specified
     * conversation.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     *
     * @return Null if the row does not exist.
     */
    private fun getDraftDatabaseIdConversation(
        conversationId: ConversationId): Long? {
        val cursor = database.query(TABLE_DRAFT,
                                    draftColumns,
                                    "$COLUMN_DID=? AND $COLUMN_CONTACT=?",
                                    arrayOf(conversationId.did,
                                            conversationId.contact),
                                    null, null, null)
        if (cursor.moveToFirst()) {
            val databaseId = cursor.getLong(
                cursor.getColumnIndexOrThrow(
                    COLUMN_DATABASE_ID))
            cursor.close()
            return databaseId
        }
        cursor.close()
        return null
    }

    /**
     * Gets the database ID for the row in the database with the specified
     * VoIP.ms ID.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     *
     * @return Null if the row does not exist.
     */
    private fun getMessageDatabaseIdVoipId(did: String, voipId: Long): Long? {
        val cursor = database.query(TABLE_MESSAGE,
                                    messageColumns,
                                    "$COLUMN_DID=? AND $COLUMN_VOIP_ID=?",
                                    arrayOf(did, voipId.toString()),
                                    null, null, null)
        if (cursor.moveToFirst()) {
            val databaseId = cursor.getLong(
                cursor.getColumnIndexOrThrow(
                    COLUMN_DATABASE_ID))
            cursor.close()
            return databaseId
        }
        cursor.close()
        return null
    }

    /**
     * Retrieves the message with the specified database ID from the database.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     *
     * @return Null if the message does not exist.
     */
    private fun getMessageDatabaseIdWithoutLock(databaseId: Long): Message? {
        val cursor = database.query(TABLE_MESSAGE,
                                    messageColumns,
                                    "$COLUMN_DATABASE_ID=?",
                                    arrayOf(databaseId.toString()),
                                    null, null, null)
        val messages = getMessagesCursor(cursor)
        if (messages.size > 0) {
            return messages[0]
        }
        return null
    }

    /**
     * Gets the draft message for the specified conversation.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     */
    private fun getMessageDraftWithoutLock(
        conversationId: ConversationId): Message? {
        val cursor = database.query(
            TABLE_DRAFT, draftColumns,
            "$COLUMN_DID=? AND $COLUMN_CONTACT=?",
            arrayOf(conversationId.did, conversationId.contact),
            null, null, null)
        cursor.moveToFirst()
        var message: Message? = null
        if (!cursor.isAfterLast) {
            message = Message(
                cursor.getString(cursor.getColumnIndexOrThrow(
                    COLUMN_DID)),
                cursor.getString(cursor.getColumnIndexOrThrow(
                    COLUMN_CONTACT)),
                cursor.getString(cursor.getColumnIndexOrThrow(
                    COLUMN_MESSAGE)))
        }
        cursor.close()
        return message
    }

    /**
     * Get the messages associated with the specified conversation.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     */
    private fun getMessagesConversation(
        conversationId: ConversationId): List<Message> {
        val cursor = database.query(
            TABLE_MESSAGE,
            messageColumns,
            "$COLUMN_DID=? AND $COLUMN_CONTACT=?",
            arrayOf(conversationId.did, conversationId.contact),
            null, null, null)
        return getMessagesCursor(cursor)
    }

    /**
     * Retrieves all of the messages that can be accessed by the specified
     * cursor. This function consumes the cursor.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     */
    private fun getMessagesCursor(cursor: Cursor): MutableList<Message> {
        val messages = mutableListOf<Message>()
        cursor.moveToFirst()
        while (!cursor.isAfterLast) {
            val message = Message(
                cursor.getLong(
                    cursor.getColumnIndexOrThrow(
                        COLUMN_DATABASE_ID)),
                if (cursor.isNull(
                        cursor.getColumnIndexOrThrow(
                            COLUMN_VOIP_ID))) {
                    null
                } else {
                    cursor.getLong(cursor.getColumnIndex(COLUMN_VOIP_ID))
                },
                cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_DATE)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_INCOMING)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONTACT)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_UNREAD)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_DELIVERED)),
                cursor.getLong(
                    cursor.getColumnIndexOrThrow(
                        COLUMN_DELIVERY_IN_PROGRESS)))
            messages.add(message)
            cursor.moveToNext()
        }
        cursor.close()
        return messages
    }

    /**
     * Gets the draft messages for all conversations associated with the
     * specified DIDs.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     */
    private fun getMessagesDraft(dids: Set<String>): List<Message> {
        val cursor = database.query(
            TABLE_DRAFT,
            draftColumns,
            dids.joinToString(" OR ") { "$COLUMN_DID=?" },
            dids.toTypedArray(),
            null, null,
            "$COLUMN_DID DESC, $COLUMN_CONTACT DESC")
        cursor.moveToFirst()
        val messages = mutableListOf<Message>()
        while (!cursor.isAfterLast) {
            messages.add(Message(
                cursor.getString(cursor.getColumnIndexOrThrow(
                    COLUMN_DID)),
                cursor.getString(cursor.getColumnIndexOrThrow(
                    COLUMN_CONTACT)),
                cursor.getString(cursor.getColumnIndexOrThrow(
                    COLUMN_MESSAGE))))
            cursor.moveToNext()
        }
        cursor.close()
        return messages
    }

    /**
     * Gets the most recent draft message in each conversation associated
     * with the specified DIDs that matches a specified filter constraint.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     */
    private fun getMessagesDraftFiltered(
        dids: Set<String>, filterConstraint: String): List<Message> {
        return getMessagesDraft(dids)
            .filter {
                it.text
                    .toLowerCase(Locale.getDefault())
                    .contains(filterConstraint)
            }
            .toMutableList()
    }

    /**
     * Gets the most recent message in each conversation associated with the
     * specified DIDs that matches a specified filter constraint. The resulting
     * list is sorted by date, from most recent to least recent.
     */
    private fun getMessagesMostRecentFilteredWithoutLock(
        dids: Set<String>,
        filterConstraint: String = "",
        contactNameCache: MutableMap<String, String>? = null): List<Message> {
        val queryParams = mutableListOf("%$filterConstraint%")
        val numberFilterConstraint = getDigitsOfString(filterConstraint)
        if (numberFilterConstraint != "") {
            val numericFilterString = "%$numberFilterConstraint%"
            queryParams.add(numericFilterString)
            queryParams.add(numericFilterString)
        }
        val numericFilterStringQuery = if (numberFilterConstraint != "") {
            "OR $COLUMN_CONTACT LIKE ? OR $COLUMN_DID LIKE ?"
        } else {
            ""
        }

        var allMessages = mutableListOf<Message>()
        for (did in dids) {
            // First, retrieve the most recent message for each
            // conversation, filtering only on the DID phone number,
            // contact phone number and message text
            var query = ("SELECT * FROM $TABLE_MESSAGE a INNER JOIN"
                         + " (SELECT $COLUMN_DATABASE_ID, $COLUMN_CONTACT,"
                         + " MAX($COLUMN_DATE)"
                         + " AS $COLUMN_DATE FROM $TABLE_MESSAGE"
                         + " WHERE ($COLUMN_MESSAGE LIKE ?"
                         + " COLLATE NOCASE $numericFilterStringQuery)"
                         + " AND $COLUMN_DID=? GROUP BY $COLUMN_CONTACT)"
                         + " b on a.$COLUMN_DATABASE_ID=b.$COLUMN_DATABASE_ID"
                         + " AND a.$COLUMN_DATE=b.$COLUMN_DATE"
                         + " ORDER BY $COLUMN_DATE DESC, $COLUMN_DATABASE_ID"
                         + " DESC")
            var cursor = database.rawQuery(query,
                                           queryParams.plus(did).toTypedArray())
            val messages = getMessagesCursor(cursor)

            // Then, retrieve the most recent message for each conversation
            // without filtering; if any conversation present in the second
            // list is not present in the first list, filter the message in
            // the second list on contact name and add it to the first list
            // if there is a match
            query = ("SELECT * FROM $TABLE_MESSAGE a INNER JOIN"
                     + " (SELECT $COLUMN_DATABASE_ID, $COLUMN_CONTACT,"
                     + " MAX($COLUMN_DATE)"
                     + " AS $COLUMN_DATE FROM $TABLE_MESSAGE"
                     + " WHERE $COLUMN_DID=? GROUP BY $COLUMN_CONTACT)"
                     + " b on a.$COLUMN_DATABASE_ID=b.$COLUMN_DATABASE_ID"
                     + " AND a.$COLUMN_DATE=b.$COLUMN_DATE"
                     + " ORDER BY $COLUMN_DATE DESC, $COLUMN_DATABASE_ID DESC")
            cursor = database.rawQuery(query, arrayOf(did))
            messages.addAll(
                getMessagesCursor(cursor)
                    .filterNot { it.contact in messages.map { it2 -> it2.contact } }
                    .filter {
                        val contactName = getContactName(context,
                                                         it.contact,
                                                         contactNameCache)
                        contactName?.toLowerCase(Locale.getDefault())?.contains(
                            filterConstraint)
                        ?: false
                    })

            allMessages.addAll(messages)
        }

        // Replace messages with any applicable draft messages
        val draftMessages = getMessagesDraftFiltered(dids,
                                                     filterConstraint)
        for (draftMessage in draftMessages) {
            var added = false
            allMessages = allMessages.map {
                if (it.conversationId == draftMessage.conversationId) {
                    added = true
                    draftMessage
                } else {
                    it
                }
            }.toMutableList()
            if (!added && draftMessage.did in dids) {
                allMessages.add(0, draftMessage)
            }
        }

        allMessages.sort()
        return allMessages
    }

    /**
     * Inserts a new draft message into the database associated with the
     * specified conversation and containing the specified text.
     *
     * Any existing draft message with the specified conversation is
     * automatically removed. If an empty message is inserted, any existing
     * message is removed from the database.
     *
     * This method intentionally does not use transaction or lock semantics;
     * this is a responsibility of the caller.
     */
    private fun insertMessageDraftWithoutLock(conversationId: ConversationId,
                                              text: String) {
        val databaseId = getDraftDatabaseIdConversation(conversationId)

        // If text is empty, then delete any existing draft message
        if (text == "") {
            if (databaseId != null) {
                database.delete(TABLE_DRAFT,
                                "$COLUMN_DATABASE_ID=?",
                                arrayOf(databaseId.toString()))
            }
            return
        }

        val values = ContentValues()
        if (databaseId != null) {
            values.put(COLUMN_DATABASE_ID, databaseId)
        }
        values.put(COLUMN_DID, conversationId.did)
        values.put(COLUMN_CONTACT, conversationId.contact)
        values.put(COLUMN_MESSAGE, text)

        database.replaceOrThrow(TABLE_DRAFT, null, values)
    }

    /**
     * Inserts the deleted VoIP.ms message ID associated with the specified
     * DID into the database.
     *
     * This method intentionally does not use transaction or lock semantics;
     * this is a responsibility of the caller.
     */
    private fun insertVoipIdDeleted(did: String, voipId: Long) {
        if (isVoipIdDeleted(did, voipId)) {
            // VoIP.ms ID and DID already in table
            return
        }

        val values = ContentValues()
        values.put(COLUMN_VOIP_ID, voipId)
        values.put(COLUMN_DID, did)

        database.replaceOrThrow(TABLE_DELETED, null, values)
    }

    /**
     * Returns whether the specified VoIP.ms message ID associated with the
     * specified DID is marked as deleted.
     *
     * This method intentionally does not use lock semantics; this is a
     * responsibility of the caller.
     */
    private fun isVoipIdDeleted(did: String, voipId: Long): Boolean {
        val cursor = database.query(TABLE_DELETED,
                                    deletedColumns,
                                    "$COLUMN_DID=? AND $COLUMN_VOIP_ID=?",
                                    arrayOf(did, voipId.toString()),
                                    null, null, null)
        cursor.moveToFirst()
        val deleted = !cursor.isAfterLast
        cursor.close()
        return deleted
    }

    /**
     * Update the app shortcuts.
     */
    private fun updateShortcuts(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val shortcutManager = context.getSystemService(
                Context.SHORTCUT_SERVICE) as ShortcutManager

            // There is one static shortcut
            val maxCount = shortcutManager.maxShortcutCountPerActivity - 1

            val messages = getMessagesMostRecentFilteredWithoutLock(
                net.kourlas.voipms_sms.preferences.getDids(
                    context, onlyShowInConversationsView = true))
            val shortcutInfoList = messages.zip(0 until maxCount).map {
                val message = it.first

                val intent = Intent(context, ConversationActivity::class.java)
                intent.action = "android.intent.action.VIEW"
                intent.putExtra(
                    context.getString(R.string.conversation_did),
                    message.did)
                intent.putExtra(
                    context.getString(R.string.conversation_contact),
                    message.contact)

                val contactBitmap = getContactPhotoBitmap(context,
                                                          message.contact)
                val icon = if (contactBitmap == null) {
                    Icon.createWithResource(
                        context, R.drawable.ic_shortcut_account_circle)
                } else {
                    Icon.createWithBitmap(applyCircularMask(contactBitmap))
                }

                val label = getContactName(context, message.contact)
                            ?: getFormattedPhoneNumber(message.contact)

                ShortcutInfo.Builder(
                    context, message.conversationId.getId())
                    .setIcon(icon)
                    .setIntent(intent)
                    .setLongLabel(label)
                    .setShortLabel(label.split(" ")[0])
                    .build()
            }
            shortcutManager.removeAllDynamicShortcuts()
            shortcutManager.addDynamicShortcuts(shortcutInfoList)
        }
    }

    /**
     * Removes the deleted VoIP.ms message ID associated with the specified
     * DIDs from the database.
     *
     * This method intentionally does not use transaction or lock semantics;
     * this is a responsibility of the caller.
     */
    private fun removeDeletedVoipId(dids: Set<String>, voipId: Long) {
        database.delete(
            TABLE_DELETED,
            "(${dids.joinToString(" OR ") { "$COLUMN_DID=?" }})"
            + " AND $COLUMN_VOIP_ID=?",
            dids.plus(voipId.toString()).toTypedArray())
    }

    /**
     * Subclass of the SQLiteOpenHelper class for use with the Database class.
     *
     * @param context The context to be used by SQLiteOpenHelper.
     */
    private inner class DatabaseHelper internal constructor(
        context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null,
                                             DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            try {
                db.beginTransaction()

                db.execSQL(DATABASE_MESSAGE_TABLE_CREATE)
                db.execSQL(DATABASE_DELETED_TABLE_CREATE)
                db.execSQL(DATABASE_DRAFT_TABLE_CREATE)
                db.execSQL(DATABASE_ARCHIVED_TABLE_CREATE)

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int,
                               newVersion: Int) {
            if (oldVersion <= 5) {
                // For version 5 and below, the database was nothing more
                // than a cache so it can simply be dropped
                try {
                    db.beginTransaction()

                    db.execSQL("DROP TABLE IF EXISTS sms")

                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }

                onCreate(db)
                return
            }

            // After version 5, the database must be converted; it cannot
            // be simply dropped
            try {
                db.beginTransaction()

                if (oldVersion <= 6) {
                    handleTimeConversion6(db)
                }
                if (oldVersion <= 7) {
                    handleDraftAddition7(db)
                }
                if (oldVersion <= 8) {
                    handleDeletedConversion8(db)
                    handleDraftConversion8(db)
                    handleArchivedAddition8(db)
                    handleColumnRemoval8(db)
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        /**
         * Handles DST conversion from version 6 onwards.
         *
         * @param db The database to use.
         */
        fun handleTimeConversion6(db: SQLiteDatabase) {
            // In version 6, dates from VoIP.ms were parsed as if
            // they did not have daylight savings time when
            // they actually did; the code below re-parses the dates
            // properly
            val table = "sms"
            val columns = arrayOf("DatabaseId", "VoipId", "Date",
                                  "Type", "Did", "Contact", "Text",
                                  "Unread", "Deleted", "Delivered",
                                  "DeliveryInProgress")
            val cursor = db.query(table, columns, null, null, null,
                                  null,
                                  null)
            cursor.moveToFirst()
            while (!cursor.isAfterLast) {
                val databaseId = cursor.getLong(
                    cursor.getColumnIndexOrThrow(columns[0]))
                val voipId = if (cursor.isNull(cursor.getColumnIndexOrThrow(
                        columns[1]))) null else cursor.getLong(
                    cursor.getColumnIndex(columns[1]))
                var date = cursor.getLong(
                    cursor.getColumnIndexOrThrow(columns[2]))
                val type = cursor.getLong(
                    cursor.getColumnIndexOrThrow(columns[3]))
                val did = cursor.getString(
                    cursor.getColumnIndexOrThrow(columns[4]))
                val contact = cursor.getString(
                    cursor.getColumnIndexOrThrow(columns[5]))
                val text = cursor.getString(
                    cursor.getColumnIndexOrThrow(columns[6]))
                val unread = cursor.getLong(
                    cursor.getColumnIndexOrThrow(columns[7]))
                val deleted = cursor.getLong(
                    cursor.getColumnIndexOrThrow(columns[8]))
                val delivered = cursor.getLong(
                    cursor.getColumnIndexOrThrow(columns[9]))
                val deliveryInProgress = cursor.getLong(
                    cursor.getColumnIndexOrThrow(columns[10]))

                // Incorrect date has an hour removed outside of
                // daylight savings time
                var dateObj = Date(date * 1000)

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                // Incorrect date converted to UTC with an hour
                // removed outside of daylight savings time
                val dateString = sdf.format(dateObj)

                // Incorrect date string is parsed as if it were
                // EST/EDT; it is now four hours ahead of EST/EDT
                // at all times
                sdf.timeZone = TimeZone.getTimeZone("America/New_York")
                dateObj = sdf.parse(dateString) ?: throw Exception(
                    "Could not parse date $dateString")

                val calendar = Calendar.getInstance(
                    TimeZone.getTimeZone("America/New_York"), Locale.US)
                calendar.time = dateObj
                calendar.add(Calendar.HOUR_OF_DAY, -4)
                // Date is now stored correctly
                date = calendar.time.time / 1000L

                val values = ContentValues()
                values.put(columns[0], databaseId)
                values.put(columns[1], voipId)
                values.put(columns[2], date)
                values.put(columns[3], type)
                values.put(columns[4], did)
                values.put(columns[5], contact)
                values.put(columns[6], text)
                values.put(columns[7], unread)
                values.put(columns[8], deleted)
                values.put(columns[9], delivered)
                values.put(columns[10], deliveryInProgress)

                db.replace(table, null, values)
                cursor.moveToNext()
            }
            cursor.close()
        }

        /**
         * Handles the addition of a draft feature from version 7 onwards.
         *
         * @param db The database to use.
         */
        fun handleDraftAddition7(db: SQLiteDatabase) = db.execSQL(
            "ALTER TABLE sms" +
            " ADD Draft INTEGER NOT NULL DEFAULT(0)")

        /**
         * Handles a change in the way deleted messages are tracked from version
         * 8 onwards.
         *
         * @param db The database to use.
         */
        fun handleDeletedConversion8(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE deleted(" +
                       "DatabaseId INTEGER PRIMARY KEY AUTOINCREMENT" +
                       " NOT NULL," +
                       "VoipId INTEGER NOT NULL," +
                       "Did TEXT NOT NULL)")

            val table = "sms"
            val columns = arrayOf("DatabaseId", "VoipId", "Date",
                                  "Type", "Did", "Contact", "Text",
                                  "Unread", "Deleted", "Delivered",
                                  "DeliveryInProgress", "Draft")
            val cursor = db.query(table, columns, "Deleted=1",
                                  null, null, null, null)
            cursor.moveToFirst()
            while (!cursor.isAfterLast) {
                val voipId = (if (cursor.isNull(cursor.getColumnIndexOrThrow(
                        columns[1]))) null else cursor.getLong(
                    cursor.getColumnIndex(columns[1]))) ?: continue
                val did = cursor.getString(cursor.getColumnIndexOrThrow(
                    columns[4]))

                val values = ContentValues()
                values.put(COLUMN_VOIP_ID, voipId)
                values.put(COLUMN_DID, did)
                db.replaceOrThrow(TABLE_DELETED, null, values)

                cursor.moveToNext()
            }
            cursor.close()
            db.delete(table, "Deleted=1", null)
        }

        /**
         * Handles a change in the way draft messages are tracked from version
         * 8 onwards.
         *
         * @param db The database to use.
         */
        fun handleDraftConversion8(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE draft(" +
                       "DatabaseId INTEGER PRIMARY KEY AUTOINCREMENT" +
                       " NOT NULL," +
                       "Did TEXT NOT NULL," +
                       "Contact TEXT NOT NULL," +
                       "Text TEXT NOT NULL)")

            val table = "sms"
            val columns = arrayOf("DatabaseId", "VoipId", "Date",
                                  "Type", "Did", "Contact", "Text",
                                  "Unread", "Deleted", "Delivered",
                                  "DeliveryInProgress", "Draft")
            val cursor = db.query(table, columns, "Draft=1",
                                  null, null, null, null)
            cursor.moveToFirst()
            while (!cursor.isAfterLast) {
                val did = cursor.getString(cursor.getColumnIndexOrThrow(
                    columns[4]))
                val contact = cursor.getString(cursor.getColumnIndexOrThrow(
                    columns[5]))
                val text = cursor.getString(cursor.getColumnIndexOrThrow(
                    columns[6]))

                if (text != "") {
                    val values = ContentValues()
                    values.put(COLUMN_DID, did)
                    values.put(COLUMN_CONTACT, contact)
                    values.put(COLUMN_MESSAGE, text)
                    db.replaceOrThrow(TABLE_DRAFT, null, values)
                }

                cursor.moveToNext()
            }
            cursor.close()
            db.delete(table, "Draft=1", null)
        }

        /**
         * Handles the addition of a archived feature from version 8 onwards.
         *
         * @param db The database to use.
         */
        fun handleArchivedAddition8(db: SQLiteDatabase) = db.execSQL(
            "CREATE TABLE archived(" +
            "DatabaseId INTEGER PRIMARY KEY AUTOINCREMENT" +
            " NOT NULL," +
            "Did TEXT NOT NULL," +
            "Contact TEXT NOT NULL," +
            "Archived INTEGER NOT NULL)")

        /**
         * Handles the removal of the draft and deleted columns from the main
         * table from version 8 onwards.
         *
         * @param db The database to use.
         */
        fun handleColumnRemoval8(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE sms RENAME TO sms_backup")
            db.execSQL("CREATE TABLE sms(" +
                       "DatabaseId INTEGER PRIMARY KEY AUTOINCREMENT NOT" +
                       " NULL," +
                       "VoipId INTEGER," +
                       "Date INTEGER NOT NULL," +
                       "Type INTEGER NOT NULL," +
                       "Did TEXT NOT NULL," +
                       "Contact TEXT NOT NULL," +
                       "Text TEXT NOT NULL," +
                       "Unread INTEGER NOT NULL," +
                       "Delivered INTEGER NOT NULL," +
                       "DeliveryInProgress INTEGER NOT NULL)")
            db.execSQL("INSERT INTO sms SELECT" +
                       " DatabaseId, VoipId, Date, Type, Did," +
                       " Contact, Text, Unread, Delivered," +
                       " DeliveryInProgress" +
                       " FROM sms_backup")
            db.execSQL("DROP TABLE sms_backup")
        }
    }

    companion object {
        const val DATABASE_NAME = "sms.db"
        private const val DATABASE_VERSION = 9

        private const val TABLE_MESSAGE = "sms"
        private const val TABLE_DELETED = "deleted"
        private const val TABLE_DRAFT = "draft"
        private const val TABLE_ARCHIVED = "archived"

        const val COLUMN_DATABASE_ID = "DatabaseId"
        const val COLUMN_VOIP_ID = "VoipId"
        const val COLUMN_DATE = "Date"
        const val COLUMN_INCOMING = "Type"
        const val COLUMN_DID = "Did"
        const val COLUMN_CONTACT = "Contact"
        const val COLUMN_MESSAGE = "Text"
        const val COLUMN_UNREAD = "Unread"
        const val COLUMN_DELIVERED = "Delivered"
        const val COLUMN_DELIVERY_IN_PROGRESS = "DeliveryInProgress"
        const val COLUMN_ARCHIVED = "Archived"

        private val messageColumns = arrayOf(COLUMN_DATABASE_ID,
                                             COLUMN_VOIP_ID,
                                             COLUMN_DATE,
                                             COLUMN_INCOMING,
                                             COLUMN_DID,
                                             COLUMN_CONTACT,
                                             COLUMN_MESSAGE,
                                             COLUMN_UNREAD,
                                             COLUMN_DELIVERED,
                                             COLUMN_DELIVERY_IN_PROGRESS)
        private val deletedColumns = arrayOf(COLUMN_DATABASE_ID,
                                             COLUMN_VOIP_ID,
                                             COLUMN_DID)
        private val draftColumns = arrayOf(COLUMN_DATABASE_ID,
                                           COLUMN_DID,
                                           COLUMN_CONTACT,
                                           COLUMN_MESSAGE)
        private val archivedColumns = arrayOf(COLUMN_DATABASE_ID,
                                              COLUMN_DID,
                                              COLUMN_CONTACT,
                                              COLUMN_ARCHIVED)

        private const val DATABASE_MESSAGE_TABLE_CREATE =
            "CREATE TABLE " + TABLE_MESSAGE + "(" +
            COLUMN_DATABASE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT" +
            " NOT NULL," +
            COLUMN_VOIP_ID + " INTEGER," +
            COLUMN_DATE + " INTEGER NOT NULL," +
            COLUMN_INCOMING + " INTEGER NOT NULL," +
            COLUMN_DID + " TEXT NOT NULL," +
            COLUMN_CONTACT + " TEXT NOT NULL," +
            COLUMN_MESSAGE + " TEXT NOT NULL," +
            COLUMN_UNREAD + " INTEGER NOT NULL," +
            COLUMN_DELIVERED + " INTEGER NOT NULL," +
            COLUMN_DELIVERY_IN_PROGRESS + " INTEGER NOT NULL)"
        private const val DATABASE_DELETED_TABLE_CREATE =
            "CREATE TABLE " + TABLE_DELETED + "(" +
            COLUMN_DATABASE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT" +
            " NOT NULL," +
            COLUMN_VOIP_ID + " INTEGER NOT NULL," +
            COLUMN_DID + " TEXT NOT NULL)"
        private const val DATABASE_DRAFT_TABLE_CREATE =
            "CREATE TABLE " + TABLE_DRAFT + "(" +
            COLUMN_DATABASE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT" +
            " NOT NULL," +
            COLUMN_DID + " TEXT NOT NULL," +
            COLUMN_CONTACT + " TEXT NOT NULL," +
            COLUMN_MESSAGE + " TEXT NOT NULL)"
        private const val DATABASE_ARCHIVED_TABLE_CREATE =
            "CREATE TABLE " + TABLE_ARCHIVED + "(" +
            COLUMN_DATABASE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT" +
            " NOT NULL," +
            COLUMN_DID + " TEXT NOT NULL," +
            COLUMN_CONTACT + " TEXT NOT NULL," +
            COLUMN_ARCHIVED + " INTEGER NOT NULL)"

        // It is not a leak to store an instance to the Application object,
        // since it has the same lifetime as the application itself
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: Database? = null

        /**
         * Gets the sole instance of the Database class. Initializes the
         * instance if it does not already exist.
         */
        fun getInstance(context: Context): Database =
            instance ?: synchronized(this) {
                instance ?: Database(
                    context.applicationContext).also { instance = it }
            }
    }
}
