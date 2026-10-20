package com.localkeep.core.import

import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * `KeeperCsv.detect` reads a header-less positional CSV, and a JSON document split on commas is a
 * list of rows. A **truncated** Bitwarden export and a legacy 1Password `.1pif` both satisfied every
 * rule it had, so each previewed as `1 item found · Keeper` with a raw JSON fragment offered as a
 * vault item under a brand the user never picked (1A-F3, 2026-09-20).
 *
 * The wizard's tile pinning is the other half of that fix; this is the half that holds even under
 * the Other/CSV tile, where sniffing every format is still what the user asked for.
 */
class KeeperCsvJsonRefusalTest {

    /**
     * The finding's own repro: a Bitwarden export cut off mid-document.
     *
     * The exact truncation matters. Split on commas this fragment is one row of seven cells —
     * `7 + 2n` with n = 0 — whose second cell is `folders:[{id:f1`, which is the title the device
     * preview showed under `1 item found · Keeper`.
     */
    private val truncatedBitwarden = "{\"encrypted\":false,\"folders\":[{\"id\":\"f1\",\"name\":\"Work\"}],\"items\":[{\"type\":1,\"name\":\"Gmail\",\"notes\":null,"

    @Test
    fun `a truncated JSON export is not claimed as a Keeper CSV`() {
        assertFalse(
            KeeperImporter.detect(truncatedBitwarden.toByteArray()),
            "a JSON fragment was read as a header-less positional CSV",
        )
    }

    @Test
    fun `a legacy 1pif entry is not claimed as a Keeper CSV`() {
        val onepif = RealWorldCorpus.text("onepassword/legacy-login.1pif")
            .lines()
            .first { it.trimStart().startsWith("{") }

        assertFalse(KeeperImporter.detect(onepif.toByteArray()), "a .1pif record was claimed by Keeper")
    }

    /** The control: Keeper's own CSV is still claimed, so the new rule refuses nothing real. */
    @Test
    fun `Keeper's own CSV exports are still claimed`() {
        listOf("keeper/export.csv", "keeper/export-dedicated-item-types.csv", "keeper/export-multi-collection.csv")
            .forEach { path ->
                kotlin.test.assertTrue(
                    KeeperImporter.detect(RealWorldCorpus.bytes(path)),
                    "$path is a Keeper CSV and must still be recognised",
                )
            }
    }
}
