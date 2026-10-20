package com.localkeep.feature.importwizard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Format detection runs off the main thread (S2-06, 2026-10-02).
 *
 * `onFilePicked` resumes on Main after its IO read, and asked every claimant's `detect` — a full CSV
 * parse, a full JSON decode, a zip walk that inflated every entry — and `needsPassword` right there,
 * over up to 64 MB. A source assertion because the thread a call ran on is not observable from the
 * state it produces; `OnePuxDecoyEntryTest` in `:core:import` pins the zip half by behaviour.
 */
class ImportDetectOffMainTest {

    private val viewModel = File("src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardViewModel.kt")

    @Test
    fun `detection and the password question run on a background dispatcher`() {
        if (!viewModel.isFile) fail("cannot find ${viewModel.absolutePath} — this test reads the source it guards")
        val body = viewModel.readText().substringAfter("fun onFilePicked(uri: Uri) {").substringBefore("\n    }")
        val offMain = body.indexOf("withContext(Dispatchers.Default) {")
        val detect = body.indexOf("importerFor(source, bytes)")
        val ask = body.indexOf("encrypted?.needsPassword(bytes)")
        assertTrue(offMain >= 0, "onFilePicked must move detection onto Dispatchers.Default")
        assertTrue(detect > offMain && ask > offMain, "both calls must sit inside that block, not before it")
    }
}
