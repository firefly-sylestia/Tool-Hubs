package com.sylestia.june.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Protects the SQLCipher database passphrase with a non-exportable Android Keystore key.
 * Only the encrypted passphrase blob is stored in app-private preferences.
 */
object JournalKeyStore {
    private const val ALIAS = "june_local_database_key_v1"
    private const val PREFS = "june_secure_storage"
    private const val KEY_BLOB = "encrypted_database_passphrase"

    fun getDatabasePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_BLOB, null)
        if (stored != null) return decrypt(Base64.decode(stored, Base64.NO_WRAP))

        val passphrase = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val encrypted = encrypt(passphrase)
        check(prefs.edit().putString(KEY_BLOB, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "Could not persist the encrypted JUNE database key."
        }
        return passphrase
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + encrypted
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "Invalid JUNE key envelope." }
        val ivSize = payload[0].toInt() and 0xff
        require(ivSize in 12..16 && payload.size > ivSize + 1) { "Invalid JUNE key envelope." }
        val iv = payload.copyOfRange(1, ivSize + 1)
        val encrypted = payload.copyOfRange(ivSize + 1, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }
}
