package com.desquared.encryptedroom.db

/**
 * Returns the 32-byte passphrase used to encrypt the database of this installation.
 *
 * The passphrase is created on first use and persisted only in encrypted form: wrapped by an
 * Android Keystore key on Android, and stored in the Keychain on iOS.
 */
internal expect fun getOrCreateDatabasePassphrase(): ByteArray
