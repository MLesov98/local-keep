package com.localkeep.feature.importwizard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A `.1pux` archive carries the files its Document items are, and `core/import` has no vault key
 * and no blob store to seal them with — so it hands over plaintext bytes and this wizard does the
 * write. `RealWorldOnePasswordImportTest.a 1PUX attachment reaches the vault` is the behaviour test
 * for the parse half; these pin the four things the wizard half has to get right or an imported
 * file either never lands, lands unencrypted, or lands as an item pointing at nothing.
 *
 * Source assertions, the same convention and for the same reason as [ImportKeyFileThreadingTest]:
 * `feature/import` has no Robolectric, so the ViewModel cannot be constructed here.
 */
class ImportAttachmentSealingTest {

    private val viewModel = File("src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardViewModel.kt")

    private fun text(file: File): String {
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readText()
    }

    private fun bodyOf(source: String, marker: String): String =
        source.substringAfter(marker).substringBefore("\n    }")

    /** The store the item editor uses, not a second write path invented here. */
    @Test
    fun `imported files are sealed through the shared blob store`() {
        val body = bodyOf(text(viewModel), "private suspend fun sealPayloads(")
        assertTrue(
            body.contains("blobStore.encryptToBlob("),
            "an imported file must go through FileBlobStore.encryptToBlob — the same call " +
                "ItemEditViewModel makes; anything else is a second encoding of the same thing",
        )
        assertTrue(
            body.contains("blobRef = ref"),
            "the resulting ref must reach the item, or the file is written and never referenced",
        )
    }

    /**
     * Before the merge. `ItemMerge.preserveStructure` gives a merged item the *vault's* id, so a
     * payload keyed on the parsed item's id has nothing to attach to once the merge has run.
     */
    @Test
    fun `sealing runs before the merge and fails the import rather than dropping the file`() {
        val body = bodyOf(text(viewModel), "fun applyImport() {")
        val sealAt = body.indexOf("sealPayloads(selected)")
        val mergeAt = body.indexOf("ImportMerger.apply(")
        assertTrue(sealAt in 0 until mergeAt, "sealPayloads must run before ImportMerger.apply")
        assertTrue(
            body.contains("reconcileFolders(mutator, sealed, current.parsedFolders)"),
            "the merge must receive the sealed items, not the unsealed originals",
        )
    }

    /**
     * The DEK is a private copy: sealing a large attachment can outlive the auto-lock grace timer,
     * and `lock()` zeroes the manager's own array underneath a write in flight — which would seal
     * the blob under an all-zero key.
     */
    @Test
    fun `sealing takes a session snapshot rather than the live DEK`() {
        val body = bodyOf(text(viewModel), "private suspend fun sealPayloads(")
        assertTrue(
            body.contains("lockManager.sessionSnapshotOrNull()"),
            "sealPayloads must snapshot the session, as ItemEditViewModel does",
        )
        assertTrue(body.contains("session.dek.fill(0)"), "the snapshot's copy must be wiped")
        assertFalse(
            body.contains("currentDekOrThrow()"),
            "the live array belongs to VaultLockManager and is zeroed on lock underneath a write",
        )
    }

    /**
     * The plaintext files are the user's documents. They live off [ImportUiState] for the same
     * reason the export's own bytes do, are wiped once the import has been written, and are wiped
     * when the wizard is abandoned — backing out of the preview must not leave a passport scan in
     * heap.
     *
     * Not wiped as each one is sealed, any more (2026-10-02): a failed apply leaves the preview up
     * with Import enabled, and the retry has to have the bytes to seal. Wiping them mid-apply is what
     * let the retry save every Document as a File item pointing at nothing —
     * `ImportWizardApplyAndReadTest` drives that end to end.
     */
    @Test
    fun `plaintext payloads are wiped on both the apply and the abandon path`() {
        assertTrue(
            text(viewModel).contains("private var pendingPayloads: Map<UUID, List<ImportedFile>>"),
            "payloads must live off ImportUiState — that object is what a crash report reaches",
        )
        assertFalse(
            bodyOf(text(viewModel), "private suspend fun sealPayloads(").contains("payload.bytes.fill(0)"),
            "sealing must not wipe a file a retried apply would still need",
        )
        assertTrue(
            bodyOf(text(viewModel), "fun applyImport() {").contains("if (result.isSuccess) discardPending()"),
            "a committed import must wipe the payloads it sealed",
        )
        val discard = bodyOf(text(viewModel), "private fun discardPending() {")
        assertTrue(
            discard.contains("pendingPayloads.values.flatten().forEach { it.bytes.fill(0) }"),
            "abandoning the wizard must wipe payloads that were never sealed",
        )
    }
}
