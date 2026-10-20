package com.localkeep.autofill

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.ui.theme.LocalKeepTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-47: `SaveConfirmActivity` and its unlock-to-save step were the only screens in the app not
 * built on the design system (default AppCompat type, flat grey buttons, no side gutter). Both are
 * rebuilt in Compose (`SaveConfirmContent`/`SaveConfirmUnlockContent` in `SaveConfirmScreen.kt`);
 * this pins that the new Compose content still hosts the same strings the old `TextView`/`Button`
 * tree did, and that `saving`/`pending` still disable the actions the way the class's own KDoc on
 * `SaveConfirmActivity.saving` has always promised.
 *
 * `autoAdvance = false` — see `PasswordStepFieldOnScreenTest` in `:feature:onboarding` for why:
 * `LkActionScaffold` draws the same slow-drift aura `LkScreen` does, which never goes idle on its
 * own and would hang `onNodeWithText`'s implicit synchronisation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h823dp-xxhdpi")
class SaveConfirmScreenContentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the confirm screen hosts the title, locked notice and both action labels`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                SaveConfirmContent(
                    title = "Save the password for g18user on LocalKeep Autofill Test?",
                    lockedNotice = "LocalKeep is locked — you'll enter your master password next.",
                    summary = summaryFor(loginEntry()),
                    saveLabel = "Save",
                    discardLabel = "Discard",
                    saving = false,
                    onSave = {},
                    onDiscard = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Save the password for g18user on LocalKeep Autofill Test?")
            .assertIsDisplayed()
        compose.onNodeWithText("LocalKeep is locked — you'll enter your master password next.")
            .assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Discard").assertIsDisplayed()
    }

    @Test
    fun `saving disables both actions, the discard listener no longer just no-ops`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                SaveConfirmContent(
                    title = "Save a new login for g18user on LocalKeep Autofill Test?",
                    lockedNotice = null,
                    summary = summaryFor(loginEntry()),
                    saveLabel = "Save",
                    discardLabel = "Discard",
                    saving = true,
                    onSave = {},
                    onDiscard = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Discard").assertIsNotEnabled()
    }

    @Test
    fun `the unlock step hosts its title, subtitle, continue label and error`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                SaveConfirmUnlockContent(
                    title = "Unlock LocalKeep",
                    subtitle = "Your vault is locked. Unlock it to store this login.",
                    passwordLabel = "Master password",
                    continueLabel = "Continue",
                    unlockingLabel = "Unlocking…",
                    errorMessage = "Wrong password",
                    pending = false,
                    onSubmit = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Unlock LocalKeep").assertIsDisplayed()
        compose.onNodeWithText("Your vault is locked. Unlock it to store this login.").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsDisplayed()
        compose.onNodeWithText("Wrong password").assertIsDisplayed()
    }

    @Test
    fun `pending shows the unlocking label instead of continue`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                SaveConfirmUnlockContent(
                    title = "Unlock LocalKeep",
                    subtitle = "Your vault is locked. Unlock it to store this login.",
                    passwordLabel = "Master password",
                    continueLabel = "Continue",
                    unlockingLabel = "Unlocking…",
                    errorMessage = "",
                    pending = true,
                    onSubmit = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Unlocking…").assertIsDisplayed()
    }

    /**
     * F-dev2-2: the screen used to be a title, two buttons and ~460dp of empty black between them —
     * the last gate before a credential is written to the vault said nothing about what was about
     * to be written. The card names the caller and the username.
     *
     * The password assertion is the half that matters, and it is why the summary is built by
     * [saveConfirmSummary] from a real entry rather than hand-assembled here: the production
     * builder is what decides what reaches a composable, and it must never hand it the secret. The
     * masked row is drawn from a label alone.
     */
    @Test
    fun `the summary card names the caller and username, and the password never reaches the screen`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                SaveConfirmContent(
                    title = "Save a new login for g18user on LocalKeep Autofill Test?",
                    lockedNotice = null,
                    summary = summaryFor(loginEntry()),
                    saveLabel = "Save",
                    discardLabel = "Discard",
                    saving = false,
                    onSave = {},
                    onDiscard = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("App or site", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("LocalKeep Autofill Test").assertIsDisplayed()
        compose.onNodeWithText("Username", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("g18user").assertIsDisplayed()
        // Masked, and announced as masked rather than as a run of bullets.
        compose.onNodeWithContentDescription("Password, hidden").assertIsDisplayed()
        // Substring, so a partial leak fails too.
        compose.onAllNodesWithText(PLAINTEXT, substring = true).assertCountEquals(0)
    }

    /**
     * The queued note is the one line that distinguishes an offer held across a lock from an
     * ordinary one, so it has to be absent as reliably as it is present — a note shown on every
     * save would say nothing at all.
     */
    @Test
    fun `the queued note is present on a queued offer and absent on an ordinary one`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                SaveConfirmContent(
                    title = "Save the login for g18user you captured on LocalKeep Autofill Test?",
                    lockedNotice = null,
                    summary = summaryFor(loginEntry(awaitingUnlock = true)),
                    saveLabel = "Save",
                    discardLabel = "Discard",
                    saving = false,
                    onSave = {},
                    onDiscard = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Captured while LocalKeep was locked").assertIsDisplayed()
    }

    @Test
    fun `the ordinary offer carries no queued note`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                SaveConfirmContent(
                    title = "Save a new login for g18user on LocalKeep Autofill Test?",
                    lockedNotice = null,
                    summary = summaryFor(loginEntry()),
                    saveLabel = "Save",
                    discardLabel = "Discard",
                    saving = false,
                    onSave = {},
                    onDiscard = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)

        compose.onAllNodesWithText("Captured while LocalKeep was locked").assertCountEquals(0)
    }

    /**
     * A captured card names itself by its last four digits and gets no password row: the security
     * code is a secret this screen has no reason to acknowledge, and the number is already the row
     * above it, masked.
     */
    @Test
    fun `a captured card shows its last four digits and no password row`() {
        val summary = summaryFor(
            PendingSaveStore.Entry(
                username = "",
                password = CharArray(0),
                webDomain = "shop.example",
                packageName = null,
                existingItemId = null,
                card = PendingSaveStore.Card(
                    number = "4111111111117823".toCharArray(),
                    securityCode = "321".toCharArray(),
                    holder = "A Person",
                    expiry = "07/29",
                ),
            ),
        )

        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                SaveConfirmContent(
                    title = "Save the card ending 7823 to LocalKeep?",
                    lockedNotice = null,
                    summary = summary,
                    saveLabel = "Save",
                    discardLabel = "Discard",
                    saving = false,
                    onSave = {},
                    onDiscard = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText("Card", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("•••• 7823").assertIsDisplayed()
        compose.onAllNodesWithText("4111", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("321", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Password", substring = true, ignoreCase = true)
            .assertCountEquals(0)
    }

    private fun loginEntry(awaitingUnlock: Boolean = false) = PendingSaveStore.Entry(
        username = "g18user",
        password = PLAINTEXT.toCharArray(),
        webDomain = null,
        packageName = "com.localkeep.autofilltest",
        existingItemId = null,
        awaitingUnlock = awaitingUnlock,
    )

    /** Real resources, so the test fails if one of the summary keys is missing or renamed. */
    private fun summaryFor(entry: PendingSaveStore.Entry): SaveConfirmSummary {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return saveConfirmSummary(entry, "LocalKeep Autofill Test") { context.getString(it) }
    }

    private companion object {
        /** Distinctive enough that a substring search for it cannot match anything incidental. */
        const val PLAINTEXT = "qx7-marmoset-trellis"
    }
}
