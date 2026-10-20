package com.localkeep.feature.itemedit

import com.localkeep.core.model.CustomFieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The custom-field type chip must be labelled from a resource, not the Kotlin enum constant name.
 *
 * Reported: `type.name.lowercase().replaceFirstChar(Char::uppercase)` rendered "Text" / "Hidden" in
 * every locale, because an enum constant name is not a resource — `checkNoHardcodedUiStrings`
 * cannot see it, since it is an expression rather than a literal. Robolectric rather than the
 * Compose test harness, for the reason `CoordinateFormatTest` gives: this is the pure-mapping half
 * of the fix, and `:feature:itemedit`'s Compose harness does not reach idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr")
class CustomFieldTypeLabelTest {

    @Test
    fun `the boolean chip is translated, not the raw enum name`() {
        val resources = RuntimeEnvironment.getApplication().resources
        val label = resources.getString(CustomFieldType.BOOLEAN.chipLabelRes())
        assertEquals("Booléen", label, "French custom-field chip did not resolve to its resource")
        assertNotEquals(
            "Boolean",
            label,
            "chip fell back to the raw enum constant name instead of a translated resource",
        )
    }

    @Test
    fun `every custom field type has its own chip label`() {
        val resIds = CustomFieldType.entries.map { it.chipLabelRes() }
        assertEquals(
            resIds.size,
            resIds.toSet().size,
            "two custom field types share a chip label resource",
        )
    }
}
