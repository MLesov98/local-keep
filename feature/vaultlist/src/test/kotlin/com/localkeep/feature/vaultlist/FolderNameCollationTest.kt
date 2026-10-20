package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import java.util.Locale
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Folder rows are ordered by the reading locale's collation, not by UTF-16 code point.
 *
 * `sortedBy { it.name.lowercase(Locale.ROOT) }` compares code points, which puts every accented
 * letter after the whole unaccented Latin alphabet: `école` sorted after `zoo`, in an app shipped in
 * French, Spanish, Portuguese and German. `café` was never affected — its leading character is plain
 * ASCII — so the defect only shows when the accent is the leading or differentiating character,
 * which is why the fixture below leads with one.
 *
 * `bulkMoveRows` is the assertion target because it is the one folder-tree builder in this module
 * that is already `internal` for exactly this purpose; the same comparator now orders the folder
 * manager, the browse list, the drawer tree and both editors' pickers.
 */
class FolderNameCollationTest {

    private lateinit var original: Locale

    @BeforeTest
    fun useFrenchLocale() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.FRENCH)
    }

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    private fun folder(name: String, parent: UUID? = null) =
        Folder(id = UUID.randomUUID(), name = name, parentId = parent)

    @Test
    fun ordersAccentedFolderNamesByFrenchCollation() {
        val folders = listOf(folder("zoo"), folder("école"), folder("dossier"))

        assertEquals(
            listOf("dossier", "école", "zoo"),
            bulkMoveRows(folders).map { it.first.name },
        )
    }

    @Test
    fun collationIsStillCaseInsensitive() {
        val folders = listOf(folder("Zoo"), folder("École"), folder("dossier"))

        assertEquals(
            listOf("dossier", "École", "Zoo"),
            bulkMoveRows(folders).map { it.first.name },
        )
    }
}
