package com.desquared.encryptedroom.db

import androidx.sqlite.SQLiteStatement
import androidx.sqlite.throwSQLiteException
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.utf16
import swiftPMImport.composeApp.*

@OptIn(ExperimentalForeignApi::class)
internal fun CPointer<sc_sqlite3>.getErrorMsg(): String? =
    sc_errmsg16(this)?.toKStringFromUtf16()

/**
 * Port of AndroidX's `NativeSQLiteStatement` that talks to SQLCipher through
 * [SQLCipherBridge][sc_sqlite3]. The contract (closed/busy/range checks) is unchanged.
 */
@OptIn(ExperimentalForeignApi::class)
internal class SQLCipherStatement(
    private val dbPointer: CPointer<sc_sqlite3>,
    private val stmtPointer: CPointer<sc_sqlite3_stmt>,
) : SQLiteStatement {

    @Volatile
    private var isClosed = false

    override fun bindBlob(index: Int, value: ByteArray) {
        throwIfClosed()
        val resultCode = sc_bind_blob(stmtPointer, index, value.toCValues(), value.size)
        if (resultCode != sc_SQLITE_OK()) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun bindLong(index: Int, value: Long) {
        throwIfClosed()
        val resultCode = sc_bind_int64(stmtPointer, index, value)
        if (resultCode != sc_SQLITE_OK()) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun bindDouble(index: Int, value: Double) {
        throwIfClosed()
        val resultCode = sc_bind_double(stmtPointer, index, value)
        if (resultCode != sc_SQLITE_OK()) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun bindText(index: Int, value: String) {
        throwIfClosed()
        // utf16 is NUL terminated, which is what the bridge relies on.
        val valueUtf16 = value.utf16
        val resultCode = sc_bind_text16(stmtPointer, index, valueUtf16)
        if (resultCode != sc_SQLITE_OK()) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun bindNull(index: Int) {
        throwIfClosed()
        val resultCode = sc_bind_null(stmtPointer, index)
        if (resultCode != sc_SQLITE_OK()) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun getText(index: Int): String {
        throwIfClosed()
        throwIfNoRow()
        throwIfInvalidColumn(index)
        val value = sc_column_text16(stmtPointer, index)
        if (value == null) {
            throwIfOutOfMemory()
            throwSQLiteException(sc_SQLITE_NOMEM(), "out of memory")
        }
        return value.toKStringFromUtf16()
    }

    override fun getLong(index: Int): Long {
        throwIfClosed()
        throwIfNoRow()
        throwIfInvalidColumn(index)
        return sc_column_int64(stmtPointer, index)
    }

    override fun getBlob(index: Int): ByteArray {
        throwIfClosed()
        throwIfNoRow()
        throwIfInvalidColumn(index)
        val blob = sc_column_blob(stmtPointer, index)
        if (blob == null) {
            throwIfOutOfMemory()
        }
        val size = sc_column_bytes(stmtPointer, index)
        if (size == 0) {
            throwIfOutOfMemory()
        }
        return if (blob != null && size > 0) {
            blob.reinterpret<ByteVar>().readBytes(size)
        } else {
            ByteArray(0)
        }
    }

    override fun getDouble(index: Int): Double {
        throwIfClosed()
        throwIfNoRow()
        throwIfInvalidColumn(index)
        return sc_column_double(stmtPointer, index)
    }

    override fun isNull(index: Int): Boolean {
        throwIfClosed()
        return getColumnType(index) == sc_SQLITE_NULL()
    }

    override fun getColumnType(index: Int): Int {
        throwIfClosed()
        throwIfNoRow()
        throwIfInvalidColumn(index)
        return sc_column_type(stmtPointer, index)
    }

    override fun getColumnCount(): Int {
        throwIfClosed()
        return sc_column_count(stmtPointer)
    }

    override fun getColumnName(index: Int): String {
        throwIfClosed()
        throwIfInvalidColumn(index)
        val name = sc_column_name16(stmtPointer, index) ?: throw OutOfMemoryError()
        return name.toKStringFromUtf16()
    }

    override fun step(): Boolean {
        throwIfClosed()
        return when (val resultCode = sc_step(stmtPointer)) {
            sc_SQLITE_ROW() -> true
            sc_SQLITE_DONE() -> false
            else -> throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun reset() {
        throwIfClosed()
        val resultCode = sc_reset(stmtPointer)
        if (resultCode != sc_SQLITE_OK()) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun clearBindings() {
        throwIfClosed()
        val resultCode = sc_clear_bindings(stmtPointer)
        if (resultCode != sc_SQLITE_OK()) {
            throwSQLiteException(resultCode, dbPointer.getErrorMsg())
        }
    }

    override fun close() {
        if (!isClosed) {
            sc_finalize(stmtPointer)
        }
        isClosed = true
    }

    private fun throwIfClosed() {
        if (isClosed) {
            throwSQLiteException(sc_SQLITE_MISUSE(), "statement is closed")
        }
    }

    private fun throwIfNoRow() {
        if (sc_stmt_busy(stmtPointer) == 0) {
            throwSQLiteException(sc_SQLITE_MISUSE(), "no row")
        }
    }

    private fun throwIfInvalidColumn(index: Int) {
        if (index < 0 || index >= getColumnCount()) {
            throwSQLiteException(sc_SQLITE_RANGE(), "column index out of range")
        }
    }

    private fun throwIfOutOfMemory() {
        if (sc_errcode(dbPointer) == sc_SQLITE_NOMEM()) {
            throw OutOfMemoryError()
        }
    }
}
