package com.localkeep.core.vault

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Login
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * What this app does when someone actually has ten thousand items in it.
 *
 * Until this existed the question had never been answered by anything — not by a test, not by a
 * device pass, not by an estimate anyone had checked. The whole vault is a single encrypted blob
 * that is decrypted, deserialized and held in memory as one immutable `Vault`; every mutation
 * rewrites all of it. That design is deliberate and it is what makes the crypto tractable, but it
 * also means the costs here are **O(vault), not O(edit)** — changing one character of one title
 * re-encrypts everything. Nobody had measured what that costs at the top of the supported range.
 *
 * The numbers this prints are the deliverable; the assertions are a **regression fence**, not a
 * performance target. They are set roughly 3× above what was measured on the emulator so that an
 * accidental O(n²) — a `contains` inside a `filter`, a re-parse per keystroke — fails the build,
 * while ordinary hardware variance does not. Tightening them to the measured value would produce a
 * test that fails on a slower device and tells you nothing about the code.
 *
 * The unlock figure is reported **twice**, and the difference is the point. A cold unlock is
 * dominated by Argon2id, which costs the same whether the vault holds one item or ten thousand, so
 * the raw total would hide the thing being measured. [unlockBaselineMs] is the same unlock on an
 * empty vault under identical KDF parameters; subtracting it isolates decrypt + deserialize.
 */
