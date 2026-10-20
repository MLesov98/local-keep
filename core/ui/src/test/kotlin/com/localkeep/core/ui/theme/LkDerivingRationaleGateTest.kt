package com.localkeep.core.ui.theme

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `LkDerivingOverlay` used to show the Argon2id "this wait is the point" rationale
 * (`core_ui_deriving_rationale`, Tier G security copy) unconditionally, on every path that raises
 * this overlay — including `UnlockScreen`'s biometric and Recovery Kit unlocks, which run no KDF
 * at all. `showRationale` gates that Text so a caller whose wait is not Argon2id can say so. The
 * default (`true`) preserves every caller that existed before this fix — onboarding's vault
 * creation and the password unlock path, both real Argon2id.
 */
@RunWith(RobolectricTestRunner::class)
class LkDerivingRationaleGateTest {

    @get:Rule
    val compose = createComposeRule()

    /** A substring stable against translation drift in the base (English) string. */
    private val rationaleSnippet = "this wait is the point"

    @Test
    fun rationaleShowsByDefault() {
        compose.setContent {
            LocalKeepTheme { LkDerivingOverlay() }
        }
        compose.onNodeWithText(rationaleSnippet, substring = true, ignoreCase = true).assertExists()
    }

    @Test
    fun rationaleIsHiddenWhenNotDrivenByARealDerivation() {
        compose.setContent {
            LocalKeepTheme { LkDerivingOverlay(showRationale = false) }
        }
        val matches = compose
            .onAllNodesWithText(rationaleSnippet, substring = true, ignoreCase = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertEquals(0, matches.size, "the Argon2id rationale rendered with showRationale = false")
    }
}
