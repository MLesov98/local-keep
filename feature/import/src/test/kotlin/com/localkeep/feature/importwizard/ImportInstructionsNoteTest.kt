package com.localkeep.feature.importwizard

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The amber panel at the top of the instructions step says something step 1 has not.
 *
 * Step 1 already explains that Android keeps every app's data private, which is why the user is
 * exporting a file by hand. The panel restated that, reworded, over five lines on every source and
 * pushed the actual export steps down the screen. What step 1 does not say is that an export can
 * only happen on a computer, so that variant stays and the sandbox one is gone.
 */
class ImportInstructionsNoteTest {

    @Test
    fun `a source that exports on the phone shows no panel`() {
        for (source in ImportSource.entries.filterNot { it.requiresComputer }) {
            assertFalse(source.showsWhyManualNote(), "$source repeats step 1's sandbox explanation")
        }
    }

    @Test
    fun `a source that can only export on a computer still says so`() {
        // The control: this is news on step 2, and dropping it with the rest would hide it.
        val needsComputer = ImportSource.entries.filter { it.requiresComputer }
        assertTrue(needsComputer.isNotEmpty())
        for (source in needsComputer) {
            assertTrue(source.showsWhyManualNote(), "$source must keep its computer notice")
        }
    }
}
