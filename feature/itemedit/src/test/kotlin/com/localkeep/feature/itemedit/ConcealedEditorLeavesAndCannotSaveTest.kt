package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An edit form whose hidden item is concealed underneath it leaves, and cannot write anything.
 *
 * O1-02, 2026-10-02: reveal a hidden item, open it, tap Edit, and still be in the form when the
 * 120 s reveal session ends. The ViewModel replaced the state with a blank default Login — but the
 * edit screen, unlike the detail screen and the file editor, never read `concealed`, so it stayed
 * up with Save enabled and `originalItem` still set. Retype a title and Save committed an empty
 * Login over the real hidden item: username, websites, notes, TOTP, custom fields and tags gone,
 * and a hidden Card, Note or Identity turned into a Login.
 *
 * Read from source because both halves live where a JVM cannot reach: the screen needs the
 * ViewModel, and the ViewModel needs AndroidKeyStore (see `ItemEditViewModelTest`). The instrumented
 * `aSaveAfterTheRevealEndsWritesNothing` drives the save end to end.
 */
class ConcealedEditorLeavesAndCannotSaveTest {

    @Test
    fun `the edit screen leaves when its item is concealed`() {
        val body = block(SCREEN, "fun ItemEditScreen(", "\n}\n")
        val effect = body.lineSequence().firstOrNull { "LaunchedEffect(state.concealed)" in it }
        assertTrue(
            effect != null && "onBack()" in effect,
            "ItemEditScreen has no `LaunchedEffect(state.concealed) { if (state.concealed) onBack() }`. " +
                "When a reveal session ends the form is blanked in place and stays on screen with " +
                "Save enabled, one retyped title from writing an empty Login over the hidden item.",
        )
    }

    @Test
    fun `a concealed form is refused before anything is written`() {
        val gate = block(VIEW_MODEL, "private fun blockedBeforeTitle(", "\n    }\n")
        assertTrue(
            "if (snapshot.concealed) return true" in gate,
            "blockedBeforeTitle does not refuse a concealed snapshot, so save() builds content from " +
                "the blank default state and writes it over the stored item:\n$gate",
        )
    }

    @Test
    fun `concealment drops the loaded record that save writes through`() {
        val collector = block(VIEW_MODEL, "repository.presentationState.collect", "concealed = true)")
        assertTrue(
            "originalItem = null" in collector,
            "the concealment collector leaves `originalItem` set; save() copies it with the blank " +
                "state's content, and it is a decrypted record the screen is meant to have let go of",
        )
    }

    /** The control: the detail screen's identical exit, true before and after this fix. */
    @Test
    fun `the detail screen already leaves on concealment`() {
        val detail = source(DETAIL)
        assertTrue("LaunchedEffect(state.concealed) { if (state.concealed) onBack() }" in detail)
    }

    private companion object {
        const val SCREEN = "src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt"
        const val DETAIL = "src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt"
        const val VIEW_MODEL = "src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt"

        /** Asserts its own anchor: `substringAfter` on a missing one returns the whole file. */
        fun block(path: String, start: String, end: String): String {
            val text = source(path)
            assertTrue(start in text, "anchor '$start' is gone from $path — update the anchor")
            val after = text.substringAfter(start)
            assertTrue(end in after, "end anchor not found after '$start' in $path")
            return after.substringBefore(end)
        }

        fun source(relative: String): String {
            val file = File(relative)
            assertTrue(file.isFile, "expected source file at ${file.absolutePath}")
            return file.readText()
        }
    }
}
