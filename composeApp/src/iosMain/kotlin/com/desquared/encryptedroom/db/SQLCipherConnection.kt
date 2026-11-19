package com.desquared.encryptedroom.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.throwSQLiteException
import kotlin.concurrent.Volatile
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.utf16
import kotlinx.cinterop.value
import swiftPMImport.composeApp.sc_SQLITE_MISUSE
import swiftPMImport.composeApp.sc_SQLITE_OK
import swiftPMImport.composeApp.sc_close_v2
import swiftPMImport.composeApp.sc_prepare16_v2
import swiftPMImport.composeApp.sc_sqlite3
import swiftPMImport.composeApp.sc_sqlite3_stmt

/**
 * Port of AndroidX's `NativeSQLiteConnection` that talks to SQLCipher through [SQLCipherBridge][sc_sqlite3].
 */
@OptIn(ExperimentalForeignApi::class)
internal class SQLCipherConnection(
    private val dbPointer: CPointer<sc_sqlite3>,
) : SQLiteConnection {

    @Volatile
    private var isClosed = false

    override fun prepare(sql: String): SQLiteStatement = memScoped {
        if (isClosed) {
            throwSQLiteException(sc_SQLITE_MISUSE(), "connection is closed")
        }
        val stmtPointer = allocPointerTo<sc_sqlite3_stmt>()
        // Kotlin/Native stores strings as UTF-16, which is exactly what prepare16_v2 expects.
        val sqlUtf16 = sql.utf16
        val resultCode = sc_prepare16_v2(dbPointer, sqlUtf16, stmtPointer.ptr)
        if (resultCode != sc_SQLITE_OK()) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
        SQLCipherStatement(dbPointer, stmtPointer.value!!)
    }

    override fun close() {
        if (!isClosed) {
            sc_close_v2(dbPointer)
        }
        isClosed = true
    }
}
