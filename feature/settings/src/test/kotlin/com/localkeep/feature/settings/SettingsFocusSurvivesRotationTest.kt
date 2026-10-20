package com.localkeep.feature.settings

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Arriving on Settings from the drawer's "Duress / decoy" row narrows the list to that one row and
 * leaves the reason visible in the search field. Rotating must not undo that.
 *
 * Measured on device 2026-09-21 (emulator-5558, API 36): drawer -> `Duress / decoy` -> rotate, and
 * the search field came back empty with all twenty rows listed. Filed as a race that only bit
 * within ~3 s of arriving; it is not one. At 0.3 s dwell and at 12 s dwell the result was
 * byte-identical, and the same rotation preserved a row expanded by hand -- so `rememberSaveable`
 * was working and nothing was racing.
 *
 * The cause is [SettingsFocus.take] being one-shot and being called from composition. A
 * configuration change rebuilds the composable, the second `take()` returns null, and the search
 * text re-seeds from nothing. The fix is the lifetime, not the storage: the ViewModel takes it
 * once, survives the rotation, and dies with the process.
 *
 * Making it `rememberSaveable` would also have "worked" and is the thing this test exists to
 * refuse -- see [SettingsFocus]'s own reason for not being a navigation argument. Instance state is
 * written to disk, and "the user went looking for the duress vault" is exactly the fact the duress
 * feature exists to keep off this device.
 */
class SettingsFocusSurvivesRotationTest {

    @AfterTest
    fun clearPendingFocus() = SettingsFocus.clear()

    /** The screen with its comments removed, so a rationale is never read as the defect. */
    private val screen: String =
        File("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    private val viewModel: String =
        File("src/main/kotlin/com/localkeep/feature/settings/SettingsViewModel.kt").readText()

    /** The mechanism. A second read returns null, which is why a `remember` cannot hold it. */
    @Test
    fun `the focus is one-shot`() {
        SettingsFocus.request(SettingsFocus.DURESS)
        assertEquals(SettingsFocus.DURESS, SettingsFocus.take())
        assertNull(
            SettingsFocus.take(),
            "take() returned the focus twice, so this test is no longer measuring the mechanism " +
                "that made a rotation lose it",
        )
    }

    @Test
    fun `the screen does not consume the focus from composition`() {
        assertFalse(
            screen.contains("SettingsFocus.take()"),
            "the screen still takes the focus itself. Composition is rebuilt by a configuration " +
                "change and take() is one-shot, so the rotation gets null and the search field " +
                "the drawer filled comes back empty",
        )
        assertTrue(
            viewModel.contains("SettingsFocus.take()"),
            "nothing takes the focus any more, so the drawer's Duress row lands on an unfiltered " +
                "list every time",
        )
    }

    @Test
    fun `the search text is not held in composition either`() {
        assertFalse(
            screen.contains("var query by remember"),
            "the search text is back in composition, so it is emptied by every configuration " +
                "change whether or not the focus survives one",
        )
        assertTrue(
            viewModel.contains("fun onQueryChanged(") && viewModel.contains("val query: StateFlow<String?>"),
            "the ViewModel must own the search text for the screen to read it back after a " +
                "configuration change",
        )
        assertTrue(
            screen.contains("viewModel::onQueryChanged") && screen.contains("viewModel.query"),
            "the screen must write the search text through the ViewModel and read it back from " +
                "there, or the two halves disagree and typing does nothing",
        )
    }

    /**
     * The control on the two above. Surviving a rotation is trivially achievable with
     * `rememberSaveable`, and that writes the row the user went looking for into the Bundle the
     * system persists to disk.
     */
    @Test
    fun `neither the focus nor the search text is made saveable`() {
        val focusRegion = screen.substringAfter("val focusRes", "").substringBefore("val passwordForm by", "")
        assertTrue(
            focusRegion.isNotEmpty() && focusRegion.length < screen.length,
            "the focus slice is unbounded, so this assertion is measuring the whole screen and " +
                "cannot fail; length=${focusRegion.length} of ${screen.length}",
        )
        assertTrue(
            focusRegion.contains("viewModel.query"),
            "the slice no longer contains the search text it is named for, so it is not the " +
                "region this test believes it is reading. Region: $focusRegion",
        )
        assertFalse(
            focusRegion.contains("rememberSaveable"),
            "the focus or the search text is saveable. Neither may be: instance state is written " +
                "to disk, and the row a user went looking for is the fact the duress feature " +
                "exists to keep off this device. Region: $focusRegion",
        )
    }
}
