package com.localkeep.feature.importwizard

import com.localkeep.core.import.AegisJsonImporter
import com.localkeep.core.import.BitwardenJsonImporter
import com.localkeep.core.import.CsvImporter
import com.localkeep.core.import.Importer
import com.localkeep.core.import.KdbxImporter
import com.localkeep.core.import.OnePuxImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `importerFor` falls back to sniffing when the picked tile does not match the file.
 *
 * That fallback — `ALL_IMPORTERS.firstOrNull { it.detect(bytes) }` — is the one piece of import
 * dispatch with no regression coverage at all, in a module that ships no Robolectric (V15,
 * 2026-09-12). It is how a user who taps "Bitwarden" and picks their Aegis export still gets a
 * working import, so it is squarely on the happy path for anyone who mis-taps.
 *
 * The fallback is only as good as `detect` being *discriminating*: `firstOrNull` returns the first
 * importer that says yes, so an importer that over-matches would silently capture another's file
 * and parse it as a malformed one of its own. This pins each format to exactly one claimant.
 *
 * The list here is hand-written, which W10 rightly calls a narrowing shape. It is not pinned to
 * `ALL_IMPORTERS` because that registry lives in a **private** companion and widening a
 * ViewModel's visibility for a test is a worse trade than the duplication — and the stronger
 * gate already exists: `checkFuzzTargetsCovered` reads `ALL_IMPORTERS` and fails the build when
 * a newly registered importer has no fuzz target, so a sixth importer cannot arrive unnoticed.
 *
 * `KdbxImporter` is left out on purpose — its `detect` reads a binary magic and building a fixture
 * for it means building a real KDBX, which `ImporterFuzzTest` and the device pass already cover.
 */
class ImporterDetectDispatchTest {

    private val bitwarden = """{"encrypted":false,"items":[{"type":1,"name":"x","login":{"username":"a","password":"b"}}]}"""
    private val aegis = """{"version":1,"header":{"slots":null,"params":null},"db":{"version":2,"entries":[]}}"""
    private val csv = "name,url,username,password\nExample,https://example.com,ada,hunter2"

    private fun detectors(): Map<String, Importer> = mapOf(
        "bitwarden" to BitwardenJsonImporter,
        "aegis" to AegisJsonImporter,
        "csv" to CsvImporter,
        "onepux" to OnePuxImporter,
    )

    private fun claimants(bytes: ByteArray): List<String> =
        detectors().filter { (_, importer) -> runCatching { importer.detect(bytes) }.getOrDefault(false) }.keys.toList()

    @Test
    fun `each text format is claimed by exactly one importer`() {
        assertEquals("bitwarden JSON", listOf("bitwarden"), claimants(bitwarden.toByteArray()))
        assertEquals("aegis JSON", listOf("aegis"), claimants(aegis.toByteArray()))
        assertEquals("generic CSV", listOf("csv"), claimants(csv.toByteArray()))
    }

    @Test
    fun `nothing claims bytes that are not an export at all`() {
        listOf(
            ByteArray(0),
            "not an export".toByteArray(),
            "{".toByteArray(),
            "{}".toByteArray(),
        ).forEach { bytes ->
            assertTrue(
                "no importer may claim ${bytes.size} bytes of non-export, or the fallback " +
                    "hands it to a parser that then reports it as a damaged file of that kind",
                claimants(bytes).isEmpty(),
            )
        }
    }

    /** `detect` is called inside `runCatching`, so it may throw — but it must not hang or corrupt. */
    @Test
    fun `detect never throws for the shapes the fallback will feed it`() {
        detectors().forEach { (name, importer) ->
            listOf(ByteArray(0), ByteArray(4096), csv.toByteArray()).forEach { bytes ->
                runCatching { importer.detect(bytes) }
                    .onFailure { error("$name.detect threw on ${bytes.size} bytes: $it") }
            }
        }
    }

}
