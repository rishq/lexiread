package com.lexiread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lexiread.presentation.reader.CloudConsentDialog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2-13: first real Compose UI test — the P1-6 cloud-consent dialog discloses
 * recipients and routes both decisions to the ViewModel callback.
 */
@RunWith(AndroidJUnit4::class)
class CloudConsentDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun consentDialogShowsRecipientsAndAllow() {
        var allowed = false
        composeRule.setContent {
            CloudConsentDialog(
                onAllow = { allowed = true },
                onStayOffline = { allowed = false }
            )
        }

        composeRule.onNodeWithText("Allow cloud lookups?").assertIsDisplayed()
        composeRule.onNodeWithText("api.dictionaryapi.dev", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("api.mymemory.translated.net", substring = true).assertIsDisplayed()

        composeRule.onNodeWithText("Allow").performClick()
        assertTrue(allowed)
    }

    @Test
    fun consentDialogStayOfflineDeclines() {
        var allowed = true
        composeRule.setContent {
            CloudConsentDialog(
                onAllow = { allowed = true },
                onStayOffline = { allowed = false }
            )
        }

        composeRule.onNodeWithText("Stay offline").performClick()
        assertFalse(allowed)
    }
}
