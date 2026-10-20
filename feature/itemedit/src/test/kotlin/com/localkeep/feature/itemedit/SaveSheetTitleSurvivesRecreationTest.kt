package com.localkeep.feature.itemedit

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextReplacement
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.feature.itemedit.ItemEditUiState.ItemType
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A name typed into the naming step must survive an activity recreation.
 *
 * For a **new** item the sheet's field is the only place the title exists: the flat form's title
 * field is gated `if (!state.isNew)`, so nothing pushes these keystrokes into the ViewModel and
 * `state.title` stays blank until Confirm. `showSaveSheet` is deliberately `rememberSaveable`, so a
 * rotation brings the sheet back — and if the field inside it is seeded from a plain `remember`,
 * what comes back is the auto-suggestion, silently overwriting the user's own word. No manifest
 * declares `android:configChanges`, so rotation really does recreate.
 *
 * The state below is a login with a website, which suggests `github.com`
 * ([TitleSuggestionTest.aLoginIsNamedAfterItsSiteAndFallsBackToTheUsername]). That is what makes
 * this discriminate: the failure is not an emptied field, it is a *different name* in it, which is
 * the shape the user actually meets.
 *
 * Robolectric rather than androidTest, for the reason `LkButtonTouchTargetFloorTest` gives: a
 * recreation is state handling, not a pixel, so it belongs where `./gradlew test` runs it on every
 * push rather than only when a device is attached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SaveSheetTitleSurvivesRecreationTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The sheet's one editable node. Matched by `hasSetTextAction` rather than by the
     * `naming-sheet-field` tag, which sits on `LkFormField`'s wrapper — a layout node above the
     * editable one, carrying neither the text nor the action.
     */
    private fun typedTitle(): String =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text

    @Test
    fun aTypedNameIsStillThereAfterTheActivityIsRecreated() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            LocalKeepTheme {
                SaveSheet(
                    state = ItemEditUiState(
                        isNew = true,
                        type = ItemType.LOGIN,
                        uris = listOf("https://github.com/login"),
                    ),
                    onDismiss = {},
                    onSave = {},
                )
            }
        }

        // The control: the sheet really does open on the suggestion, so the assertion below is
        // about the recreation and not about the seeding never having worked.
        assertEquals("github.com", typedTitle(), "the naming step did not open on its suggestion")

        compose.onNode(hasSetTextAction()).performTextReplacement(TYPED)
        assertEquals(TYPED, typedTitle(), "the field did not take the typed name")

        restoration.emulateSavedInstanceStateRestore()

        assertEquals(
            TYPED,
            typedTitle(),
            "the naming step lost the name the user typed across an activity recreation and " +
                "re-seeded itself from the suggestion. For a new item nothing else holds this " +
                "value — the form's title field is gated on !isNew — so the typed name is gone.",
        )
    }

    private companion object {
        const val TYPED = "Work GitHub"
    }
}
