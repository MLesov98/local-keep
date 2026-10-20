package com.localkeep.autofill

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The browser-domain trust gate is the app's own dialog, not a platform `LinearLayout` (2C-F12).
 *
 * `shots/5554-2C.5k-confirm-dialog.png`, 2026-09-20: the gate raised by `ConfirmDomainActivity`
 * dumped as plain `TextView`/`Button` nodes, every one of them `bounds="[0,…][1080,…]"` — text
 * running into both screen edges, ALLOW and DENY full-bleed grey, no card, no icon, no accent. The
 * 24dp of padding the source set was measured onto a view whose children filled the window anyway.
 * Every other consent in the app is an `LkDialog`, including the passkey binding gate two screens
 * away, which was rebuilt for this exact reason in wave 3 (`NativeBindingDialogTest`). This is the
 * app's most phishing-sensitive prompt: a consent about who may speak for a site, drawn so that it
 * could have come from anywhere.
 *
 * What is pinned is the content and the two answers, not the pixels — the same four strings, the
 * same outcomes, and the design-system tags that say which chrome drew them. `LkDialog`'s colours
 * and geometry are covered in `:core:ui`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfirmDomainDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val callerPackage = "com.android.chrome"
    private val webDomain = "example.test"

    private var allowed = 0
    private var denied = 0

    private fun show(working: Boolean = false) {
        compose.setContent {
            LocalKeepTheme {
                ConfirmDomainDialog(
                    packageName = callerPackage,
                    webDomain = webDomain,
                    working = working,
                    onAllow = { allowed++ },
                    onDeny = { denied++ },
                )
            }
        }
    }

    @Test
    fun `the gate names both halves of the pairing and says the decision is kept`() {
        show()
        compose.onNodeWithText("Allow $callerPackage to fill saved logins for $webDomain?")
            .assertIsDisplayed()
        compose.onNodeWithText(
            "Only allow this if you recognize both the app and site. LocalKeep remembers this " +
                "pairing and won't ask again.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Allow").assertIsDisplayed()
        compose.onNodeWithText("Deny").assertIsDisplayed()
    }

    /**
     * The two design-system tags. Their presence is what distinguishes this from the platform
     * widgets it replaced — a raw `Button` carries no test tag and never could.
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
        assertEquals(1, allowed, "Allow did not reach onAllow")
        assertEquals(0, denied, "Allow also denied")
    }

    @Test
    fun `deny resolves the gate as denied`() {
        show()
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertEquals(1, denied, "Deny did not reach onDeny")
        assertEquals(0, allowed, "Deny also allowed")
    }

    /**
     * The old Allow button disabled itself on its first tap, because everything behind it runs in a
     * coroutine and a second tap would start the pinning over.
     */
    @Test
    fun `allow is dimmed while the decision is being applied`() {
        show(working = true)
        compose.onNodeWithTag("dialog-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("dialog-dismiss").assertIsEnabled()
    }

    /**
     * The half a rendered assertion cannot make: that the platform widgets are gone from the gate
     * rather than merely unused on this path. Left imported they are an invitation — the next
     * prompt added here finds `LinearLayout` already in scope and reaches for it, which is how this
     * one came to exist. Scoped to this one file, because `AuthActivity`'s password prompt is still
     * a platform view and is not this finding.
     *
     * Comments are stripped first, so the explanation above [ConfirmDomainDialog], which has to
     * name what it replaced, does not trip it. Same idiom as `NativeBindingDialogTest`'s own
     * source check in `:credential`.
     */
    @Test
    fun `no platform widgets are left in the gate`() {
        val source = File("src/main/kotlin/com/localkeep/autofill/ConfirmDomainActivity.kt")
        if (!source.exists()) fail("ConfirmDomainActivity.kt was not found at ${source.absolutePath}")
        val code = source.readText().lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        for (widget in listOf("LinearLayout", "TextView", "android.widget.Button")) {
            assertTrue(
                widget !in code,
                "$widget is still built by ConfirmDomainActivity — the gate is meant to be an " +
                    "LkDialog now, and a platform widget in scope is how the next one goes back.",
            )
        }
    }
}
