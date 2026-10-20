package com.localkeep.feature.importwizard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The import wizard's state must never be handed to `SavedStateHandle`.
 *
 * This is a guard against a fix, not against a bug. "The wizard has no `SavedStateHandle`, so
 * process death loses your place" has been filed twice as a defect, and both readings took the
 * absence for an oversight. Injecting one would write the source export's master password and every
 * parsed row — usernames, passwords, TOTP secrets, notes, in plaintext, because that is what the
 * preview renders — into system-managed saved state on disk, outside the vault and outside its
 * encryption.
 *
 * The cost of *not* doing it is one restart of a flow the user is standing in front of, with their
 * export file still on the device. So the test pins the absence, and the KDoc beside the class says
 * why, so the third filing is answered before it is opened.
 *
 * Deliberately narrow: it asserts nothing about how the wizard holds state, only that it does not
 * hold it somewhere durable.
 */
class WizardStateNeverReachesDiskTest {

    private val text: String
        get() {
            val relative = "src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardViewModel.kt"
            val direct = File(relative)
            val file = if (direct.isFile) direct else File(repoRoot(), "feature/import/$relative")
            if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
            return file.readText()
        }

    private fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("repo root not found above ${File("").absolutePath}")

    @Test
    fun `the wizard viewmodel takes no SavedStateHandle`() {
        // Matched on the injection, not on the word: the KDoc beside the class necessarily names
        // the type several times to explain why it is absent, so a bare substring check fails on
        // its own rationale.
        val body = text.substringAfter("class ImportWizardViewModel @Inject constructor(")
        assertTrue(
            "androidx.lifecycle.SavedStateHandle" !in text && "SavedStateHandle" !in body.take(600),
            "ImportUiState carries the export's master password and every parsed credential in " +
                "plaintext; SavedStateHandle persists to disk outside the vault. Losing the " +
                "wizard's place on process death is the cheaper failure, and the class KDoc says so",
        )
    }

    @Test
    fun `the reason is written down where the next person will look`() {
        // The control, and it must stay green while the test above goes red. A bare assertion with
        // no explanation beside the code is how this got filed twice: the absence looks like
        // something nobody got round to. Deleting the rationale would leave the guard passing while
        // the thing it guards became inexplicable again.
        assertTrue(
            "deliberately has no `SavedStateHandle`" in text &&
                "outside the vault and outside its encryption" in text,
            "the class KDoc must state why the handle is absent, or the next reader files it as a " +
                "defect for the third time",
        )
    }
}
