package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A new item and an existing one are the same form.
 *
 * `shots/053-login-editor.png`, 2026-09-15: a new Login opened on USERNAME, PASSWORD, WEBSITES,
 * NOTES and "Folder, tags and more" — no title field anywhere. The *same screen*, reopened on the
 * saved item, led with `TITLE (REQUIRED)`. Two forms for one editor, and the difference was
 * invisible until the user went looking for the field that was there last time (F-15).
 *
 * The naming step ([SaveSheet]) is not removed: its argument — a new item can be named at the end,
 * once the thing being named exists — is still right for a user who does not type a title. It is
 * the fallback now rather than the only way in.
 *
 * FAILS WITHOUT THE FIX: the title block was wrapped in `if (!state.isNew)`, and `onSave` opened
 * the naming sheet for every new item whatever the form held.
 */
class CreateAndEditAreOneFormTest {

    private val screen = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()

    /**
     * The form between the type's own fields and "Folder, tags and more" — where the title field
     * has lived since 2026-10-05, when it moved below what it names.
     */
    private val titleBlock = screen
        .substringAfter("LkSectionSwap(state.type)", "")
        .substringBefore("MoreSection(\n")

    @Test
    fun `the title field is not gated on the item already existing`() {
        assertTrue(
            "R.string.itemedit_edit_title_field_label" in titleBlock,
            "the form lost its title field",
        )
        assertFalse(
            "if (!state.isNew) {" in titleBlock,
            "the title field is drawn for existing items only — create and edit are two forms again",
        )
    }

    /**
     * And the naming sheet stops repeating a field the user can see behind it. A modal that asks
     * again for something already typed into the form teaches that the form's own inputs do not
     * count.
     */
    @Test
    fun `the naming step opens only when the form has no title`() {
        val onSave = screen.substringAfter("val onSave = {", "").substringBefore("\n    }")
        assertTrue(
            "if (state.isNew && state.title.isBlank()) {" in onSave,
            "Save on a new item still routes through the naming sheet regardless of the title",
        )
        assertTrue("viewModel.save()" in onSave, "the direct save path is gone")
    }

    /**
     * The backstop is untouched. `save()` refuses a blank title on its own, and that guard is what
     * keeps an unfindable "Untitled" row out of the vault whatever the UI does — a rule that lives
     * only in a button's enabled state is one navigation change from being gone.
     */
    @Test
    fun `save still refuses a blank title on its own`() {
        val viewModel = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt").readText()
        val save = viewModel.substringAfter("\n    fun save() {", "").substringBefore("\n    fun ")
        assertTrue("titleError" in save, "the blank-title refusal left save()")
    }
}
