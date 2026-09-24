package com.lexiread.core.preferences

import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

object ApiKeyCrypto {
    private const val TAG = "ApiKeyCrypto"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "lexiread_api_keys"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private const val ENCRYPTED_PREFIX = "enc:v1:"

    fun isEncrypted(stored: String): Boolean = stored.startsWith(ENCRYPTED_PREFIX)

    fun encrypt(plain: String): String = encrypt(plain, ::getOrCreateKey)

    internal fun encrypt(plain: String, keyProvider: () -> java.security.Key): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        if (cipher.iv.size != IV_BYTES) {
            throw GeneralSecurityException("Unexpected GCM IV length")
        }
        return ENCRYPTED_PREFIX + Base64.encodeToString(cipher.iv + cipherText, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String = decrypt(stored, ::getOrCreateKey)

    internal fun decrypt(stored: String, keyProvider: () -> java.security.Key): String {
        if (stored.isEmpty()) return ""
        if (stored.startsWith("plain:")) return stored.removePrefix("plain:")
        val encrypted = stored.startsWith(ENCRYPTED_PREFIX)
        return try {
            val raw = Base64.decode(stored.removePrefix(ENCRYPTED_PREFIX), Base64.DEFAULT)
            if (raw.size < IV_BYTES + GCM_TAG_BITS / 8) {
                return if (encrypted) "" else stored
            }
            val iv = raw.copyOfRange(0, IV_BYTES)
            val cipherText = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            if (encrypted) {
                Log.w(TAG, "Unable to decrypt API key")
                ""
            } else {
                stored
            }
        }
    }

    @Synchronized
    private fun getOrCreateKey(): java.security.Key {
        val ks = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null))?.let { return it }
        val keyGen = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        keyGen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGen.generateKey()
    }
}
