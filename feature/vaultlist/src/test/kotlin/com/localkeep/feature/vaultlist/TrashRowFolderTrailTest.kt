package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A trash row has to say where the item came from.
 *
 * 2C-F8, on a device 2026-09-20: the vault list appends the folder to every row
 * (`desc='Bar 2, john.doe2@example.com, in Foo / Baz'`) and neither the Trash row nor its preview
 * dialog did, so Restore was blind until it landed — on the one screen where the alternative to
 * getting it wrong is Delete forever.
 *
 * The lookup has to span live *and* trashed folders, which is the half that is easy to get wrong:
 * an item trashed on its own still points at a live folder, and one trashed along with its folder
 * points at a trashed one. Resolving against either list alone leaves half the rows silent, and a
 * silent row reads as "this one had no folder" rather than as "this screen cannot tell you".
 */
class TrashRowFolderTrailTest {

    private val ellipsis = "…"

    private val foo = Folder(UUID.randomUUID(), "Foo")
    private val baz = Folder(UUID.randomUUID(), "Baz", parentId = foo.id)
    private val deep1 = Folder(UUID.randomUUID(), "L1")
    private val deep2 = Folder(UUID.randomUUID(), "L2", parentId = deep1.id)
    private val deep3 = Folder(UUID.randomUUID(), "L3", parentId = deep2.id)
    private val deep4 = Folder(UUID.randomUUID(), "L4", parentId = deep3.id)

    private val tree = listOf(foo, baz, deep1, deep2, deep3, deep4)

    @Test
    fun `the trail is the item's whole path, with no browse scope to subtract`() {
        assertEquals("Foo / Baz", folderTrail(tree, baz.id, ellipsis))
    }

    @Test
    fun `an item filed nowhere gets no trail rather than a marker`() {
        assertNull(folderTrail(tree, folderId = null, ellipsis = ellipsis))
    }

    @Test
    fun `a long path keeps its ends and elides the middle`() {
        assertEquals("L1 / … / L4", folderTrail(tree, deep4.id, ellipsis))
    }

    @Test
    fun `a folder the screen cannot resolve produces no trail at all`() {
        // Rather than a half-written one. This is the case that makes `folderTree` have to carry
        // the trashed folders too: without them, every item trashed along with its folder lands
        // here.
        assertNull(folderTrail(tree, UUID.randomUUID(), ellipsis))
    }

    @Test
    fun `a trashed folder resolves once the trash screen's tree includes it`() {
        val trashed = Folder(UUID.randomUUID(), "Archive")
        val child = Folder(UUID.randomUUID(), "2025", parentId = trashed.id)
        assertNull(folderTrail(tree, child.id, ellipsis), "control: not resolvable from live folders alone")
        assertEquals("Archive / 2025", folderTrail(tree + trashed + child, child.id, ellipsis))
    }

    @Test
    fun `the trash row actually draws it`() {
        // The arithmetic above is worth nothing if the screen never asks for it — which was the
        // whole of 2C-F8, since `folderTrailFor` had existed on the vault list for a month.
        val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/TrashScreen.kt")
        if (!source.isFile) fail("cannot find ${source.absolutePath} - this test reads the source it guards")
        val code = source.readText()
        assertTrue(
            "folderTrail(state.folderTree, item.folderId, trailEllipsis)" in code,
            "the trash item row must resolve the item's folder trail against the screen's own " +
                "live-plus-trashed folder tree",
        )
        assertTrue(
            "LkIcons.Folder" in code,
            "and draw it with the same glyph the vault list uses, so the two rows read alike",
        )
    }
}
