@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.desquared.encryptedroom.db

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.NativeSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile

private val PLAINTEXT_HEADER = "SQLite format 3\u0000".encodeToByteArray()
private val fileManager = NSFileManager.defaultManager

/**
 * Replaces a database that was created before encryption was enabled with an encrypted copy.
 *
 * SQLCipher cannot encrypt an existing plaintext database in place, so the content is copied into a
 * new SQLCipher database (schema first, then rows) and the original file is swapped out only after
 * the copy succeeded. Must run before Room opens the file.
 */
internal fun deleteIfPlaintext(dbPath: String, passphrase: ByteArray) {
    if (!fileManager.fileExistsAtPath(dbPath) || !hasPlaintextHeader(dbPath)) {
        return
    }

    // Closing the plaintext database checkpoints its WAL into the main file.
    NativeSQLiteDriver().open(dbPath).close()
    deleteFile("$dbPath-wal")
    deleteFile("$dbPath-shm")

    val encryptedPath = "$dbPath.encrypted"
    val backupPath = "$dbPath.plaintext"
    deleteFile(encryptedPath)
    try {
        copyIntoEncrypted(sourcePath = dbPath, targetPath = encryptedPath, passphrase = passphrase)
    } catch (failure: Throwable) {
        deleteFile(encryptedPath)
        throw failure
    }

    copyFile(from = dbPath, to = backupPath)
    deleteFile(dbPath)
    moveFile(from = encryptedPath, to = dbPath)
    deleteFile(backupPath)
    println("SQLCipherMigration: replaced the plaintext database with an encrypted copy")
}

internal fun hasPlaintextHeader(dbPath: String): Boolean {
    val data = NSData.dataWithContentsOfFile(dbPath) ?: return false
    if (data.length < PLAINTEXT_HEADER.size.toULong()) {
        return false
    }
    val bytes = ByteArray(PLAINTEXT_HEADER.size)
    bytes.usePinned { data.getBytes(it.addressOf(0), bytes.size.toULong()) }
    return bytes.contentEquals(PLAINTEXT_HEADER)
}

private fun copyIntoEncrypted(sourcePath: String, targetPath: String, passphrase: ByteArray) {
    val source = NativeSQLiteDriver().open(sourcePath)
    try {
        val target = SQLCipherDriver(passphrase).open(targetPath)
        try {
            copySchema(source, target)
            copyRows(source, target)
        } finally {
            target.close()
        }
    } finally {
        source.close()
    }
}

private fun copySchema(source: SQLiteConnection, target: SQLiteConnection) {
    for (type in listOf("table", "index", "trigger", "view")) {
        val statements = mutableListOf<String>()
        val statement = source.prepare(
            "SELECT sql FROM sqlite_master WHERE type = '$type' AND sql IS NOT NULL"
        )
        try {
            while (statement.step()) {
                statements += statement.getText(0)
            }
        } finally {
            statement.close()
        }
        statements.forEach { target.execSQL(it) }
    }
}

// shortcut: rows are buffered in memory before inserting; stream them if databases get large.
private fun copyRows(source: SQLiteConnection, target: SQLiteConnection) {
    val tables = mutableListOf<String>()
    val tableStatement = source.prepare(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
    )
    try {
        while (tableStatement.step()) {
            tables += tableStatement.getText(0)
        }
    } finally {
        tableStatement.close()
    }

    for (table in tables) {
        val quotedTable = '"' + table.replace("\"", "\"\"") + '"'
        val rows = mutableListOf<List<Any?>>()
        var columnCount = 0
        val select = source.prepare("SELECT * FROM $quotedTable")
        try {
            columnCount = select.getColumnCount()
            while (select.step()) {
                rows += List(columnCount) { readColumn(select, it) }
            }
        } finally {
            select.close()
        }
        if (rows.isEmpty()) {
            continue
        }

        val placeholders = List(columnCount) { "?" }.joinToString(", ")
        val insert = target.prepare("INSERT INTO $quotedTable VALUES ($placeholders)")
        try {
            for (row in rows) {
                insert.clearBindings()
                row.forEachIndexed { index, value -> bind(insert, index + 1, value) }
                insert.step()
                insert.reset()
            }
        } finally {
            insert.close()
        }
    }
}

private fun readColumn(statement: SQLiteStatement, index: Int): Any? =
    when (statement.getColumnType(index)) {
        SQLITE_DATA_INTEGER -> statement.getLong(index)
        SQLITE_DATA_FLOAT -> statement.getDouble(index)
        SQLITE_DATA_TEXT -> statement.getText(index)
        SQLITE_DATA_BLOB -> statement.getBlob(index)
        else -> null
    }

private fun bind(statement: SQLiteStatement, index: Int, value: Any?) {
    when (value) {
        null -> statement.bindNull(index)
        is Long -> statement.bindLong(index, value)
        is Double -> statement.bindDouble(index, value)
        is String -> statement.bindText(index, value)
        is ByteArray -> statement.bindBlob(index, value)
        else -> error("Unsupported column value type: ${value::class}")
    }
}

private fun deleteFile(path: String) {
    if (fileManager.fileExistsAtPath(path)) {
        fileManager.removeItemAtPath(path, null)
    }
}

private fun copyFile(from: String, to: String) {
    deleteFile(to)
    check(fileManager.copyItemAtPath(from, to, null)) { "Could not back up $from to $to" }
}

private fun moveFile(from: String, to: String) {
    check(fileManager.moveItemAtPath(from, to, null)) { "Could not move $from to $to" }
}
