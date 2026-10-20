package com.localkeep.feature.health

import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Health dashboard says how many entries it could not see, and says nothing when it saw them.
 *
 * 3A-F33, on a device 2026-09-20: with seven entries invisible the screen read `100` /
 * "Nothing worth fixing" / "Every password here is unique, not weak, and not in the offline breach
 * set." The same vault visible read `88`, two reused, one weak, one compromised. The Invisible-items
 * screen does disclose the trade when the lease is taken ("takes it out of the security dashboard's
 * counts"), but the dashboard is where the claim gets made, and the word carrying the whole of the
 * qualification was "here".
 *
 * The trap this pins is the obvious implementation. `VaultVisibilitySnapshot.concealedItemIds` is
 * populated whether or not a reveal session is running — a revealed session keeps the ids so a row
 * can be *marked* as hidden, while the entries themselves are in `liveItems` and are scanned. So a
 * note driven off the size of that set would print "7 hidden entries were not checked" directly
 * under a score that had just checked all seven, which is a second false statement in place of the
 * first.
 */
class ExcludedFromScanTest {

    private val t: Instant = Instant.parse("2026-09-20T00:00:00Z")

    private fun item() = VaultItem(
        id = UUID.randomUUID(),
        createdAt = t,
        updatedAt = t,
        content = Login(username = "u", password = "p", passwordChangedAt = t),
    )

    private fun note() = VaultItem(
        id = UUID.randomUUID(),
        createdAt = t,
        updatedAt = t,
        content = Note(body = "b"),
    )

    private fun trashedLogin() = item().copy(deletedAt = t)

    /** What the ViewModel passes: everything the scan saw plus everything it was kept from. */
    private fun excluded(concealed: List<VaultItem>, scanned: List<VaultItem>) =
        excludedFromScan(concealed.map { it.id }.toSet(), scanned, scanned + concealed)

    @Test
    fun `an ordinary session counts nothing`() {
        val scanned = List(5) { item() }
        assertEquals(0, excludedFromScan(emptySet(), scanned, scanned))
    }

    @Test
    fun `concealed entries the scan never saw are counted`() {
        val scanned = List(5) { item() }
        val hidden = List(7) { item() }
        assertEquals(7, excluded(hidden, scanned))
    }

    /**
     * The scan only ever looks at live Logins, so "N hidden entries were not checked" may only count
     * those. A hidden note, a hidden card and a hidden login in the trash were counted too - the note
     * then said entries had gone unchecked under a score that had checked every login there was, and
     * revealed a count of hidden things the scan was never going to read.
     */
    @Test
    fun `a hidden note is not an unchecked entry`() {
        val scanned = List(5) { item() }
        assertEquals(0, excluded(listOf(note(), note()), scanned))
    }

    @Test
    fun `a hidden login in the trash is not an unchecked entry`() {
        val scanned = List(5) { item() }
        assertEquals(0, excluded(listOf(trashedLogin()), scanned))
    }

    @Test
    fun `only the hidden logins are counted among mixed hidden entries`() {
        val scanned = List(5) { item() }
        val hidden = listOf(item(), item(), note(), trashedLogin())
        assertEquals(2, excluded(hidden, scanned))
    }

    /** The reveal session: the ids are still there, the entries were scanned, so nothing is owed. */
    @Test
    fun `a reveal session counts nothing, because the concealed entries were scanned`() {
        val concealed = List(7) { item() }
        val scanned = concealed + List(5) { item() }
        assertEquals(
            0,
            excludedFromScan(concealed.map { it.id }.toSet(), scanned, scanned),
            "a revealed session scans the concealed entries, so the dashboard must not claim it skipped them",
        )
    }

    /** Ancestry conceals a whole folder: some of its entries may still have been scanned. */
    @Test
    fun `only the concealed entries missing from the scan are counted`() {
        val concealed = List(4) { item() }
        val scanned = concealed.take(1) + List(3) { item() }
        assertEquals(3, excludedFromScan(concealed.map { it.id }.toSet(), scanned, concealed + scanned))
    }
}
