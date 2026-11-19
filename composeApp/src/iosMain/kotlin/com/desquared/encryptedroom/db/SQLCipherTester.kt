package com.desquared.encryptedroom.db

import androidx.sqlite.SQLiteConnection
import kotlin.native.Platform
import kotlinx.cinterop.toKString
import swiftPMImport.composeApp.sc_libversion

/**
 * Debug-only sanity check that the database on disk is actually encrypted by SQLCipher.
 *
 * The three checks follow Zetetic's encryption testing guide:
 * 1. `PRAGMA cipher_version` is only non-null when SQLCipher is the loaded library,
 * 2. `sqlite_master` is only readable when the key is correct,
 * 3. the file header must not be the plaintext `"SQLite format 3\0"` signature.
 */
object SQLCipherTester {

    fun verifyEncryption(dbPath: String) {
        if (!Platform.isDebugBinary) {
            return
        }

        val report = StringBuilder()
        report.appendLine("--- SQLCipher file verification for: $dbPath ---")
        report.appendLine("bridge_libversion: ${sc_libversion()?.toKString() ?: "unavailable"}")

        var cipherVersion: String? = null
        var schemaAccessible = false
        val connection: SQLiteConnection? = try {
            SQLCipherDriver(getOrCreateDatabasePassphrase()).open(dbPath)
        } catch (failure: Throwable) {
            report.appendLine("open_error: $failure")
            null
        }
        if (connection != null) {
            try {
                cipherVersion = singleText(connection, "PRAGMA cipher_version;")
                schemaAccessible = runCatching {
                    singleText(connection, "SELECT count(*) FROM sqlite_master;")
                }.isSuccess
            } finally {
                connection.close()
            }
        }

        report.appendLine("cipher_version: $cipherVersion")
        report.appendLine("schema_accessible: $schemaAccessible")
        report.appendLine("header_encrypted: ${!hasPlaintextHeader(dbPath)}")
        report.appendLine("--- SQLCipher verification complete ---")
        println(report)
    }

    private fun singleText(connection: SQLiteConnection, sql: String): String? {
        val statement = connection.prepare(sql)
        return try {
            if (statement.step()) statement.getText(0) else null
        } finally {
            statement.close()
        }
    }
}
