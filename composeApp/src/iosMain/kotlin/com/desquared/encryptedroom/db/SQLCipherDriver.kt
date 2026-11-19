package com.desquared.encryptedroom.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import androidx.sqlite.throwSQLiteException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import swiftPMImport.composeApp.sc_SQLITE_OK
import swiftPMImport.composeApp.sc_SQLITE_OPEN_CREATE
import swiftPMImport.composeApp.sc_SQLITE_OPEN_READWRITE
import swiftPMImport.composeApp.sc_extended_result_codes
import swiftPMImport.composeApp.sc_initialize
import swiftPMImport.composeApp.sc_key
import swiftPMImport.composeApp.sc_open_v2
import swiftPMImport.composeApp.sc_sqlite3

/**
 * A [SQLiteDriver] that opens SQLCipher-encrypted databases on iOS.
 *
 * SQLCipher is driven through the dlopen-based `SQLCipherBridge` package instead of calling
 * `sqlite3_*` directly, because Firebase's Package.swift transitively links Apple's system
 * sqlite3, which otherwise wins the symbol lookup and silently leaves the database unencrypted.
 *
 * The key has to be handed over right after opening and before any statement is prepared.
 */
@OptIn(ExperimentalForeignApi::class)
internal class SQLCipherDriver(
    private val rawKey: ByteArray,
) : SQLiteDriver {

    init {
        check(sc_initialize() != 0) {
            "SQLCipherBridge could not load SQLCipher.framework, see the SQLCipherBridge log"
        }
    }

    override fun open(fileName: String): SQLiteConnection = memScoped {
        val dbPointer = allocPointerTo<sc_sqlite3>()
        var resultCode = sc_open_v2(
            fileName,
            dbPointer.ptr,
            sc_SQLITE_OPEN_READWRITE() or sc_SQLITE_OPEN_CREATE(),
            null,
        )
        if (resultCode != sc_SQLITE_OK()) {
            throwSQLiteException(resultCode, null)
        }

        val db = dbPointer.value!!

        resultCode = sc_extended_result_codes(db, 1)
        if (resultCode != sc_SQLITE_OK()) {
            throwSQLiteException(resultCode, null)
        }

        // Must be the first operation after opening the connection, per Zetetic's documentation.
        rawKey.usePinned { pinned ->
            resultCode = sc_key(db, pinned.addressOf(0), rawKey.size)
        }
        if (resultCode != sc_SQLITE_OK()) {
            throwSQLiteException(resultCode, null)
        }

        val connection = SQLCipherConnection(db)
        connection.execSQL("PRAGMA journal_mode = WAL;")
        connection
    }
}
