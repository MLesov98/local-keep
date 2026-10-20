package com.localkeep.core.crypto

/** Selects the strongest supported rung fitting one unlock slot's time budget. */
object Argon2Calibrator {
    /**
     * Strongest first. Nothing above [KdfParams.PORTABLE_MAX_MEMORY_KIB] appears here, and that is
     * a deliberate deletion rather than an omission: the ladder used to open at 64 MiB and 48 MiB,
     * and a modern phone would pick one of them for a vault that then could not be opened on a
     * device at this app's own `minSdk` (H-1). Degrading on time was never the only constraint; the
     * ladder just never knew about the other one.
     *
     * The ceiling is checked in [calibrate] rather than assumed of this list, because a rung added
     * here later is exactly how the invariant would come back undone.
     */
    private val ladder = listOf(
        KdfParams.DEFAULT,
        KdfParams(memoryKib = 24_576, iterations = 3, parallelism = 2),
        KdfParams.FLOOR,
    )

    fun calibrate(
        targetMillisPerSlot: Long = 250L,
        availableHeapBytes: () -> Long = ::freeHeapBytes,
        measure: (KdfParams) -> Long = ::benchmark,
    ): KdfParams {
        require(targetMillisPerSlot > 0) { "target must be positive" }
        val available = availableHeapBytes()
        return ladder
            .filter { it.memoryKib <= KdfParams.PORTABLE_MAX_MEMORY_KIB }
            .filter { affordable(it, available) }
            .firstOrNull { fits(it, targetMillisPerSlot, measure) }
            ?: KdfParams.FLOOR
    }

    /**
     * Whether this device can afford to *probe* [params], as distinct from whether the probe
     * would throw.
     *
     * Measured on `LocalKeep_API_29` (48 MiB growth limit), 2026-09-11: benchmarking the 32 MiB
     * rung does not throw for the thread doing the benchmarking. It succeeds, and holds the process
     * at `0% free, 47MB/48MB` for the length of a full derivation, during which any *other* thread
     * that allocates gets the `OutOfMemoryError` instead. Onboarding a new vault died that way on
     * `queued-work-looper`, inside `SharedPreferencesImpl.writeToFile` -- a thread nothing here can
     * wrap in a `try`. [fits] answers "did this rung throw"; the process needs "can this rung run
     * without starving everything else", and only this can answer that.
     *
     * The headroom is not a tuning knob to shave. An app never has the growth limit to itself: the
     * framework, the Compose runtime, the vault being created and the SharedPreferences writer are
     * all resident, and the crash above was a 64 KB allocation losing by 464 bytes.
     */
    private fun affordable(params: KdfParams, availableBytes: Long): Boolean =
        params.memoryKib.toLong() * 1024L + HEAP_HEADROOM_BYTES <= availableBytes

    private const val HEAP_HEADROOM_BYTES = 8L * 1024L * 1024L

    private fun freeHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
    }

    /**
     * Whether [params] both fits in this device's heap and lands inside the time budget.
     *
     * **The memory half is not hypothetical.** Argon2id's cost parameter *is* a heap allocation:
     * BouncyCastle holds the whole cost as a `Block[]` on the Java heap, so the top rung asks for
     * 64 MiB in one structure. Android caps an ordinary app at `dalvik.vm.heapgrowthlimit`, which
     * on the API 28 emulator image — and on real low-RAM devices of that era — is **48 MiB**.
     *
     * The app declares `largeHeap` since 2026-09-14, so on a device that also raises
     * `dalvik.vm.heapsize` the growth limit is no longer what binds: measured on `LocalKeep_API_29`
     * 2026-09-17, `heapgrowthlimit=48m` but `heapsize=512m`, and `dumpsys package` reports
     * `LARGE_HEAP`. Neither this check nor [affordable] may be relaxed on the strength of that. A
     * `largeHeap` request is a request — a device is free to ignore it, and a `ro.config.low_ram`
     * device does — so the 48 MiB heap is still a heap this app runs on. This line said the app
     * declared no `largeHeap` until the date above, which was simply out of date.
     *
     * Before this, that was fatal rather than a degradation: `calibrate` walked the ladder from the
     * strongest rung down, benchmarking each, and the very first `measure(DEFAULT)` threw
     * `OutOfMemoryError` — which is an `Error`, not an `Exception`, so nothing caught it and the
     * walk never reached a rung that would have fit. The ladder existed precisely to degrade
     * gracefully and degraded only on *time*. Found on 2026-07-29 by the first run of the
     * instrumented suite at minSdk: vault creation, and backup export, crashed outright.
     *
     * Catching `OutOfMemoryError` is normally a mistake, and is deliberate here. The failed
     * allocation is one known structure that becomes unreachable the moment the attempt fails, so
     * the heap recovers; and the alternative is not a cleaner failure, it is the app being unable
     * to create a vault at all on a device it claims to support.
     */
    private fun fits(
        params: KdfParams,
        targetMillisPerSlot: Long,
        measure: (KdfParams) -> Long,
    ): Boolean = try {
        measure(params) <= targetMillisPerSlot
    } catch (_: OutOfMemoryError) {
        false
    }

    private fun benchmark(params: KdfParams): Long {
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val password = "calibration-probe".toCharArray()
        var key: ByteArray? = null
        return try {
            val start = System.nanoTime()
            key = Argon2.deriveKey(password, salt, params)
            (System.nanoTime() - start) / 1_000_000
        } finally {
            Zeroizer.wipe(key)
            Zeroizer.wipe(password)
            Zeroizer.wipe(salt)
        }
    }
}
