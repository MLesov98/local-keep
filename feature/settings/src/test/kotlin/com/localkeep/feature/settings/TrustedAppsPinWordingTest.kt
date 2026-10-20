package com.localkeep.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.vault.TrustedApp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A signature pin is not trust the user gave, and the screen must not call it that.
 *
 * Autofill pins an app's signing certificate the moment that app asks to be filled with a saved
 * login on offer, before the user has picked anything — deliberately, so the signer is fixed before
 * the first credential can reach it. Such an app then appeared here as "this app is trusted", and
 * Forget looked broken because the next tap in that app put the row straight back. A site row is
 * different: it exists only because the user tapped Allow for that browser and site.
 *
 * Resolved through a real (shadowed) Context so the assertion is on the words the card renders,
 * not on a key name.
 */
@RunWith(RobolectricTestRunner::class)
class TrustedAppsPinWordingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val pinnedOnly = TrustedApp(
        packageName = "com.example.bank",
        label = "Example Bank",
        domains = emptyList(),
    )

    private val allowedSites = TrustedApp(
        packageName = "com.android.chrome",
        label = "Chrome",
        domains = listOf("example.com"),
    )

    @Test
    fun `a pinned app nothing was allowed for reads as a remembered signature, not as trusted`() {
        val caption = assertNotNull(trustedAppCaptionRes(pinnedOnly), "a pin-only card shows no line")
        val text = context.getString(caption)

        assertTrue(
            text.contains("Signature remembered"),
            "a pin-only card must say the signature is remembered; it says: $text",
        )
        assertFalse(
            text.contains("trusted", ignoreCase = true),
            "a pin-only card calls the app trusted although the user never chose anything: $text",
        )
    }

    /** Control: a card the user allowed sites for lists them under the Trusted apps heading. */
    @Test
    fun `an app with allowed sites lists them under the trusted heading, without the pin line`() {
        assertNull(
            trustedAppCaptionRes(allowedSites),
            "a card the user allowed sites for must list them, not carry the pin-only line",
        )
        assertTrue(
            context.getString(R.string.settings_trusted_title).contains("Trusted"),
            "the heading allowed sites sit under no longer reads as trust",
        )
    }

    @Test
    fun `forgetting says the signature will be remembered again on the next fill request`() {
        val body = context.getString(R.string.trusted_apps_forget_body)

        assertTrue(
            body.contains("remembered again the next time this app asks LocalKeep to fill"),
            "the forget confirmation hides that the next fill request pins the app again: $body",
        )
    }
}