@RunWith(AndroidJUnit4::class)
class VaultScaleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager

    /**
     * Deliberately not [KdfParams.DEFAULT]. The KDF is measured separately and subtracted out, so
     * paying the full production cost here would add ~3 s per unlock to a test that already writes
     * 10,000 items several times, and would buy no information this test is looking for.
     */
    private val kdfParams = KdfParams(memoryKib = 24_576, iterations = 3, parallelism = 2)

    @BeforeTest
    fun setup() {
        cleanupFiles()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context)).also {
            it.autoLockTimeoutMs = 0
        }
    }

    @AfterTest
    fun cleanup() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    @Test
    fun tenThousandItemsStayUsable() = runBlocking {
        // Not a soft assertion — a statement about what this device can hold.
        //
        // A 10,000-item vault is ~8 MiB serialized, and several times that as a live object graph:
        // 10,000 `VaultItem`s, their strings, the protobuf buffer built during a save, and the
        // search index. Android caps an ordinary app at `dalvik.vm.heapgrowthlimit` — 192 MiB on
        // the API 35 image, but **48 MiB on API 28**, this app's own minSdk — and at 48 MiB the save
        // dies trying to grow an 8 MiB buffer with 2.6 MiB free. Measured 2026-07-29.
        //
        // So this is skipped rather than failed there, and the skip is the finding: **LocalKeep
        // cannot hold 10,000 items on a low-heap Android 9 device.** That bounds the supported
        // scale, and it is recorded as a known limit rather than hidden behind a green suite.
        //
        // What changed on 2026-07-29 is what happens when a user reaches that bound anyway. The
        // save no longer crashes: `commit` catches the `OutOfMemoryError` around the encrypt and
        // throws `VaultTooLargeForDeviceException`, and the editor answers it with a message that
        // does not tell the user to try again — see `SaveFailureMessageTest`. The ceiling is still
        // the ceiling; hitting it is now an explanation instead of a process death.
        val maxHeapMib = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        assumeTrue(
            "a $ITEM_COUNT-item vault needs more than this device's ${maxHeapMib} MiB heap cap",
            maxHeapMib >= MIN_HEAP_MIB_FOR_SCALE,
        )

        // --- baseline: what an unlock costs with nothing in the vault ---------------------------
        val unlockBaselineMs = measure { assertEquals(UnlockResult.REAL, lockManager.unlock(password())) }

        // --- seeding: one commit, so this is one encrypt + one atomic write of the whole vault ---
        val items = List(ITEM_COUNT) { index -> syntheticItem(index) }
        val commitMs = measure {
            VaultRepository(lockManager).mutate { mutator -> mutator.items.addAll(items) }
        }
        val vaultBytes = File(context.filesDir, AtomicVaultWriter.VAULT_FILE).length()

        // --- cold unlock of the loaded vault ----------------------------------------------------
        lockManager.lock()
        val unlockLoadedMs = measure { assertEquals(UnlockResult.REAL, lockManager.unlock(password())) }
        val repository = VaultRepository(lockManager)
        assertEquals(ITEM_COUNT, repository.getAllItems().size, "every seeded item must survive the round trip")

        // --- search: one keystroke, worst case (a needle that matches nothing scans everything) --
        val searchMissMs = median(SEARCH_SAMPLES) { repository.search("zzzznotpresent") }
        val hits = repository.search("item-04")
        val searchHitMs = median(SEARCH_SAMPLES) { repository.search("item-04") }

        // --- one edit: the O(vault)-not-O(edit) cost -------------------------------------------
        val target = repository.getAllItems().first()
        val editMs = measure {
            repository.updateItem(target.copy(title = "renamed", updatedAt = Instant.now()))
        }

        Log.i(
            TAG,
            """
            |D1 scale, $ITEM_COUNT items
            |  vault file            ${vaultBytes / 1024} KiB
            |  unlock, empty         $unlockBaselineMs ms  (Argon2id, memoryKib=${kdfParams.memoryKib})
            |  unlock, loaded        $unlockLoadedMs ms
            |  decrypt + parse       ${unlockLoadedMs - unlockBaselineMs} ms  (the difference)
            |  commit all items      $commitMs ms
            |  one edit              $editMs ms
            |  search, no match      $searchMissMs ms  (median of $SEARCH_SAMPLES)
            |  search, ${hits.size} matches   $searchHitMs ms
            """.trimMargin(),
        )

        // --- the fence ---------------------------------------------------------------------------
        // A keystroke is the only one of these the user waits on with no progress indicator, so it
        // is the only tight bound. 16 ms is one frame; 250 ms is where a text field starts to feel
        // detached from the keyboard. Anything above that is a defect regardless of hardware.
        assertTrue(searchMissMs < SEARCH_CEILING_MS, "search took $searchMissMs ms per keystroke")
        assertTrue(searchHitMs < SEARCH_CEILING_MS, "search took $searchHitMs ms per keystroke")
        assertTrue(commitMs < WRITE_CEILING_MS, "committing $ITEM_COUNT items took $commitMs ms")
        assertTrue(editMs < WRITE_CEILING_MS, "one edit took $editMs ms")
        assertTrue(
            unlockLoadedMs - unlockBaselineMs < LOAD_CEILING_MS,
            "decrypt + parse took ${unlockLoadedMs - unlockBaselineMs} ms",
        )
    }

    // --- fixture ---------------------------------------------------------------------------------

    /**
     * An item with enough substance to be worth encrypting — a bare title would understate the blob
     * by an order of magnitude and make every number here optimistic. Notes, a URI and a tag put a
     * synthetic item within range of a real one.
     */
    private fun syntheticItem(index: Int): VaultItem {
        val now = Instant.now()
        return VaultItem(
            id = UUID.randomUUID(),
            title = "item-%05d service".format(index),
            tags = listOf("tag-${index % 40}"),
            favorite = index % 100 == 0,
            createdAt = now,
            updatedAt = now,
            content = Login(
                username = "user%05d@example.invalid".format(index),
                password = "correct-horse-battery-staple-%05d".format(index),
                uris = listOf(UriMatch(uri = "https://host%05d.example.invalid/login".format(index))),
                notes = "Recovery questions and account notes for entry $index. ".repeat(3),
            ),
        )
    }

    private inline fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    /**
     * Median rather than mean, and warmed first. A single timing on ART measures the JIT as much as
     * the code; one outlier in a mean would swamp the fence.
     */
    private inline fun median(samples: Int, block: () -> Unit): Long {
        repeat(3) { block() }
        val timings = List(samples) {
            val start = System.nanoTime()
            block()
            System.nanoTime() - start
        }.sorted()
        return timings[timings.size / 2] / 1_000_000
    }

    private fun createFreshVault() {
        val password = password()
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kek = EnvelopeCrypto.deriveKek(password, salt, kdfParams)
        try {
            AtomicVaultWriter.write(
                context,
                VaultFileCodec.create(
                    dek = dek,
                    salt = salt,
                    kdfParams = kdfParams,
                    wrappedDek = EnvelopeCrypto.wrapDek(kek, dek),
                    commitment = EnvelopeCrypto.slotCommitment(dek),
                ),
            )
        } finally {
            Zeroizer.wipe(password)
            Zeroizer.wipe(dek)
            Zeroizer.wipe(kek)
        }
    }

    private fun cleanupFiles() {
        File(context.filesDir, AtomicVaultWriter.VAULT_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.BACKUP_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.TEMP_FILE).delete()
        File(context.filesDir, DeviceKeyStore.FILE_NAME).delete()
        File(context.filesDir, StaleContainerSweeper.BACKUPS_DIR).deleteRecursively()
    }

    private fun password(): CharArray = "test-master-password-123".toCharArray()

    private companion object {
        const val TAG = "VaultScaleTest"

        /** The top of the range the app claims to support. */
        const val ITEM_COUNT = 10_000
        const val SEARCH_SAMPLES = 9

        /**
         * Below this heap cap the benchmark is skipped. 128 MiB is comfortably above what the run
         * needs on API 35 and comfortably above the 48 MiB where it is known to fail, rather than a
         * threshold tuned to squeak past on one device.
         */
        const val MIN_HEAP_MIB_FOR_SCALE = 128L

        const val SEARCH_CEILING_MS = 250L
        const val WRITE_CEILING_MS = 20_000L
        const val LOAD_CEILING_MS = 15_000L
    }
}
