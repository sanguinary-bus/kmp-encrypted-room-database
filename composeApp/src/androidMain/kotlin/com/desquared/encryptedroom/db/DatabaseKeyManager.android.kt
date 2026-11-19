package com.desquared.encryptedroom.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.desquared.encryptedroom.DSQApplication
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "encrypted_room_database_key"
private const val PREFERENCES = "encrypted_room_database"
private const val CIPHERTEXT_KEY = "wrapped_passphrase"
private const val IV_KEY = "wrapped_passphrase_iv"
private const val PASSPHRASE_SIZE = 32
private const val GCM_TAG_BITS = 128
private const val TRANSFORMATION = "AES/GCM/NoPadding"

internal actual fun getOrCreateDatabasePassphrase(): ByteArray {
    val preferences = DSQApplication.instance.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val storedCiphertext = preferences.getString(CIPHERTEXT_KEY, null)
    val storedIv = preferences.getString(IV_KEY, null)

    if (storedCiphertext != null && storedIv != null) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystoreKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(storedIv, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(storedCiphertext, Base64.NO_WRAP))
    }

    val passphrase = ByteArray(PASSPHRASE_SIZE).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
    val ciphertext = cipher.doFinal(passphrase)
    preferences.edit()
        .putString(CIPHERTEXT_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        .apply()
    return passphrase
}

private fun keystoreKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
        KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
    )
    return generator.generateKey()
}
