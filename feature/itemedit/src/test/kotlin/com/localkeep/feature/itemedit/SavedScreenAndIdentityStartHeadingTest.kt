package com.localkeep.feature.itemedit

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.localkeep.core.ui.theme.LkSavedScreen
import com.localkeep.core.ui.theme.LocalKeepTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 'Saved' payoff title and the identity-capture intro title are headings, not just large text.
 *
 * Same defect as [com.localkeep.core.ui.theme.LkScreenHeaderHeadingTest]'s `LkScreenHeader`, on two
 * screens that never call it. `LkSavedScreen` (core/ui) is the shared confirmation every save flow
 * (folder, login, generator, note, file, 2FA) lands on; `IdentityStart` (this module) is the intro
 * screen for the identity-capture flow. Neither's title carried `Modifier.semantics { heading() }`,
 * so TalkBack's navigate-by-heading gesture had no stop on either screen — a missing stop on
 * `LkSavedScreen` repeated across every save flow, not a single-screen gap. Fixed 2026-09-13.
 *
 * Robolectric (this module's `src/test`, not `src/androidTest`) because both composables render with
 * no `ItemEditViewModel` — `LkSavedScreen` takes plain strings and `IdentityStart` takes only
 * callbacks — unlike `EditorHeaderInstrumentedTest`, which needs a real `VaultRepository` and is
 * therefore instrumented. `SaveSheetTitleSurvivesRecreationTest` in this same package is the
 * precedent for `createComposeRule()` working here on the JVM.
 *
 * `IdentityStart`'s title is resolved with [stringResource] from *inside* the composed content
 * (captured into a plain closure var, read back after `waitForIdle`) rather than via
 * `ApplicationProvider.getApplicationContext()`: this module's `testImplementation` config (see
 * `build.gradle.kts`) never adds `androidx.test:core`
 * directly, only `androidx.test.ext:junit`, so nothing here should assume that artifact is on the
 * unit-test classpath. `stringResource` needs only the Compose UI test dependency already declared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SavedScreenAndIdentityStartHeadingTest {

    @get:Rule
    val compose = createComposeRule()

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /**
     * `LkSavedScreen`'s bloom/ring and `IdentityStart`'s entrance animate on an infinite transition,
     * so Robolectric's idling strategy never settles ("Compose did not get idle after 60 SECONDS",
     * measured 2026-09-13). Freeze the clock and step it by hand, the
     * way `RecoveryDeferStaysPinnedTest` and `PasswordStepFieldOnScreenTest` do for `LkScreen`'s aura.
     */
    @Before
    fun freezeTheClock() {
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun `the saved screen title is a heading`() {
        compose.setContent {
            LocalKeepTheme {
                LkSavedScreen(
                    title = "Login saved",
                    body = "Your changes are protected.",
                    actionLabel = "Done",
                    onAction = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Login saved").assert(isHeading)
    }

    @Test
    fun `the saved screen body is not a heading`() {
        // The control, matching LkScreenHeaderHeadingTest's: without it, tagging every Text a
        // heading would satisfy the assertion above while making navigate-by-heading stop on
        // everything, which is the same uselessness as stopping on nothing.
        compose.setContent {
            LocalKeepTheme {
                LkSavedScreen(
                    title = "Login saved",
                    body = "Your changes are protected.",
                    actionLabel = "Done",
                    onAction = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Your changes are protected.")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Heading))
    }

    @Test
    fun `the identity start title is a heading`() {
        var resolvedTitle: String? = null
        compose.setContent {
            LocalKeepTheme {
                resolvedTitle = stringResource(R.string.itemedit_identity_start_title)
                IdentityStart(onPhoto = {}, onType = {}, onLeave = { true })
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val title = checkNotNull(resolvedTitle) { "the identity start title string never resolved" }
        compose.onNodeWithText(title).assert(isHeading)
    }

    @Test
    fun `the identity start body is not a heading`() {
        var resolvedBody: String? = null
        compose.setContent {
            LocalKeepTheme {
                resolvedBody = stringResource(R.string.itemedit_identity_start_body)
                IdentityStart(onPhoto = {}, onType = {}, onLeave = { true })
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val body = checkNotNull(resolvedBody) { "the identity start body string never resolved" }
        compose.onNodeWithText(body).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Heading))
    }
}
