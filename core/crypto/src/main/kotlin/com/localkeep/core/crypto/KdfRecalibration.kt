package com.localkeep.core.crypto

/**
 * Whether a vault's stored Argon2id cost still suits the device it is now sitting on.
 *
 * design.md §11.1 asks for this on restore or import: *"On restore/import onto different hardware,
 * re-benchmark and offer to re-wrap with recalibrated params (an old high-memory vault could be
 * unusably slow — or an old weak one too fast — on the new device)."* The parameters are chosen
 * once, at vault creation, on whatever phone the user had then; they are then carried in the header
 * for the life of the vault, across every export, restore and new device.
 *
 * Both directions matter, and they fail differently. Too slow is a usability failure the user can
 * see — a vault that takes eight seconds to open on a budget phone is one they stop locking. Too
 * fast is a *security* failure they cannot see at all: the same parameters that cost 250 ms on a
 * 2019 handset cost a fraction of that on modern hardware, and an offline attacker gets exactly
 * that speed-up for free.
 *
 * The rule is pure and lives here so it can be tested without a device, which matters more than
 * usual: the expensive half is a benchmark, and a rule tangled up with one is a rule nobody
 * exercises.
 */
object KdfRecalibration {

    /**
     * Above this, unlocking is slow enough to change behaviour rather than merely annoy.
     *
     * Chosen against the 250 ms calibration target rather than pulled from the air: it is roughly
     * six times the budget, which is the point at which the delay stops reading as "working" and
     * starts reading as "stuck".
     */
    const val UNUSABLY_SLOW_MS: Long = 1_500L

    /**
     * Below this, the stored cost is being bought far too cheaply on this hardware, and an attacker
     * with the file gets the same discount. Half the target is the trigger — a device twice as fast
     * as the one that set the parameters.
     */
    const val SUSPICIOUSLY_FAST_MS: Long = 125L

    enum class Verdict {
        /** Stored parameters still fit this device. No prompt. */
        KEEP,

        /** This device can afford more work per guess than the vault currently asks for. */
        OFFER_STRONGER,

        /** The vault is slow enough here to be worth weakening deliberately, with eyes open. */
        OFFER_FASTER,

        /**
         * The vault's stored cost exceeds what every supported device can allocate, so there are
         * phones this app runs on that cannot open it at all.
         *
         * Nothing about the current device produces this verdict — it fires on a fast phone where
         * the vault opens instantly, which is the whole point. [OFFER_FASTER] and [OFFER_STRONGER]
         * both describe *here*, and neither can ever fire on the device where the fix has to be
         * applied: re-wrapping needs the vault open, so it can only be done somewhere the vault
         * already opens, and there it is not slow. A vault created at 64 MiB before the
         * [KdfParams.PORTABLE_MAX_MEMORY_KIB] ceiling existed would otherwise carry that forever,
         * with the control that fixes it permanently hidden.
         */
        OFFER_PORTABLE,
    }

    /**
     * Total work per derivation, as the single number the ladder is ordered by.
     *
     * Memory × iterations is what an attacker has to pay per guess; parallelism divides the *wall
     * clock* for a defender with cores to spare but does not reduce the total work, so it is
     * deliberately not in this product. Comparing on memory alone would call
     * `64 MiB × t=1` stronger than `48 MiB × t=4`, which is backwards.
     */
    fun work(params: KdfParams): Long =
        params.memoryKib.toLong() * params.iterations.toLong()

    /**
     * @param stored the parameters in the vault header.
     * @param calibrated what [Argon2Calibrator] picks for this device now.
     * @param storedMillis how long [stored] actually took to derive here, measured.
     */
    fun assess(stored: KdfParams, calibrated: KdfParams, storedMillis: Long): Verdict = when {
        // First, and ahead of both speed rules, because it is the only one about a device that is
        // not this one. A vault above the portability ceiling cannot be opened on a phone at this
        // app's own minSdk — and the speed rules, which only ever describe the current device, will
        // say KEEP on the fast phone where the re-wrap is possible. Ordering this below them would
        // hide the fix behind a verdict that never fires.
        stored.memoryKib > KdfParams.PORTABLE_MAX_MEMORY_KIB -> Verdict.OFFER_PORTABLE

        // Slowness is judged on the measurement, never on the numbers: a vault can carry modest
        // parameters and still crawl on a device under thermal or memory pressure, and the user
        // experiences the seconds, not the KiB.
        storedMillis > UNUSABLY_SLOW_MS && work(calibrated) < work(stored) -> Verdict.OFFER_FASTER

        // Strength is judged on both, and needs both: a fast measurement alone could just mean a
        // briefly idle device, while a stronger available rung alone could be a rounding artefact.
        // Requiring the calibrator to actually offer more work AND the current cost to be well
        // under budget keeps this from firing on noise.
        storedMillis < SUSPICIOUSLY_FAST_MS && work(calibrated) > work(stored) ->
            Verdict.OFFER_STRONGER

        else -> Verdict.KEEP
    }
}
