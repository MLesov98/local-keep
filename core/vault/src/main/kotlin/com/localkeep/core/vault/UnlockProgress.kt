package com.localkeep.core.vault

/**
 * A checkpoint passed while opening a vault, reported as it happens.
 *
 * ### Why this exists at all
 *
 * Unlocking takes tens of seconds — that is the Argon2id cost, and it is deliberate. Behind an
 * indeterminate spinner it is indistinguishable from a hang, and the app has already been reported
 * as broken during it. What it needs is a number, and the only number worth showing is one that
 * moves because work finished.
 *
 * ### The honesty constraint
 *
 * Every value here is a checkpoint the pipeline genuinely reaches. Nothing is interpolated and no
 * delay is added, so a small vault on a fast phone runs the whole sequence in a fraction of the time
 * a large one on a slow phone takes — the wait is never the same length twice, because it is the
 * work and not a timer. That is also why [fractionDone] is weighted rather than evenly spaced:
 * spacing five checkpoints at 20% each would say that reading the header costs as much as an Argon2
 * derivation, which is off by four orders of magnitude and would make the number decorative.
 *
 * ### The security constraint, which is the load-bearing one
 *
 * `VaultLockManager.scanPasswordSlots` runs **both** password slots to full completion before it
 * branches, so a wrong password costs exactly what a right one does and the duress slot costs
 * exactly what the real one does. Progress reporting is only safe because it preserves that:
 *
 * - Every checkpoint below [OPENING] is emitted **unconditionally**, from a point every unlock
 *   attempt reaches. The sequence, its length and its timing are identical for a right password, a
 *   wrong password and the duress password.
 * - **Nothing is ever emitted from a per-slot result** — not `unwrapValid`, not `commitmentValid`,
 *   not `SlotTrial.Match`. Firing on a slot's success would turn the label into a live password
 *   oracle, and one that answers earlier and more cheaply than the unlock result itself does.
 * - **No per-slot timing is exposed.** An aggregate fraction leaks nothing the wall clock did not
 *   already show; a "slot 0: 1.2s / slot 1: 1.4s" readout would distinguish the duress slot from the
 *   real one. Out of scope, deliberately, and this comment is where that decision lives.
 * - [OPENING] is the one checkpoint a wrong password never reaches. It fires after every
 *   timing-relevant operation is complete and after the session is published, at which point the
 *   unlock result is about to be returned to the caller anyway.
 *
 * A consumer must also drop this on the way back to a locked vault: leaving the last session's
 * progress on screen would let someone picking up a re-locked phone learn something without typing
 * anything. Passing the reporter per call rather than exposing a manager-wide flow is what makes
 * that easy — the callback dies with the call.
 */
enum class UnlockPhase {
    /** The vault file is being read off disk and its header decoded. Milliseconds. */
    READING,

    /** An Argon2id derivation is running. This is where nearly all of the wait lives. */
    DERIVING,

    /** Every derivation is done; the encrypted payload region is being decrypted and parsed. */
    DECRYPTING,

    /** The session is published and the vault is in memory. */
    OPENING,
}

/**
 * @param fractionDone how much of the unlock is finished, 0f..1f. Monotonically non-decreasing
 *   within a single [VaultLockManager.unlock] call.
 */
data class UnlockProgress(val phase: UnlockPhase, val fractionDone: Float)

/**
 * Where the weights come from.
 *
 * Argon2id is calibrated to run for on the order of ten seconds per derivation, and a vault is
 * always scanned against both of its slots, so the two derivations together are essentially the
 * whole wait. Reading the file and decoding its header are single-digit milliseconds. Decrypting and
 * parsing the payload is the only other part that varies — it scales with how much is in the vault,
 * which is exactly why the number dwells there on a large one and does not on a small one.
 *
 * These are proportions of the wait, not of the checkpoint count.
 */
internal object UnlockWeights {
    /** Done once the file is read and a header has decoded. */
    const val READ = 0.02f

    /** Done once every slot's Argon2id derivation has returned. */
    const val DERIVED = 0.60f

    /** Done once the payload region has decrypted and parsed. */
    const val DECRYPTED = 0.88f

    /** Fraction after [n] of [total] slot derivations, spread across [READ]..[DERIVED]. */
    fun afterDerivations(n: Int, total: Int): Float =
        READ + (DERIVED - READ) * (n.toFloat() / total.coerceAtLeast(1))
}
