package com.lexiread.core.preferences

import android.util.Base64
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApiKeyCryptoTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `encrypt-decrypt round trip uses encrypted envelope`() {
        val raw = "sk-test-12345"
        val stored = ApiKeyCrypto.encrypt(raw) { key }
        assertTrue(stored.startsWith("enc:v1:"))
        assertFalse(stored.contains(raw))
        assertEquals(raw, ApiKeyCrypto.decrypt(stored) { key })
    }

    @Test
    fun `encryption generates a fresh IV each time`() {
        val first = ApiKeyCrypto.encrypt("test-key") { key }
        val second = ApiKeyCrypto.encrypt("test-key") { key }
        val firstIv = Base64.decode(first.removePrefix("enc:v1:"), Base64.DEFAULT).take(12)
        val secondIv = Base64.decode(second.removePrefix("enc:v1:"), Base64.DEFAULT).take(12)
        assertNotEquals(firstIv, secondIv)
        assertEquals("test-key", ApiKeyCrypto.decrypt(second) { key })
    }

    @Test
    fun `keystore failure never returns plaintext`() {
        assertThrows(GeneralSecurityException::class.java) {
            ApiKeyCrypto.encrypt("secret") { throw GeneralSecurityException("Unavailable") }
        }
    }

    @Test
    fun `corrupt ciphertext and unavailable keys fail closed`() {
        val stored = ApiKeyCrypto.encrypt("test-key") { key }
        assertEquals("", ApiKeyCrypto.decrypt(stored) { throw GeneralSecurityException() })
        assertEquals("", ApiKeyCrypto.decrypt("enc:v1:invalid") { key })
        val bytes = Base64.decode(stored.removePrefix("enc:v1:"), Base64.DEFAULT)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val corrupted = "enc:v1:" + Base64.encodeToString(bytes, Base64.NO_WRAP)
        assertEquals("", ApiKeyCrypto.decrypt(corrupted) { key })
    }

    @Test
    fun `empty stays empty without accessing keystore`() {
        assertEquals("", ApiKeyCrypto.encrypt("") { error("Must not access keystore") })
        assertEquals("", ApiKeyCrypto.decrypt(""))
    }

    @Test
    fun `legacy values remain readable`() {
        assertEquals("legacy-key", ApiKeyCrypto.decrypt("legacy-key"))
        assertEquals("test", ApiKeyCrypto.decrypt("test"))
        assertEquals("legacy-key", ApiKeyCrypto.decrypt("plain:legacy-key"))
        val oldEnvelope = ApiKeyCrypto.encrypt("legacy-key") { key }.removePrefix("enc:v1:")
        assertEquals("legacy-key", ApiKeyCrypto.decrypt(oldEnvelope) { key })
    }
}
