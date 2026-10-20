package com.localkeep.core.import

import java.io.ByteArrayInputStream
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.KdbxStreamFormat
import org.linguafranca.pwdb.kdbx.dom.DomSerializableDatabase
import org.w3c.dom.Element

/**
 * What a KDBX container itself says it holds, read straight off the decrypted XML.
 *
 * This is how `RealWorldKeePassImportTest` gets an item count it did not take from the thing it is
 * testing. [KdbxImporter] walks the same DOM, but it is the *walk* that is under test — which
 * groups it descends into, what it does with the Recycle Bin, whether an entry can go missing on
 * the way to a [VaultItem] — and counting `<Entry>` elements in the container answers that
 * independently of it. A KDBX has no sibling plaintext export the way `NewDatabase.kdbx` does, so
 * without this there is nothing to check a count against.
 *
 * `<History>` entries are excluded: they are previous revisions of the entry that contains them,
 * not separate records, and KeePass itself does not count them.
 */
object RealWorldKdbxOracle {

    /** One KDBX container's own inventory. */
    data class Contents(
        /** Live `<Entry>` elements, history revisions excluded. */
        val entries: Int,
        /** Entries inside the group named by `Meta/RecycleBinUUID`, which are part of [entries]. */
        val recycleBinEntries: Int,
        /** `<Group>` elements below the root group, which is the database itself and not a folder. */
        val groups: Int,
    )

    /**
     * [keyFile] is the raw key-file bytes for a container that needs one. Built with the library's
     * own `KdbxCreds` constructors rather than [KdbxImporter]'s composite, deliberately: this is the
     * oracle, so it must not derive its key the same way the code under test does.
     */
    fun contents(bytes: ByteArray, password: String, keyFile: ByteArray? = null): Contents {
        val pw = password.toByteArray(Charsets.UTF_8)
        val creds =
            if (keyFile == null) KdbxCreds(pw) else KdbxCreds(pw, ByteArrayInputStream(keyFile))
        val database = DomSerializableDatabase.createEmptyDatabase()
        KdbxStreamFormat(TwofishKdbxHeader()).load(database, creds, ByteArrayInputStream(bytes))
        val keePassFile = database.doc.documentElement
        val root = children(keePassFile, "Root").first()
        val rootGroup = children(root, "Group").first()
        val binId = children(keePassFile, "Meta").firstOrNull()
            ?.let { children(it, "RecycleBinUUID").firstOrNull()?.textContent?.trim() }
            ?.takeIf { it.isNotEmpty() && it != "AAAAAAAAAAAAAAAAAAAAAA==" }

        var entries = 0
        var binEntries = 0
        var groups = 0
        fun walk(group: Element, insideBin: Boolean) {
            val here = insideBin || (binId != null && children(group, "UUID").firstOrNull()?.textContent?.trim() == binId)
            entries += children(group, "Entry").size
            if (here) binEntries += children(group, "Entry").size
            children(group, "Group").forEach { groups++; walk(it, here) }
        }
        walk(rootGroup, insideBin = false)
        return Contents(entries = entries, recycleBinEntries = binEntries, groups = groups)
    }

    private fun children(parent: Element, name: String): List<Element> {
        val nodes = parent.childNodes
        val out = mutableListOf<Element>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == name) out += node
        }
        return out
    }
}
