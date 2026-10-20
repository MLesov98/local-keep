package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper

/**
 * The KeePass group tree, which this importer used to flatten.
 *
 * KDBX is the only source format with a real hierarchy, and dropping it meant every entry arrived at
 * the top level — often discarding years of a user's organisation, silently, in an import that
 * reported full success. Built with the same backend the app ships, so the writer cannot drift away
 * from the reader under test.
 */
class KdbxGroupHierarchyTest {

    private val password = "correct horse battery staple"

    private fun parse(build: DomDatabaseWrapper.() -> Unit): Pair<List<Folder>, Map<String, java.util.UUID?>> {
        val db = DomDatabaseWrapper().apply(build)
        val bytes = ByteArrayOutputStream().use { out ->
            db.save(KdbxCreds(password.toByteArray(Charsets.UTF_8)), out)
            out.toByteArray()
        }
        val result = KdbxImporter.parse(bytes, password.toCharArray())
        assertTrue(result is Outcome.Success, "import must succeed")
        val parsed = result.value
        return parsed.folders to parsed.items.associate { it.title to it.folderId }
    }

    private fun DomDatabaseWrapper.entry(title: String) = newEntry().apply {
        this.title = title
        username = "u"
        setPassword("p")
    }

    @Test
    fun `a nested group tree becomes a nested folder tree`() {
        val (folders, byTitle) = parse {
            val work = newGroup("Work")
            val bank = newGroup("Bank")
            bank.addEntry(entry("statement"))
            work.addGroup(bank)
            rootGroup.addGroup(work)
        }

        assertEquals(listOf("Work", "Bank"), folders.map { it.name })
        val work = folders.single { it.name == "Work" }
        val bank = folders.single { it.name == "Bank" }
        assertNull(work.parentId, "a root group's folder sits at the top level")
        assertEquals(work.id, bank.parentId)
        assertEquals(bank.id, byTitle["statement"])
        assertTrue(FolderTree.validate(folders).isValid)
    }

    @Test
    fun `entries in the root group are filed nowhere`() {
        // The KeePass root group is the database itself, not a folder. Naming it would put every
        // imported entry inside a folder called after the file.
        val (folders, byTitle) = parse { rootGroup.addEntry(entry("loose")) }

        assertTrue(folders.isEmpty())
        assertNull(byTitle["loose"])
    }

    @Test
    fun `groups deeper than the limit are clamped, and their entries stay reachable`() {
        val depth = FolderTree.MAX_DEPTH + 3
        val (folders, byTitle) = parse {
            var current = rootGroup
            repeat(depth) { level ->
                val group = newGroup("g$level")
                current.addGroup(group)
                current = group
            }
            current.addEntry(entry("deep"))
        }

        assertEquals(FolderTree.MAX_DEPTH, folders.size, "only the levels that fit become folders")
        assertTrue(FolderTree.validate(folders).isValid)
        // The entry joins the deepest folder that did fit rather than falling back to the root:
        // "somewhere near where it was" beats "back at the top".
        val deepest = folders.single { it.name == "g${FolderTree.MAX_DEPTH - 1}" }
        assertEquals(deepest.id, byTitle["deep"])
    }

    @Test
    fun `an unnamed group still produces a nameable folder`() {
        // A blank folder name is not a legal folder here, and a nameless row in the picker is a row
        // the user cannot choose deliberately.
        val (folders, _) = parse {
            val group = newGroup("")
            group.addEntry(entry("x"))
            rootGroup.addGroup(group)
        }

        assertNotNull(folders.singleOrNull())
        assertTrue(folders.single().name.isNotBlank())
    }

    @Test
    fun `sibling groups sharing a name are both kept`() {
        // Two vaults' worth of "Bank" under different parents is legal at rest, and so is this: the
        // importer does not rename the user's own data.
        val (folders, _) = parse {
            val a = newGroup("Work")
            val b = newGroup("Personal")
            listOf(a, b).forEach { parent ->
                val bank = newGroup("Bank")
                bank.addEntry(entry("e-${parent.name}"))
                parent.addGroup(bank)
                rootGroup.addGroup(parent)
            }
        }

        assertEquals(2, folders.count { it.name == "Bank" })
        assertTrue(FolderTree.validate(folders).isValid)
    }
}
