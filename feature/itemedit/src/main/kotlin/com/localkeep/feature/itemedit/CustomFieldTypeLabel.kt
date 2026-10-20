package com.localkeep.feature.itemedit

import androidx.annotation.StringRes
import com.localkeep.core.model.CustomFieldType

/**
 * The chip label for one custom-field type, in the row that lets a field switch between them.
 *
 * Previously `type.name.lowercase().replaceFirstChar(Char::uppercase)` — an enum constant name is
 * not a resource, so every locale but English rendered "Text" / "Hidden" untranslated, and one of
 * those two is the control that decides whether the value is masked and re-auth-gated. BOOLEAN and
 * LINKED are reachable only on a field that already carries one from an imported vault (this
 * editor's own two offered chips are TEXT and HIDDEN — see the comment in `CustomFieldsSection`),
 * but a field like that keeps its own chip in the row, so it needs a label too.
 */
@StringRes
internal fun CustomFieldType.chipLabelRes(): Int = when (this) {
    CustomFieldType.TEXT -> R.string.itemedit_custom_field_type_text
    CustomFieldType.HIDDEN -> R.string.itemedit_custom_field_type_hidden
    CustomFieldType.BOOLEAN -> R.string.itemedit_custom_field_type_boolean
    CustomFieldType.LINKED -> R.string.itemedit_custom_field_type_linked
}
