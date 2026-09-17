package com.lexiread

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
  @Test
  fun apiKeysUseAndroidKeystoreEncryption() {
    val plain = "instrumented-test-key"
    val encrypted = com.lexiread.core.preferences.ApiKeyCrypto.encrypt(plain)
    val second = com.lexiread.core.preferences.ApiKeyCrypto.encrypt(plain)
    assertTrue(encrypted.startsWith("enc:v1:"))
    assertFalse(encrypted.contains(plain))
    assertNotEquals(encrypted, second)
    assertEquals(plain, com.lexiread.core.preferences.ApiKeyCrypto.decrypt(encrypted))
  }

  @Test
  fun useAppContext() {
    // Context of the app under test.
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("com.lexiread.app", appContext.packageName)
  }
}
