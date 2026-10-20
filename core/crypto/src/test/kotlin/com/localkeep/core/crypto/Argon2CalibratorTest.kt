package com.localkeep.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Argon2CalibratorTest {
    @Test
    fun `fast device keeps strongest params`() {
        assertEquals(KdfParams.DEFAULT, Argon2Calibrator.calibrate { 10 })
    }

    @Test
    fun `slow device never falls below floor`() {
        assertEquals(KdfParams.FLOOR, Argon2Calibrator.calibrate { 100_000 })
    }

    @Test
    fun `intermediate device picks strongest fitting rung`() {
        val result = Argon2Calibrator.calibrate { params ->
            if (params.memoryKib <= 32_768) 200 else 400
        }

        assertEquals(KdfParams(32_768, 3, 4), result)
    }

    @Test
    fun `target must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            Argon2Calibrator.calibrate(targetMillisPerSlot = 0) { 0 }
        }
    }
    /**
     * A rung that will not fit in the heap must be stepped over, not fatal.
     *
     * This is the API 28 case, and it is not hypothetical: Argon2id's cost parameter is a heap
     * allocation, the top rung asked for 64 MiB of it, and `dalvik.vm.heapgrowthlimit` on the API 28
     * emulator image — and on real low-RAM devices of that era — is 48 MiB, with no `largeHeap`
     * declared. Before the fix, `measure(DEFAULT)` threw `OutOfMemoryError`, which is an `Error` and
     * so passed straight through the ladder walk: creating a vault crashed on a device inside the
     * app's own minSdk. Found by the first instrumented run at API 28, 2026-07-29.
     *
     * The ladder no longer *offers* a rung above 48 MiB — see the portability ceiling below — so on
     * this fake nothing throws at all now. The skip logic is kept and kept tested because the two
     * defences answer different questions: the ceiling is about vaults being portable to devices
     * this run has never seen, and this is about the device this run is actually on.
     */
    @Test
    fun `a rung too large for the heap is skipped rather than fatal`() {
        // `>=`, not `>`. An app never gets the whole growth limit to itself — the framework, the
        // Compose runtime and the vault are already resident — so a rung asking for exactly the cap
        // is one that fails in practice.
        val result = Argon2Calibrator.calibrate { params ->
            if (params.memoryKib >= 32 * 1024) throw OutOfMemoryError("simulated heap limit") else 100
        }

        assertEquals(
            KdfParams(24_576, 3, 2),
            result,
            "the strongest rung that fits the heap should be chosen",
        )
    }

    /**
     * The H-1 invariant: no device, however fast, may create a vault other supported devices cannot
     * open.
     *
     * The `measure` here reports every rung as instant, which is the case that used to select
     * 64 MiB — parameters that then travelled in the vault header to a phone whose heap cap is
     * 48 MiB, where the correct password could not open the right vault at all. Nothing about the
     * creating device can detect that, which is why this is a ceiling rather than a benchmark.
     *
     * Asserted against the constant rather than a literal so it moves with the policy, and asserted
     * on `calibrate` rather than on the ladder so that adding an over-ceiling rung back to the
     * ladder fails here instead of shipping.
     */
    @Test
    fun `the fastest possible device still cannot exceed the portability ceiling`() {
        val chosen = Argon2Calibrator.calibrate { 0 }

        assertTrue(
            chosen.memoryKib <= KdfParams.PORTABLE_MAX_MEMORY_KIB,
            "calibrate picked ${chosen.memoryKib} KiB, above the " +
                "${KdfParams.PORTABLE_MAX_MEMORY_KIB} KiB every supported device must be able to " +
                "allocate — a vault created here would not open at minSdk",
        )
    }

    /** If nothing fits, the floor is the answer — never a crash, and never something below it. */
    @Test
    fun `a device where no rung fits still lands on the floor`() {
        val result = Argon2Calibrator.calibrate { throw OutOfMemoryError("nothing fits") }

        assertEquals(KdfParams.FLOOR, result)
    }

    /**
     * Reproduced on `LocalKeep_API_29` (Pixel 2 profile, `dalvik.vm.heapgrowthlimit=48m`),
     * 2026-09-11: onboarding a new vault killed the process outright.
     *
     * The mechanism is not the one [`a rung too large for the heap is skipped rather than fatal`]
     * covers, and that is why this test exists beside it. Benchmarking the 32 MiB rung on a 48 MiB
     * heap does **not** throw for the thread doing the measuring -- it succeeds, having driven the
     * process to `0% free, 47MB/48MB`, and holds it there for the length of a full Argon2id
     * derivation. Every *other* thread that allocates inside that window dies instead. What
     * actually crashed was `SharedPreferencesImpl.writeToFile` on the `queued-work-looper`:
     *
     *     FATAL EXCEPTION: queued-work-looper
     *     java.lang.OutOfMemoryError: Failed to allocate a 65552 byte allocation with 464 free
     *     bytes and 464B until OOM, target footprint 50331648, growth limit 50331648
     *         at com.android.internal.util.FastXmlSerializer.<init>(FastXmlSerializer.java:86)
     *
     * `fits` catching `OutOfMemoryError` cannot help: the error is raised on a thread this code has
     * never heard of, and by then the damage is a dead process rather than a rung to step over. The
     * only defence is not to probe a rung the heap cannot afford in the first place.
     */
    @Test
    fun `a rung the heap cannot afford is never even probed`() {
        val probed = mutableListOf<Int>()
        val result = Argon2Calibrator.calibrate(
            // What the API 29 emulator actually reports at onboarding: a 48 MiB cap against about
            // 13 MiB already resident.
            availableHeapBytes = { 35L * 1024 * 1024 },
        ) { params ->
            probed += params.memoryKib
            100
        }

        assertTrue(
            KdfParams.PORTABLE_MAX_MEMORY_KIB !in probed,
            "the ${KdfParams.PORTABLE_MAX_MEMORY_KIB} KiB rung was benchmarked against 35 MiB of " +
                "free heap; probing it saturates the process and kills whichever thread allocates " +
                "next, which is not a thread this class can protect",
        )
        assertEquals(
            KdfParams(24_576, 3, 2),
            result,
            "the strongest rung the heap can actually afford should be chosen",
        )
    }

    /** A roomy heap must be unaffected: the ceiling is the only thing capping a modern phone. */
    @Test
    fun `a roomy heap still picks the strongest rung`() {
        val result = Argon2Calibrator.calibrate(availableHeapBytes = { 160L * 1024 * 1024 }) { 10 }

        assertEquals(KdfParams.DEFAULT, result)
    }
}
