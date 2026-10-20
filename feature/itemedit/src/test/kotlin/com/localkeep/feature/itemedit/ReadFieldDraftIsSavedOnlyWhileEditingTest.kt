package com.localkeep.feature.itemedit

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The detail screen keeps item content out of saved-instance state unless an inline edit is open.
 *
 * O1-04, 2026-10-02: `ReadField` held its `draft` in a plain `rememberSaveable`, registered for
 * every non-blank row it drew — login notes, card notes, username, identity contacts, visible custom
 * fields — whether or not anyone was editing. Backgrounding the app serialised all of them into the
 * Activity's saved state, outside the vault and handed back to a new process before unlock: the
 * thing `AppNavGraph` routes a generated password through `PendingSecret` to avoid. An idle row's
 * draft is only a copy of the value it shows, so there was never anything to save.
 *
 * [ReadFieldDraftSurvivesRecreationTest] is the other half and must stay green: a draft being typed
 * still survives a rotation.
 */
class ReadFieldDraftIsSavedOnlyWhileEditingTest {

    private val scope = SaverScope { true }

    private fun Saver<MutableState<String>, String>.saved(value: String): String? =
        with(this) { scope.save(mutableStateOf(value)) }

    @Test
    fun `an idle row saves nothing`() {
        assertNull(
            readFieldDraftSaver { false }.saved("recovery codes 8213 4470"),
            "a row nobody is editing wrote the value it shows into saved-instance state",
        )
    }

    /** The control — the reason the draft is saveable at all. */
    @Test
    fun `an open edit saves its draft and restores it`() {
        val saver = readFieldDraftSaver { true }
        val saved = saver.saved("half-typed")

        assertEquals("half-typed", saved)
        assertEquals("half-typed", saver.restore(saved!!)?.value)
    }

    /** The decision is taken at save time, so an edit opened after composition is still covered. */
    @Test
    fun `the editing flag is read when the state is saved`() {
        var editing = false
        val saver = readFieldDraftSaver { editing }

        assertNull(saver.saved("shown value"))
        editing = true
        assertEquals("typed value", saver.saved("typed value"))
    }

    @Test
    fun `ReadField's draft uses the editing-gated saver`() {
        val source = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt")
            .also { assertTrue(it.isFile, "ItemDetailScreen.kt not found at ${it.absolutePath}") }
            .readText()
        val line = source.lineSequence().first { it.trimStart().startsWith("var draft by ") }
        assertTrue(
            "readFieldDraftSaver { editing }" in line,
            "ReadField's draft is `${line.trim()}`, which saves every displayed value",
        )
    }
}
