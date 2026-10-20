package com.localkeep.feature.importwizard

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a source silently fails to carry must be said on the way in, not discovered on the way out.
 *
 * The wizard's last step offers to securely delete the export, so anything an importer drops has
 * one chance to be disclosed and that chance is here. Two gaps closed on 2026-09-13:
 *
 * - **1Password attachments (F15).** A `.1pux` is a zip that really does carry files — a `files/`
 *   directory beside `export.data`, with each item's `documentAttributes` naming its `documentId`
 *   (1Password's published format description). `OnePuxImporter` declares neither field, so a file
 *   attached to an otherwise-mapped Login, Card or Identity vanished with no skip entry. The
 *   sibling KEEPASS and BITWARDEN caveats had said the same thing about their own formats since
 *   2026-08-30; 1Password was the one left silent.
 *
 *   **Half of that is fixed as of 2026-09-17** and the caveat was narrowed rather than deleted: a
 *   file saved as its own Document item now crosses over, and a file attached to a Login, Card or
 *   Identity through a section field still does not. The key stays, because the warning it carries
 *   is still true of the second case — and a caveat that over-claims a fix is the same defect as
 *   one that hides a loss.
 * - **Bitwarden passkeys (F47).** Bitwarden's JSON export carries them as `login.fido2Credentials`
 *   — verified against `fido2-credential.export.ts` in bitwarden/clients, 2026-09-13 — and this
 *   build drops them: they cannot be mapped in `:core:import`, which holds no vault key to seal a
 *   private key with.
 *
 * The third entry is not a loss but a *translation* — organisation collections arrive as tags
 * (F16) — and it is disclosed for the opposite reason: the item shows up carrying labels the user
 * never typed, and nothing else on screen explains where they came from.
 */
class ImportSourceCaveatTest {

    @Test
    fun `1Password discloses that attachments are dropped`() {
        assertTrue(
            R.string.import_source_onepassword_caveat_2 in ImportSource.ONEPASSWORD.caveats,
            "a .1pux carries attachments this build does not read; the wizard must say so before " +
                "offering to delete the export",
        )
    }

    @Test
    fun `Bitwarden discloses passkeys and the collection mapping`() {
        assertTrue(R.string.import_source_bitwarden_caveat_3 in ImportSource.BITWARDEN.caveats)
        assertTrue(R.string.import_source_bitwarden_caveat_4 in ImportSource.BITWARDEN.caveats)
    }

    /**
     * The control. These predate the two above and must survive adding them — a fix that appends a
     * caveat while dropping one is indistinguishable from a pass if only the new key is asserted.
     */
    @Test
    fun `the caveats that were already there are still there`() {
        assertTrue(R.string.import_source_onepassword_caveat_1 in ImportSource.ONEPASSWORD.caveats)
        assertTrue(R.string.import_source_bitwarden_caveat_1 in ImportSource.BITWARDEN.caveats)
        assertTrue(R.string.import_source_bitwarden_caveat_2 in ImportSource.BITWARDEN.caveats)
        assertTrue(R.string.import_source_keepass_caveat_4 in ImportSource.KEEPASS.caveats)
    }

    /**
     * Every source that needs a password or produces a plaintext file says *something*. A source
     * with an empty caveat list is how both gaps above went unnoticed.
     */
    @Test
    fun `no source that drops data is silent`() {
        val mustSpeak = listOf(
            ImportSource.ONEPASSWORD,
            ImportSource.BITWARDEN,
            ImportSource.KEEPASS,
            ImportSource.AEGIS,
            ImportSource.CHROME,
            ImportSource.APPLE,
            ImportSource.CSV,
            ImportSource.KEEPER,
            ImportSource.ENPASS,
            ImportSource.PROTONPASS,
            ImportSource.DASHLANE,
        )
        mustSpeak.forEach { assertTrue(it.caveats.isNotEmpty(), "${it.name} has no caveats at all") }
    }
}
