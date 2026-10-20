package com.localkeep.credential

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The passkey binding gate is the app's own dialog, not the platform's (V3-06).
 *
 * `shots/082-passkey-bindgate-foss.png`, 2026-09-17: the gate raised by
 * `com.localkeep.credential.CredentialActivity` dumped as `android:id/alertTitle`,
 * `android:id/button1`, `android:id/button2` — ALL-CAPS CANCEL and ALLOW in a horizontal row,
 * square corners, edge to edge. Every other consent in the app is an `LkDialog`: an inset rounded
 * card with `dialog-confirm` and `dialog-dismiss` stacked full-width beneath the message. The one
 * dialog whose whole job is to be recognisably the password manager speaking was the one that did
 * not look like it, and a consent that looks like it came from somewhere else is a consent about
 * trust that is asking to be misread.
 *
 * What is pinned here is the content and the answers, not the pixels: the same four strings, the
 * same two outcomes, and the two design-system tags that say which chrome drew them. The colours
 * and geometry belong to `LkDialog` and are covered in `:core:ui`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeBindingDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val callerPackage = "com.localkeep.passkeytest"
    private val rpId = "example.test"

    private var allowed = 0
    private var denied = 0

    private fun show() {
        compose.setContent {
            LocalKeepTheme {
                NativeBindingDialog(
                    packageName = callerPackage,
                    rpId = rpId,
                    onAllow = { allowed++ },
                    onDeny = { denied++ },
                )
            }
        }
    }

    @Test
    fun `the gate says what it said before`() {
        show()
        compose.onNodeWithText("Allow this app?").assertIsDisplayed()
        compose.onNodeWithText(
            "$callerPackage wants to create a passkey for \"$rpId\". LocalKeep cannot verify this " +
                "offline app-to-site binding.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Allow").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    /**
     * The two design-system tags. Their presence is what distinguishes this dialog from the
     * platform one it replaced — `android:id/button1` carries no test tag and never could.
     */
    @Test
    fun `both answers are the design system's own buttons`() {
        show()
        compose.onNodeWithTag("dialog-confirm").assertIsDisplayed()
        compose.onNodeWithTag("dialog-dismiss").assertIsDisplayed()
    }

    @Test
    fun `allow resolves the gate as allowed`() {
        show()
        compose.onNodeWithTag("dialog-confirm").performClick()
        assertEquals(1, allowed, "ALLOW did not reach onAllow")
        assertEquals(0, denied, "ALLOW also denied")
    }

    @Test
    fun `cancel resolves the gate as denied`() {
        show()
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertEquals(1, denied, "CANCEL did not reach onDeny")
        assertEquals(0, allowed, "CANCEL also allowed")
    }

    /**
     * The other half of the finding, and the half a rendered assertion cannot make: that the
     * platform builder is gone from the module rather than merely unused on this path. Left in
     * place it is an invitation — the next consent added here finds `AlertDialog.Builder` already
     * imported and reaches for it, which is exactly how this one came to exist.
     *
     * Comments are stripped before the check so the explanation above `confirmNativeBinding`, which
     * has to name what it replaced, does not trip it. Same idiom as `:feature:vaultlist`'s
     * `SpentBadgeCopyTest.theFilterSheetMarksNoTypeAsPaid`.
     */
    @Test
    fun `no platform dialog builder is left in this module`() {
        val sources = File("src/main/kotlin/com/localkeep/credential")
            .walkTopDown().filter { it.extension == "kt" }.toList()
            .ifEmpty { fail("no Kotlin sources found under src/main/kotlin/com/localkeep/credential") }
        sources.forEach { source ->
            val code = source.readText().lineSequence()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .joinToString("\n")
            assertTrue(
                "AlertDialog" !in code,
                "${source.name} reaches for a platform AlertDialog again. Every consent in this " +
                    "app is an LkDialog; this module's one consent is the one the user is asked " +
                    "to trust on the app's own appearance.",
            )
        }
    }

    /** The control: prove the walk really reached the activity that raises the gate. */
    @Test
    fun `the sources being read really include the credential activity`() {
        val activity = File("src/main/kotlin/com/localkeep/credential/CredentialActivity.kt")
        assertTrue(activity.isFile, "CredentialActivity.kt not found")
        val text = activity.readText()
        assertTrue("confirmNativeBinding(" in text, "read an activity with no binding gate")
        assertTrue("NativeBindingDialog(" in text, "the binding gate no longer raises this dialog")
    }
}
