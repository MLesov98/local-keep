package com.localkeep.core.vault

import android.content.Context
import android.os.SystemClock
import com.localkeep.core.common.Outcome
import com.localkeep.core.common.PendingSecret
import com.localkeep.core.crypto.AeadAuthException
import com.localkeep.core.crypto.ConstantTime
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.RevealPhraseVerifier
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultSchemaTooNewException
import java.time.Instant
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/** Outcome of [VaultLockManager.restoreVaultFile]. */
enum class RestoreOutcome {
    /** Restored and re-locked. */
    OK,

    /** Not a decodable vault file — nothing was written. */
    INVALID_FILE,

    /** Written under the current real DEK but its region fails to authenticate (corrupt/tampered) —
     *  nothing was written; the live vault is untouched. */
    CORRUPT,

    /**
     * A well-formed snapshot this build predates — either its container declares a
     * [UnsupportedVaultFormatException] format version, or (duress splice only) its payload stamps a
     * [VaultSchemaTooNewException] schema. Nothing was written; the live vault is untouched. Distinct
     * from [INVALID_FILE] and [CORRUPT] for the same reason [SlotTrial.PayloadTooNew] is split out of
     * unlock's blanket failure: the right next step is "update the app", and both of the other two
     * outcomes would instead send the user to delete this snapshot or try an older one.
     */
    NEWER_VERSION,
}

/** Outcome of [VaultLockManager.rewrapKdfParams]. */
enum class RewrapOutcome {
    OK,
    WRONG_PASSWORD,

    /**
     * A decoy vault is configured, so the two unlock slots cannot be moved to new KDF parameters
     * together — see [VaultLockManager.rewrapKdfParams]. Reported rather than silently skipped, but
     * the wording shown to the user must never name duress: the whole feature depends on an
     * observer not learning it exists.
     */
    DURESS_CONFIGURED,
}

/** Owns decrypted session state, unlock policy, throttling, and the only persistence seam. */
class VaultLockManager(
    context: Context,
    private val keystoreManager: KeystoreManager,
) {
    internal val context: Context = context.applicationContext ?: context

    private val operationMutex = Mutex()
    private val stateMonitor = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _lockState = MutableStateFlow<LockState>(LockState.Locked)
    val lockState: StateFlow<LockState> = _lockState.asStateFlow()
    private val _onSaved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onSaved: SharedFlow<Unit> = _onSaved.asSharedFlow()

    private var autoLockJob: Job? = null

    /** Ends the reveal session when its window runs out — see [scheduleRevealExpiryLocked]. */
    private var revealExpiryJob: Job? = null
    private var stateEpoch: Long = 0

    /**
     * How many operations are currently reading the session DEK through [withSessionDek]. Mutated
     * only under [stateMonitor]; a flow rather than a plain counter so [wipeSessionDek] can wait for
     * it to drain without blocking the thread that asked for the lock.
     */
    private val dekBorrows = MutableStateFlow(0)
    private var biometricBlockedUntilMasterUnlock = false

    /**
     * The session-only "hidden entries are revealed" flag. Owned here so that the one place that
     * locks and zeroizes the DEK also conceals, in the same critical section (see [lock]).
     */
    val revealSession = RevealSession()

    /**
     * How media blobs get re-keyed when the DEK rotates. Set once at process start by the
     * application, because `:core:vault` cannot depend on the module that owns the blob directory
     * — see [BlobReKeyStore]. Volatile rather than a constructor argument so the DI graph in this
     * module stays free of the feature layer; nothing here reads it except a rotation.
     */
    @Volatile
    private var blobReKeyStore: BlobReKeyStore? = null

    fun registerBlobReKeyStore(store: BlobReKeyStore) {
        blobReKeyStore = store
    }

    /**
     * Serializes the blocking Argon2 derivation of a reveal attempt, separately from [operationMutex]
     * so a reveal never contends with an unlock or a save. A UI debounce can invalidate an attempt's
     * *generation*, but it cannot interrupt Bouncy Castle; the generation check after derivation is
     * what makes a superseded attempt harmless.
     */
    private val revealMutex = Mutex()

    /**
     * Bumped on every new attempt request, every explicit invalidation, and every [lock]. An attempt
     * carrying a generation no longer equal to this one is stale: it still counts as a wrong guess if
     * it was wrong, but it can neither reveal nor reset the throttle. Guarded by [stateMonitor].
     */
    private var revealAttemptGeneration: Long = 0

    /** Wrong reveal attempts since the last success or lock; drives the backoff. Guarded by [stateMonitor]. */
    private var revealFailureCount: Int = 0

    /**
     * A throwaway floor verifier used to run a same-cost derivation when the real one is absent,
     * invalid, or this is a duress session — so an attempt is timing-indistinguishable from a wrong
     * guess whether or not a phrase exists and whether or not this is a decoy.
     */
    private val dummyRevealVerifier: RevealPhraseVerifier by lazy {
        RevealPhraseVerifier(
            salt = SecureRandomProvider.nextBytes(RevealPhraseVerifier.SALT_SIZE),
            memoryKib = KdfParams.FLOOR.memoryKib,
            iterations = KdfParams.FLOOR.iterations,
            parallelism = KdfParams.FLOOR.parallelism,
            hash = SecureRandomProvider.nextBytes(RevealPhraseVerifier.HASH_SIZE),
        )
    }

    /**
     * Failed-attempt backoff, tracked **per credential kind** — see [Credential].
     *
     * They used to share one counter, and that locked people out of their own vault: a few
     * cancelled fingerprint prompts armed the password's exponential backoff, so the correct master
     * password was then refused for a credential it had never been tried against. The user has no
     * way to understand that, and no way out except killing the app.
     *
     * Separate counters are also the correct security model. Throttling exists to make *guessing*
     * expensive, and a cancelled biometric prompt is not a guess at the password — it carries no
     * information about it at all. Android already rate-limits the fingerprint sensor itself, so
     * the biometric path is throttled here only as a backstop.
     *
     * Persisted, because it used to be two plain maps here and nothing else: `am force-stop` reset
     * the count and the deadline together, so five guesses and a force-stop bought five more at no
     * cost. See [UnlockThrottle].
     */
    private val throttle = UnlockThrottle(
        store = PrefsThrottleStore(this.context),
        maxFailedAttempts = MAX_FAILED_ATTEMPTS,
        maxBackoffMs = MAX_BACKOFF_MS,
    )

    /** Which secret an unlock attempt was made with. Each gets its own backoff. */
    private enum class Credential {
        /** Something the user knows: the master password, or the Recovery Kit mnemonic. */
        KNOWLEDGE,

        /** Something the device holds, released by the OS biometric prompt. */
        BIOMETRIC,
    }

    @Volatile
    private var configuredAutoLockTimeoutMs = DEFAULT_AUTO_LOCK_TIMEOUT_MS

    @Volatile
    private var configuredBackgroundGraceMs = AutoLockPreset.DEFAULT.backgroundGraceMs

    /** True while the app is not in the foreground; selects which timeout applies. */
    @Volatile
    private var inBackground = false

    /** Outstanding system pickers we launched ourselves. See [beginExternalPicker]. */
    private var externalPickerDepth = 0

    /** Idle timeout: the app is in front and untouched. Reset by [extendAutoLock]. */
    var autoLockTimeoutMs: Long
        get() = configuredAutoLockTimeoutMs
        set(value) {
            require(value >= 0) { "auto-lock timeout must be non-negative" }
            configuredAutoLockTimeoutMs = value
            synchronized(stateMonitor) {
                if (_lockState.value is LockState.Unlocked) scheduleAutoLockLocked()
            }
        }

    /**
     * Grace period once the app leaves the foreground (§10.2). Deliberately separate from — and
     * much shorter than — the idle timeout: an unattended phone is a different risk from a phone
     * the user is looking at.
     */
    var backgroundGraceMs: Long
        get() = configuredBackgroundGraceMs
        set(value) {
            require(value >= 0) { "background grace must be non-negative" }
            configuredBackgroundGraceMs = value
            synchronized(stateMonitor) {
                if (_lockState.value is LockState.Unlocked) scheduleAutoLockLocked()
            }
        }

    fun applyAutoLockPreset(preset: AutoLockPreset) {
        configuredAutoLockTimeoutMs = preset.idleMs
        configuredBackgroundGraceMs = preset.backgroundGraceMs
        synchronized(stateMonitor) {
            if (_lockState.value is LockState.Unlocked) scheduleAutoLockLocked()
        }
    }

    /**
     * The app left the foreground: switch to the (shorter) background grace timer.
     *
     * Not an immediate lock — hopping to an authenticator app or the browser and straight back is
     * ordinary use, and forcing a master password every time trains people toward weaker ones.
     */
    fun onEnterBackground() {
        synchronized(stateMonitor) {
            inBackground = true
            if (_lockState.value is LockState.Unlocked) scheduleAutoLockLocked()
        }
    }

    /**
     * Marks the start of a system picker *we* launched — the document picker, the print dialog,
     * the photo picker.
     *
     * These run in another process, so `ProcessLifecycleOwner` correctly reports that LocalKeep is
     * backgrounded, but the user has not left: they are halfway through an in-app task. Found by
     * running the import wizard, where browsing for the export file locked the vault before a file
     * could even be chosen, making import impossible on the shorter presets.
     *
     * The excursion **downgrades to the idle timer, it does not disable locking**. Someone who
     * opens the picker and walks away still gets locked — just on the longer timer, which is the
     * right trade because their own screen is what's in front. Must be paired with
     * [endExternalPicker] in the result callback, including on cancel.
     */
    fun beginExternalPicker() {
        synchronized(stateMonitor) {
            externalPickerDepth++
            if (_lockState.value is LockState.Unlocked) scheduleAutoLockLocked()
        }
    }

    fun endExternalPicker() {
        synchronized(stateMonitor) {
            if (externalPickerDepth > 0) externalPickerDepth--
            if (_lockState.value is LockState.Unlocked) scheduleAutoLockLocked()
        }
    }

    /** The app is in front again: back to the idle timer, counting from now. */
    fun onEnterForeground() {
        synchronized(stateMonitor) {
            inBackground = false
            if (_lockState.value is LockState.Unlocked) scheduleAutoLockLocked()
        }
    }

    /**
     * True while [panicLock] has disabled biometric unlock until the master password is entered.
     *
     * Public because the unlock screen has to ask: otherwise it offers a biometric button that
     * authenticates fine and then fails, and the failure is indistinguishable from a wrong
     * password. Read-only — nothing outside this class can clear the block.
     */
    val isBiometricUnlockBlocked: Boolean
        get() = synchronized(stateMonitor) { biometricBlockedUntilMasterUnlock }

    /**
     * Whether a panic lock has taken biometrics away from the vault's own step-up gates — the
     * Recovery Kit and the invisible items — until biometric unlock is set up again.
     *
     * [panicLock] already ends the unlock route by destroying the device-wrapped key. The gates were
     * a second door the same forced finger could open, onto the 24 words that reopen the vault for
     * good (QA 2026-10-03). Not [isBiometricUnlockBlocked]: that lifts at the next master unlock,
     * which is exactly when the gates become reachable. Persisted, for the reason [panicLock] gives
     * about its own in-memory block.
     */
    val isBiometricSuspendedByPanic: Boolean
        get() = panicPrefs.getBoolean(PANIC_SUSPENDED_KEY, false)

    private val panicPrefs by lazy { context.getSharedPreferences(PANIC_BIOMETRIC_PREFS, Context.MODE_PRIVATE) }

    /**
     * Consumes and wipes [password]. Both slots always receive Argon2, unwrap, commitment,
     * subkey, and payload-decrypt work before a result is published.
     *
     * **A memory failure is retried before it is reported.** An `OutOfMemoryError` during the
     * derivation says an allocation lost a race, not that this vault is too big for this phone —
     * and the two messages [memoryFailureResult] chooses between are both bad news on a screen
     * reached by someone who has just typed a password they believe in. One of them tells a
     * brand-new user to open their brand-new vault "on a phone with more memory". The observed
     * shape of the fault was always "fails once, opens on the next try" (API 29, 2026-09-08), so
     * the next try is what this does — the user never sees the first one. Argon2's block array is
     * unreachable the moment the frame unwinds, so the heap the first attempt could not find is
     * already back; [yield] hands the thread over first so whatever else was allocating alongside
     * it gets its turn.
     *
     * Nothing is published, written or counted against the backoff on the failing path, so a second
     * attempt is not a second guess: [unlockOnce] reaches its `OutOfMemoryError` handler only from
     * inside the derivation, before any session exists, and `recordFailure` is reached only by a
     * slot scan that completed. A vault that genuinely does not fit still ends on the same message,
     * one derivation later — and that derivation is the cheap kind, an allocation that fails at
     * once rather than a cost that runs.
     *
     * Consumes the password here rather than in [unlockOnce] for the obvious reason: the retry
     * needs it. Wiped whatever happens, including on cancellation mid-derivation.
     *
     * **Owns its dispatcher**, the way [commit] does, and for the same reason: so that no caller can
     * forget. Three of the four call sites wrapped this in `withContext(Dispatchers.Default)`
     * themselves and the fourth — `OnboardingViewModel.createVault`, the mandatory first-run path —
     * did not, so the two calibrated Argon2id slot derivations and the vault file read ran on
     * `Dispatchers.Main.immediate` and froze the progress indicator that had just been shown. A
     * requirement every call site has to remember is one a call site will eventually forget.
     *
     * The remaining `withContext` at the other three sites is now redundant. It is left alone
     * deliberately — nesting the same dispatcher costs nothing, and this fix should not touch three
     * files that were already correct.
     */
    suspend fun unlock(
        password: CharArray,
        /**
         * Called as each checkpoint is passed, on the calling coroutine's `Dispatchers.Default`
         * thread. Per call rather than a manager-wide flow, deliberately: this is a `@Singleton`
         * shared by four callers, and the comment at [unlockOnce]'s `expectedEpoch` read below
         * explains that another activity can legitimately be unlocking at the same time. A shared
         * stream would let one screen watch another caller's progress. A closure dies with the call.
         *
         * Read [UnlockProgress] before adding an emission point — the constraints there are what
         * keep this from becoming a password oracle.
         */
        onProgress: (UnlockProgress) -> Unit = {},
    ): UnlockResult = try {
        retryingMemoryFailures { unlockOnce(password, onProgress) }
    } finally {
        Zeroizer.wipe(password)
    }

    /**
     * One attempt at [unlock], and the owner of the `withContext` that doc describes. Does **not**
     * wipe [password] — the retry needs it, so [unlock] wipes it once, for all attempts.
     */
    private suspend fun unlockOnce(
        password: CharArray,
        onProgress: (UnlockProgress) -> Unit,
    ): UnlockResult {
        // The cost this vault actually asks for, remembered as soon as a header parses so the
        // OutOfMemoryError handler below can tell a vault that genuinely does not fit this device
        // from a process that merely has no room left right now. Null means no header was read
        // before the failure, in which case the handler keeps the older, more cautious answer.
        var attemptedMemoryKib: Int? = null
        return try {
            withContext(Dispatchers.Default) {
                operationMutex.withLock {
                    val expectedEpoch = synchronized(stateMonitor) {
                        // Answered, not thrown. Another activity sharing this manager — the credential
                        // provider, when an autofill or passkey request is served from another app — can
                        // legitimately have opened the vault since this screen was drawn.
                        if (_lockState.value !is LockState.Locked) {
                            return@withLock UnlockResult.ALREADY_UNLOCKED
                        }
                        stateEpoch
                    }
                    checkThrottle(Credential.KNOWLEDGE)
                    // An unreadable file is not a failed guess, so it does not count against the backoff:
                    // throttling a user whose vault file is damaged only delays them reaching the restore
                    // path, and slows no attacker down.
                    onProgress(UnlockProgress(UnlockPhase.READING, 0f))
                    val candidates = VaultLoader.loadCandidates(context)
                    // Nothing on disk parses. Before calling that corruption — which sends the user to
                    // the restore-a-backup path, and restoring an older backup over a merely *newer* vault
                    // destroys it — ask whether the file is simply from a build this one predates.
                    if (candidates.isEmpty()) {
                        return@withLock if (VaultLoader.unsupportedFormatVersion(context) != null) {
                            UnlockResult.VAULT_FROM_NEWER_VERSION
                        } else {
                            UnlockResult.VAULT_UNREADABLE
                        }
                    }

                    // Walks primary then `.bak`. Only ONE candidate is ever scanned for a genuinely wrong
                    // password — [ScanOutcome.CredentialMismatch] stops the loop — so a wrong guess still
                    // costs exactly two Argon2 derivations, not four. The backup is reached only when the
                    // primary proved the password correct and then failed to decrypt, which is corruption.
                    var sawCorruption = false
                    for ((index, fileBytes) in candidates.withIndex()) {
                        val header = try {
                            VaultHeaderCodec.decode(fileBytes)
                        } catch (_: IllegalArgumentException) {
                            continue
                        }
                        attemptedMemoryKib = header.slots.firstOrNull()?.kdfParams?.memoryKib
                        onProgress(UnlockProgress(UnlockPhase.READING, UnlockWeights.READ))
                        when (
                            val scan =
                                scanPasswordSlots(fileBytes, header, password, expectedEpoch, onProgress)
                        ) {
                            is ScanOutcome.Opened -> {
                                // Opened from the `.bak` because the primary was damaged. Promote the good
                                // bytes to `vault.enc` now: every later write (`commit`, Recovery Kit) reads
                                // the primary, and splicing this session's region into a corrupt container
                                // would carry the damage forward. Best effort — a failure here leaves the
                                // user with an open vault, which matters more than the repair.
                                // `index > 0` alone was not the right question. It means "not the
                                // first candidate", and the first candidate is the primary only while
                                // the primary is readable — with `vault.enc` missing entirely, the
                                // `.tmp` takes position 0 and the repair was skipped precisely when the
                                // vault existed nowhere else. Ask about the file instead of the
                                // position, so the rename-window survivor is promoted too.
                                if (index > 0 || !VaultLoader.hasValidPrimary(context)) {
                                    runCatching { AtomicVaultWriter.write(context, fileBytes) }
                                }
                                // The one checkpoint a wrong password never reaches, and it is safe
                                // because it fires after the session is already published — the result
                                // returned on the next line says the same thing.
                                onProgress(UnlockProgress(UnlockPhase.OPENING, UnlockWeights.DECRYPTED))
                                resetThrottle()
                                return@withLock scan.result
                            }
                            ScanOutcome.Interrupted -> return@withLock UnlockResult.INTERRUPTED
                            // Returned rather than folded into `sawCorruption`, and returned from the
                            // first candidate that says it: the password was proved right, the file is
                            // intact, and the only thing wrong is this build. The check above cannot
                            // reach this case — it reads the CONTAINER format version, which has never
                            // bumped past 1, while the payload schema is what actually moves. Trying
                            // the `.bak` would only find an older copy of the same vault and end on
                            // "restore a backup", which is what destroys it.
                            ScanOutcome.PayloadTooNew ->
                                return@withLock UnlockResult.VAULT_FROM_NEWER_VERSION
                            ScanOutcome.PayloadCorrupt -> {
                                // The password unwrapped a slot key and that key matched the slot's
                                // commitment: it IS the right password. The payload it should decrypt did
                                // not authenticate, so this container is damaged, not mistyped. Try the
                                // next one before giving up.
                                sawCorruption = true
                            }
                            ScanOutcome.CredentialMismatch -> {
                                if (sawCorruption) continue
                                recordFailure(Credential.KNOWLEDGE)
                                return@withLock UnlockResult.WRONG_CREDENTIAL
                            }
                        }
                    }
                    // Reported as unreadable, and deliberately NOT counted against the backoff. Telling
                    // someone whose file is damaged that their password is wrong sends them to retype it,
                    // fail, and conclude the vault is lost — while the throttle they are accumulating is
                    // the one thing standing between them and the restore path.
                    UnlockResult.VAULT_UNREADABLE
                }
            }
        } catch (_: OutOfMemoryError) {
            // The vault's own KDF cost does not fit this device's heap. Answered, not propagated:
            // letting it escape crashes the unlock screen, and a crash tells the user nothing about a
            // vault that is intact and a password that is very probably right. See
            // [UnlockResult.VAULT_NEEDS_MORE_MEMORY].
            //
            // Catching an Error is normally wrong and is deliberate here, on the same reasoning as
            // Argon2Calibrator.fits: the failed allocation is one structure inside the derivation and
            // becomes unreachable the moment it fails, so the heap recovers. Nothing is published to
            // the lock state on this path, so the vault stays closed.
            //
            // WHICH failure it was is a property of the vault, not of the exception. A vault whose own
            // cost is within the portable ceiling fits this device by construction — this build will not
            // create one above it — so an allocation failure opening it means the process is full, not
            // the phone, and the remedy is to close the app fully rather than to find another device.
            // Reporting the two identically told a user to replace a phone over a three-item vault that
            // phone had made (G9, 2026-09-11).
            memoryFailureResult(attemptedMemoryKib)
        }
    }

    /** What one container had to say about [password]. See the loop in [unlock]. */
    private sealed interface ScanOutcome {
        /** A slot opened and the session was published. */
        data class Opened(val result: UnlockResult) : ScanOutcome

        /** Neither slot authenticated: this really is the wrong password. */
        data object CredentialMismatch : ScanOutcome

        /** A slot authenticated but its payload region did not decrypt: the file is damaged. */
        data object PayloadCorrupt : ScanOutcome

        /**
         * A slot authenticated and its payload decrypted, but declares a schema this build does not
         * understand. Kept apart from [PayloadCorrupt] because the file is healthy and the remedy
         * is the opposite one.
         */
        data object PayloadTooNew : ScanOutcome

        /** A lock landed mid-derivation, so the session this belonged to is gone. */
        data object Interrupted : ScanOutcome
    }

    /**
     * Runs [password] against both slots of one container and publishes the session on a match.
     *
     * Both slots always receive the full Argon2, unwrap, commitment, subkey and payload-decrypt
     * work before anything is returned, so the duress slot costs the same as the real one.
     */
    private fun scanPasswordSlots(
        fileBytes: ByteArray,
        header: VaultHeader,
        password: CharArray,
        expectedEpoch: Long,
        onProgress: (UnlockProgress) -> Unit = {},
    ): ScanOutcome {
        // Emitted from the loop body and not from a trial's result: every slot reaches these points
        // whatever it decides, so the sequence is identical for the right password, the wrong one
        // and the duress one. See UnlockProgress for why that is the whole ballgame.
        val trials = Array(VaultHeader.NUM_SLOTS) { slot ->
            tryPasswordSlot(fileBytes, header, slot, password) { derived ->
                val done = slot + derived
                onProgress(
                    UnlockProgress(
                        // The last derivation finishing is what changes the sentence: from here on
                        // the only work left is the payload, and that is the part whose length
                        // depends on how much is in the vault.
                        phase = if (done < VaultHeader.NUM_SLOTS) {
                            UnlockPhase.DERIVING
                        } else {
                            UnlockPhase.DECRYPTING
                        },
                        fractionDone = UnlockWeights.afterDerivations(done, VaultHeader.NUM_SLOTS),
                    ),
                )
            }
        }
        try {
            val matchedIndex = when {
                trials[1] is SlotTrial.Match -> 1
                trials[0] is SlotTrial.Match -> 0
                // Too-new is asked first: it is a positive fact about an intact payload, while
                // "undecryptable" is what the other slot says about a region it does not key.
                else -> return if (trials.any { it is SlotTrial.PayloadTooNew }) {
                    ScanOutcome.PayloadTooNew
                } else if (trials.any { it is SlotTrial.PayloadUndecryptable }) {
                    ScanOutcome.PayloadCorrupt
                } else {
                    ScanOutcome.CredentialMismatch
                }
            }
            val chosen = trials[matchedIndex] as SlotTrial.Match
            trials.indices
                .filter { it != matchedIndex }
                .forEach { Zeroizer.wipe((trials[it] as? SlotTrial.Match)?.dek) }
            if (
                !publishUnlocked(
                    dek = chosen.dek,
                    vault = chosen.vault,
                    isDuress = matchedIndex == 1,
                    expectedEpoch = expectedEpoch,
                )
            ) {
                // The session changed underneath us (a lock landed mid-derivation). Not a bad
                // password — the user should simply try again.
                Zeroizer.wipe(chosen.dek)
                return ScanOutcome.Interrupted
            }
            return ScanOutcome.Opened(
                if (matchedIndex == 0) UnlockResult.REAL else UnlockResult.DURESS,
            )
        } finally {
            trials.forEach { trial ->
                val dek = (trial as? SlotTrial.Match)?.dek
                if (dek != null && (_lockState.value as? LockState.Unlocked)?.dek !== dek) {
                    Zeroizer.wipe(dek)
                }
            }
        }
    }

    /**
     * Uses an already authenticated biometric `Cipher.DECRYPT_MODE` to unwrap the device copy.
     */
    suspend fun unlockWithBiometric(cipher: Cipher): UnlockResult = operationMutex.withLock {
        val expectedEpoch = synchronized(stateMonitor) {
            if (_lockState.value !is LockState.Locked) {
                return@withLock UnlockResult.ALREADY_UNLOCKED
            }
            if (biometricBlockedUntilMasterUnlock) {
                return@withLock UnlockResult.BIOMETRIC_UNAVAILABLE
            }
            stateEpoch
        }
        if (!keystoreManager.isDeviceSecure()) return@withLock UnlockResult.BIOMETRIC_UNAVAILABLE
        checkThrottle(Credential.BIOMETRIC)

        val fileBytes = when (val loaded = VaultLoader.loadFileBytes(context)) {
            is Outcome.Success -> loaded.value
            is Outcome.Failure -> return@withLock UnlockResult.VAULT_UNREADABLE
        }
        val wrappedBio = DeviceKeyStore.load(context)
            ?: return@withLock UnlockResult.BIOMETRIC_UNAVAILABLE
        if (!ConstantTime.equals(cipher.iv ?: ByteArray(0), wrappedBio.nonce)) {
            recordFailure(Credential.BIOMETRIC)
            return@withLock UnlockResult.BIOMETRIC_UNAVAILABLE
        }

        var dek: ByteArray? = null
        var encKey: ByteArray? = null
        return@withLock try {
            dek = cipher.doFinal(wrappedBio.ciphertext)
            require(dek.size == CryptoConstants.KEY_LEN) { "biometric DEK must be 256 bits" }
            val header = VaultHeaderCodec.decode(fileBytes)
            if (!commitmentMatches(dek, header.slots[0].commitment)) {
                // The device key no longer opens this vault — the password was changed or the key
                // rotated since biometrics were enrolled. Re-enrolment, not a retry, is the fix.
                recordFailure(Credential.BIOMETRIC)
                UnlockResult.BIOMETRIC_UNAVAILABLE
            } else {
                encKey = VaultSubkeys.encKey(dek)
                val vault = VaultFileCodec.decryptRegion(fileBytes, encKey, 0).second
                if (publishUnlocked(dek, vault, isDuress = false, expectedEpoch)) {
                    dek = null
                    // Same repair the password path performs, for the same reason: with no
                    // `vault.enc` on disk, `loadFileBytes` opened the rename-window `.tmp` and the
                    // vault looks entirely healthy while the only container Auto Backup carries
                    // does not exist. Best effort — a failure here leaves the user unlocked.
                    if (!VaultLoader.hasValidPrimary(context)) {
                        runCatching { AtomicVaultWriter.write(context, fileBytes) }
                    }
                    resetThrottle()
                    UnlockResult.REAL
                } else {
                    UnlockResult.INTERRUPTED
                }
            }
        } catch (_: Exception) {
            // Includes the cancelled/dismissed prompt: the cipher was never completed, so
            // unwrapping the device key throws here.
            recordFailure(Credential.BIOMETRIC)
            UnlockResult.BIOMETRIC_UNAVAILABLE
        } finally {
            Zeroizer.wipe(dek)
            Zeroizer.wipe(encKey)
        }
    }

    /**
     * A Recovery Kit that has been generated and shown, but not yet made real.
     *
     * The gap between those two things is the whole reason this exists — see
     * [prepareRecoveryKit]. Held here rather than in a view-model so it cannot outlive the session
     * it belongs to: [clearPreparedRecoveryKit] runs on every lock.
     */
    private class PreparedKit(
        val wrappedDek: WrappedDek,
        val epoch: Long,
    )

    private var preparedKit: PreparedKit? = null

    /**
     * Generates a Recovery Kit and returns its words — WITHOUT touching the kit already in the
     * vault. Call [commitPreparedRecoveryKit] once the user has actually saved the words.
     *
     * This used to be one step: generate, write the new wrapped key into the container, return the
     * words. Since there is exactly one recovery block, writing it revokes the previous kit
     * immediately — so a user who tapped "Create a Recovery Kit" out of curiosity, saw 24 words,
     * and left the screen without exporting them had just destroyed the printed kit in their desk
     * drawer and received nothing in exchange. Nothing told them; nothing could, because from the
     * app's point of view it had succeeded. The failure only surfaces years later, at the one
     * moment the kit was for.
     *
     * Splitting it means the old kit stays valid until a new one is demonstrably in the user's
     * hands. If they walk away, they keep what they had.
     *
     * Refused in the duress session. A kit minted there would wrap the decoy's key and be
     * indistinguishable from a real one, which is the failure the whole duress feature exists to
     * prevent.
     */
    suspend fun prepareRecoveryKit(): String? = operationMutex.withLock {
        val snapshot = synchronized(stateMonitor) {
            val unlocked = _lockState.value as? LockState.Unlocked ?: return@withLock null
            if (unlocked.isDuress) return@withLock null
            CommitSnapshot(
                state = unlocked,
                dek = unlocked.dek.copyOf(),
                epoch = stateEpoch,
                operation = "prepare the Recovery Kit",
            )
        }
        try {
            val kit = RecoveryKit.generate(snapshot.dek)
            synchronized(stateMonitor) {
                preparedKit = PreparedKit(wrappedDek = kit.wrappedDek, epoch = snapshot.epoch)
            }
            kit.mnemonic
        } catch (_: Exception) {
            null
        } finally {
            Zeroizer.wipe(snapshot.dek)
        }
    }

    /**
     * Writes the prepared kit's wrapped key into the container, revoking the previous kit.
     *
     * Returns false when there is nothing prepared, when the session has changed since it was
     * prepared (a lock in between means those words were shown against a session that is gone), or
     * when the write fails — in every one of which the OLD kit is still the working one, which is
     * the safe outcome.
     *
     * Main-safe by construction, for the same reason [commit] is and on the same evidence: a
     * whole-file read, a whole-file copy, a write, an `fd.sync()` and a directory fsync, none of
     * which yields. All three production callers reach it from `RecoveryKitViewModel` through
     * `viewModelScope.launch`, whose dispatcher is `Main.immediate`, so finishing the Recovery Kit
     * flow ran every byte of that on the UI thread. Owning the dispatcher here rather than at each
     * call site is what keeps a future non-ViewModel caller safe too (2026-09-13).
     */
    suspend fun commitPreparedRecoveryKit(): Boolean = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val (pending, snapshot) = synchronized(stateMonitor) {
                val pending = preparedKit ?: return@withLock false
                val unlocked = _lockState.value as? LockState.Unlocked ?: return@withLock false
                if (unlocked.isDuress || pending.epoch != stateEpoch) return@withLock false
                pending to CommitSnapshot(
                    state = unlocked,
                    dek = unlocked.dek.copyOf(),
                    epoch = stateEpoch,
                    operation = "save the Recovery Kit",
                )
            }
            var encKey: ByteArray? = null
            try {
                val currentBytes = when (val loaded = VaultLoader.loadFileBytes(context)) {
                    is Outcome.Success -> loaded.value
                    is Outcome.Failure -> return@withLock false
                }
                // Splices the block in; no ciphertext is touched, which is what lets this work while a
                // duress vault is configured (resealing region 1 would need the duress password).
                val updated = VaultFileCodec.withRecoveryBlock(currentBytes, pending.wrappedDek)
                ensureSessionUnchanged(snapshot)
                AtomicVaultWriter.write(context, updated)
                // `vault.enc` is not the only container holding a recovery block. The write above
                // just moved the previous generation to `vault.enc.bak`, and `backups/` keeps the
                // newest five saves — each still carrying the block the user has just been told is
                // void, and wrapping the *same* DEK, because minting a kit rotates nothing. So the
                // retired 24 words went on unwrapping the live data key out of a file sitting beside
                // the live vault. Measured 2026-09-13.
                encKey = VaultSubkeys.encKey(snapshot.dek)
                StaleContainerSweeper.refreshRecoveryBlock(context, pending.wrappedDek, encKey)
                synchronized(stateMonitor) { preparedKit = null }
                _onSaved.tryEmit(Unit)
                true
            } catch (_: Exception) {
                false
            } finally {
                Zeroizer.wipe(encKey)
                Zeroizer.wipe(snapshot.dek)
            }
        }
    }

    /** Throws away a prepared-but-uncommitted kit. The vault's existing kit keeps working. */
    fun clearPreparedRecoveryKit() {
        synchronized(stateMonitor) { preparedKit = null }
    }

    /**
     * Whether [words] are *this vault's own* Recovery Kit — the check the Recovery-phrase item's save
     * refusal is built on (storing the vault's key inside the vault).
     *
     * Read-only and side-effect free: it opens nothing, changes no state, and — unlike
     * [unlockWithRecoveryKit] — is **not** throttled, because the caller is already unlocked and this
     * guards their own input rather than attempting access. Derives the recovery KEK from the words
     * and tests it against the vault file's recovery block via [VaultKitMatch].
     *
     * Fails open: an unreadable file, a vault with no recovery block, or a phrase that is not 24
     * valid words all return false, so the refusal never blocks a save it cannot justify.
     */
    suspend fun matchesOwnRecoveryKit(words: List<String>): Boolean {
        val fileBytes = when (val loaded = VaultLoader.loadFileBytes(context)) {
            is Outcome.Success -> loaded.value
            is Outcome.Failure -> return false
        }
        val wrappedDek = VaultFileCodec.recoveryWrappedDek(fileBytes) ?: return false
        return VaultKitMatch.isVaultRecoveryKit(words, wrappedDek)
    }

    /**
     * Unlocks the real vault from an offline Recovery Kit.
     *
     * Takes the words alone. It used to also require the wrapped key, which lived only in the QR
     * code — so the PDF, the export this app pushes hardest, could not open anything. The wrapped
     * key now lives in the vault file.
     */
    suspend fun unlockWithRecoveryKit(
        mnemonic: String,
    ): UnlockResult = operationMutex.withLock {
        val expectedEpoch = synchronized(stateMonitor) {
            check(_lockState.value is LockState.Locked) { "vault is already unlocked" }
            stateEpoch
        }
        // The Recovery Kit is a knowledge factor like the master password, and shares its backoff:
        // both are guessable secrets typed by the user, so guesses at either must cost the same.
        checkThrottle(Credential.KNOWLEDGE)
        val fileBytes = when (val loaded = VaultLoader.loadFileBytes(context)) {
            is Outcome.Success -> loaded.value
            is Outcome.Failure -> return@withLock UnlockResult.VAULT_UNREADABLE
        }

        var recoveryKek: ByteArray? = null
        var dek: ByteArray? = null
        var encKey: ByteArray? = null
        return@withLock try {
            // Each step below says for itself whether its failure is a wrong guess or a damaged
            // file, because the exception types cannot: `IllegalArgumentException` is both "these
            // are not 24 valid words" and "this header will not decode", and `AeadAuthException` is
            // both "wrong words" and "the payload is corrupt". One catch-all around the lot — which
            // is what this used to be — answered every one of them with WRONG_CREDENTIAL, so a
            // damaged vault told the user to retype words that were right and charged them a
            // knowledge-domain backoff for it. Same rule as the password path: corruption is
            // reported as corruption and never counted against the throttle.

            // Read before the words are used at all, so a header that will not decode cannot be
            // mistaken for one — it falls to the catch at the bottom, which calls it damage.
            val header = VaultHeaderCodec.decode(fileBytes)
            // Absent block and wrong words are the same outcome on purpose: nothing the user can
            // do distinguishes "this vault has no kit" from "these are not its words", and nothing
            // an attacker holding the file should be able to either.
            val wrappedDek = VaultFileCodec.recoveryWrappedDek(fileBytes)
                ?: run {
                    recordFailure(Credential.KNOWLEDGE)
                    return@withLock UnlockResult.WRONG_CREDENTIAL
                }
            // The words themselves. This — and the commitment check below — is the only place a
            // failure means the user typed something wrong.
            val unwrapped = try {
                val kek = RecoveryKit.deriveRecoveryKek(mnemonic).also { recoveryKek = it }
                EnvelopeCrypto.unwrapDek(kek, wrappedDek)
            } catch (_: Exception) {
                recordFailure(Credential.KNOWLEDGE)
                return@withLock UnlockResult.WRONG_CREDENTIAL
            }
            dek = unwrapped
            if (!commitmentMatches(unwrapped, header.slots[0].commitment)) {
                recordFailure(Credential.KNOWLEDGE)
                UnlockResult.WRONG_CREDENTIAL
            } else {
                // Past the commitment the words are proven right, so nothing from here on can be
                // the user's fault: the key is correct and the payload it should open is not.
                val payloadKey = VaultSubkeys.encKey(unwrapped).also { encKey = it }
                val vault = try {
                    VaultFileCodec.decryptRegion(fileBytes, payloadKey, 0).second
                } catch (_: Exception) {
                    return@withLock UnlockResult.VAULT_UNREADABLE
                }
                if (
                    publishUnlocked(
                        unwrapped,
                        vault,
                        isDuress = false,
                        expectedEpoch,
                        openedWithRecoveryKit = true,
                    )
                ) {
                    dek = null
                    // The third route through `loadFileBytes` that publishes a session, and it has
                    // the same rename-window hole as the biometric one above. Lower risk, because a
                    // kit unlock is normally followed by a password reset that writes — but "normally"
                    // is not a guarantee, and leaving one known hole open is not cheaper than closing it.
                    if (!VaultLoader.hasValidPrimary(context)) {
                        runCatching { AtomicVaultWriter.write(context, fileBytes) }
                    }
                    resetThrottle()
                    UnlockResult.REAL
                } else {
                    UnlockResult.INTERRUPTED
                }
            }
        } catch (_: Exception) {
            // Nothing above is expected to reach here. Whatever did is not evidence that the words
            // were wrong, so it is not charged to the user as a failed guess.
            UnlockResult.VAULT_UNREADABLE
        } finally {
            Zeroizer.wipe(recoveryKek)
            Zeroizer.wipe(dek)
            Zeroizer.wipe(encKey)
        }
    }

    /**
     * Persists the current session's region without exposing or overwriting the other vault.
     *
     * Main-safe by construction. Every vault mutation in the app funnels through here, and the work
     * below is a whole-file read, an AEAD seal over the entire payload bucket, a write and an
     * `fd.sync()` — none of which yields. Most callers reach it from `viewModelScope.launch`, whose
     * dispatcher is Main, so saving an item ran all of that on the UI thread: janky on a small
     * vault, an ANR on a large one over slow storage. Owning the dispatcher here rather than at each
     * call site is what makes that true for the autofill Save-confirm path too, which has no
     * ViewModel to put a `withContext` in.
     */
    suspend fun commit(newVault: Vault) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val snapshot = synchronized(stateMonitor) {
                val unlocked = _lockState.value as? LockState.Unlocked
                    ?: error("vault is locked")
                CommitSnapshot(
                    state = unlocked,
                    dek = unlocked.dek.copyOf(),
                    epoch = stateEpoch,
                    operation = "save your changes",
                )
            }
            val stamped = newVault.copy(modifiedAt = Instant.now())
            var encKey: ByteArray? = null
            try {
                val currentBytes = when (val loaded = VaultLoader.loadFileBytes(context)) {
                    is Outcome.Success -> loaded.value
                    is Outcome.Failure -> error("vault file unavailable during save")
                }
                encKey = VaultSubkeys.encKey(snapshot.dek)
                // Scoped to the serialize-and-encrypt, and no wider. The whole vault becomes one new
                // byte array here — ~8 MiB at 10,000 items, several times that live while protobuf
                // builds it — and Android caps an ordinary app at `dalvik.vm.heapgrowthlimit`:
                // 192 MiB on the API 35 image, 48 MiB on API 28, this app's own minSdk. Measured
                // 2026-07-29: at 48 MiB the save dies growing an 8 MiB buffer with 2.6 MiB free, and
                // an `Error` off a save handler is a crash.
                //
                // Nothing on disk has been touched at this point — `AtomicVaultWriter.write` is
                // below, and writes a temp file then renames — so "the change was not saved and
                // everything stored is unchanged" is exactly true. That is why the catch stops
                // here: past the write, the same message would be a lie about the user's data.
                //
                // Catching an `Error` is normally wrong, and is deliberate here on the same
                // reasoning as `Argon2Calibrator.fits` and `unlock`: the failed allocation is this
                // one buffer, which becomes unreachable the moment the frame unwinds, so the heap
                // recovers. Nothing is published to the lock state on this path.
                val newFileBytes = try {
                    VaultFileCodec.commitSave(
                        fileBytes = currentBytes,
                        vault = stamped,
                        encKey = encKey,
                        isDuressSession = snapshot.state.isDuress,
                    )
                } catch (_: OutOfMemoryError) {
                    throw VaultTooLargeForDeviceException(
                        heapLimitMib = Runtime.getRuntime().maxMemory() / (1024 * 1024),
                    )
                }
                check(synchronized(stateMonitor) { stateEpoch == snapshot.epoch }) {
                    "vault locked during save"
                }
                AtomicVaultWriter.write(context, newFileBytes)
                synchronized(stateMonitor) {
                    if (
                        stateEpoch == snapshot.epoch &&
                        _lockState.value === snapshot.state
                    ) {
                        // `copy`, not a fresh Unlocked: this republishes the same session with a
                        // newer vault, so every other property of that session must survive. Built
                        // by hand once, which silently dropped `openedWithRecoveryKit` and made the
                        // Recovery-Kit note vanish on the user's first save (V5, 2026-09-12).
                        _lockState.value = snapshot.state.copy(vault = stamped)
                        scheduleAutoLockLocked()
                    }
                }
                // A concurrent lock may win after durable write. Keep state locked; save still succeeded.
                _onSaved.tryEmit(Unit)
            } finally {
                Zeroizer.wipe(snapshot.dek)
                Zeroizer.wipe(encKey)
            }
        }
    }

    /** Installs or replaces duress data. Consumes and wipes [duressPassword]. */
    internal suspend fun setupDuress(
        duressPassword: CharArray,
        decoyVault: Vault,
    ) = try {
        operationMutex.withLock {
            val snapshot = realSessionSnapshot("set up duress")
            var decoyDek: ByteArray? = null
            var realEncKey: ByteArray? = null
            var decoyEncKey: ByteArray? = null
            var duressKek: ByteArray? = null
            try {
                val currentBytes = loadCurrentFile()
                val header = VaultHeaderCodec.decode(currentBytes)
                // A typed refusal, so the caller can say which constraint was broken. A bare
                // `require` reached the UI as the generic "That didn't go through. Try again.",
                // which is advice that reproduces the failure.
                if (
                    passwordMatchesSlot(
                        password = duressPassword,
                        slot = header.slots[0],
                        expectedDek = snapshot.dek,
                    )
                ) {
                    throw DuressPasswordCollisionException()
                }

                decoyDek = EnvelopeCrypto.generateDek()
                realEncKey = VaultSubkeys.encKey(snapshot.dek)
                decoyEncKey = VaultSubkeys.encKey(decoyDek)
                val duressSalt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
                duressKek = guardKdfMemory { EnvelopeCrypto.deriveKek(
                    duressPassword,
                    duressSalt,
                    header.slots[0].kdfParams,
                ) }
                val duressSlot = UnlockSlot(
                    salt = duressSalt,
                    kdfParams = header.slots[0].kdfParams,
                    wrappedDek = EnvelopeCrypto.wrapDek(duressKek, decoyDek),
                    commitment = EnvelopeCrypto.slotCommitment(decoyDek),
                )
                val updatedHeader = header.copy(
                    slots = listOf(header.slots[0], duressSlot),
                )
                val updatedFile = VaultFileCodec.encryptBothRegions(
                    realVault = snapshot.state.vault,
                    encKeyReal = realEncKey,
                    decoyVault = decoyVault,
                    encKeyDecoy = decoyEncKey,
                    prevHeader = updatedHeader,
                    // The real DEK is unchanged here, so an existing kit still opens the real
                    // vault and must survive configuring a decoy.
                    recoveryBlock = VaultFileCodec.recoveryBlockOf(currentBytes),
                )
                ensureSessionUnchanged(snapshot)
                AtomicVaultWriter.write(context, updatedFile)
                _onSaved.tryEmit(Unit)
            } finally {
                Zeroizer.wipe(snapshot.dek)
                Zeroizer.wipe(decoyDek)
                Zeroizer.wipe(realEncKey)
                Zeroizer.wipe(decoyEncKey)
                Zeroizer.wipe(duressKek)
            }
        }
    } finally {
        Zeroizer.wipe(duressPassword)
    }

    /** Removes duress data by replacing slot 1 and region 1 with fresh random filler. */
    internal suspend fun removeDuress() = operationMutex.withLock {
        val snapshot = realSessionSnapshot("remove duress")
        var realEncKey: ByteArray? = null
        try {
            val currentBytes = loadCurrentFile()
            realEncKey = VaultSubkeys.encKey(snapshot.dek)
            val updatedFile = VaultFileCodec.encrypt(
                vault = snapshot.state.vault,
                encKey = realEncKey,
                prevHeader = VaultHeaderCodec.decode(currentBytes),
                // The real DEK is untouched by removing a decoy, so the kit still opens this
                // vault and must come across.
                recoveryBlock = VaultFileCodec.recoveryBlockOf(currentBytes),
            )
            ensureSessionUnchanged(snapshot)
            AtomicVaultWriter.write(context, updatedFile)
            // `vault.enc` is not the only container holding the decoy. The write above just moved
            // the previous generation — decoy region and live duress slot intact — to
            // `vault.enc.bak`, and `backups/` keeps the newest five saves, each carrying the same
            // pair. So a decoy the user has just been told is gone went on opening under the
            // retired duress password, out of a file sitting beside the live vault, until five
            // ordinary saves had pushed the last copy out of the ring. Measured 2026-09-13, sweep
            // `final-20260913-fable`, finding F38. Nothing is deleted — see the sweep's own doc.
            //
            // Journalled, because the walk is one file at a time after a live write that has
            // already committed: killed at file 3 of 9 it leaves some containers clean and some
            // not, and nothing would ever retry — the trigger was a one-shot user action. The
            // journal is written **after** the commit and deleted after the last file; see
            // [ContainerMaintenanceJournal.write] for why that order and not the other one.
            ContainerMaintenanceJournal.write(
                context,
                snapshot.dek,
                ContainerMaintenanceJournal.Pass.REMOVED_DECOY,
            )
            StaleContainerSweeper.sweepRemovedDecoy(context, realEncKey)
            ContainerMaintenanceJournal.clear(context)
            _onSaved.tryEmit(Unit)
        } finally {
            Zeroizer.wipe(snapshot.dek)
            Zeroizer.wipe(realEncKey)
        }
    }

    /**
     * Detects duress only after real unlock: filler authenticates with the real key; a decoy does
     * not. No plaintext header marker is stored.
     */
    internal suspend fun isDuressConfigured(): Boolean = operationMutex.withLock {
        val snapshot = realSessionSnapshot("inspect duress")
        var realEncKey: ByteArray? = null
        try {
            realEncKey = VaultSubkeys.encKey(snapshot.dek)
            !VaultFileCodec.authenticatesRegion(
                fileBytes = loadCurrentFile(),
                encKey = realEncKey,
                regionIndex = 1,
            )
        } finally {
            Zeroizer.wipe(snapshot.dek)
            Zeroizer.wipe(realEncKey)
        }
    }

    /**
     * Re-derives slot 0 under [newParams], keeping the same password and the same DEK.
     * Consumes and wipes [currentPassword].
     *
     * This is the re-wrap design.md §11.1 asks for after a restore onto different hardware: the
     * Argon2id cost is chosen once, at creation, on whatever phone the user had then, and is then
     * carried in the header for the life of the vault. On a slower device that cost can make the
     * vault unusable; on a faster one it hands an offline attacker a discount the user cannot see.
     * [KdfRecalibration] decides whether either has happened.
     *
     * Deliberately **not** a rotation. Nothing is being retired here — the password is unchanged
     * and so is the key it protects — so the Recovery Kit stays valid, biometric unlock survives,
     * and the local snapshots go on opening under the same credential they always did. Rotating the
     * DEK to change a cost parameter would void all three for no security gain, and would make the
     * honest answer to "will this invalidate my Recovery Kit?" a yes.
     *
     * A fresh salt comes with the new parameters regardless: re-deriving a KEK from the same
     * password and the same salt under different costs is free to precompute against, and a salt is
     * cheap.
     *
     * **Refuses while duress is configured**, returning [RewrapOutcome.DURESS_CONFIGURED]. The
     * header format requires both unlock slots to carry identical KDF parameters — that is what
     * stops an attacker reading the cost fields and telling the real slot from the decoy, or
     * telling that a decoy exists at all. Moving slot 0 to new parameters therefore means moving
     * slot 1 with it, and slot 1 can only be re-wrapped with the duress password, which this
     * session does not have and must not ask for: prompting for it would announce the feature to
     * whoever is watching. When no decoy is configured slot 1 is random filler, so it is simply
     * re-minted as noise at the new parameters and nothing is lost.
     *
     * A session opened with the *duress* password gets the same refusal, for the same reason: it is
     * a vault with two slots, and this one cannot re-wrap either of them. It matters that the answer
     * is identical to the real vault's, because a decoy that behaves differently is a decoy that
     * has been found.
     */
    internal suspend fun rewrapKdfParams(
        currentPassword: CharArray,
        newParams: KdfParams,
    ): RewrapOutcome = try {
        operationMutex.withLock {
            // A decoy session refuses for the same reason a real one with a decoy configured does,
            // and it has to say so the same way. `realSessionSnapshot` throws here, and the backup
            // screen renders a thrown IllegalStateException as the generic "that didn't go through,
            // try again" — an invitation to retry something that can never succeed, and a different
            // answer from the one the real vault gives on the one screen where the two must not be
            // distinguishable. Returning the typed outcome routes it to the same wording, which
            // names no feature.
            //
            // What this does not equalise is timing: the real vault's refusal arrives after two
            // Argon2id derivations, this one arrives at once. That gap is unchanged by this fix
            // (the throw was instant too) and closing it would mean deriving against the decoy's
            // own slot to verify a password whose only use is to reproduce a delay.
            if ((_lockState.value as? LockState.Unlocked)?.isDuress == true) {
                return@withLock RewrapOutcome.DURESS_CONFIGURED
            }
            val snapshot = realSessionSnapshot("update the encryption settings")
            var currentKek: ByteArray? = null
            var verifiedDek: ByteArray? = null
            var newKek: ByteArray? = null
            try {
                checkThrottle(Credential.KNOWLEDGE)
                val currentBytes = loadCurrentFile()
                val header = VaultHeaderCodec.decode(currentBytes)
                try {
                    currentKek = guardKdfMemory { EnvelopeCrypto.deriveKek(
                        currentPassword,
                        header.slots[0].salt,
                        header.slots[0].kdfParams,
                    ) }
                    verifiedDek = EnvelopeCrypto.unwrapDek(currentKek, header.slots[0].wrappedDek)
                } catch (_: AeadAuthException) {
                    recordFailure(Credential.KNOWLEDGE)
                    return@withLock RewrapOutcome.WRONG_PASSWORD
                }
                // Same two checks the rotation paths make: the unwrapped key has to match its own
                // commitment *and* the key this session is already holding, so a header swapped
                // underneath us cannot get a different vault's key wrapped under this password.
                if (
                    !commitmentMatches(verifiedDek, header.slots[0].commitment) ||
                    !ConstantTime.equals(verifiedDek, snapshot.dek)
                ) {
                    recordFailure(Credential.KNOWLEDGE)
                    return@withLock RewrapOutcome.WRONG_PASSWORD
                }
                // Slot 1 has to move to the same parameters (see the KDoc), and it can only be
                // re-wrapped with the duress password. Detected the same way isDuressConfigured
                // does it: with no decoy, region 1 is filler that the *real* key authenticates.
                val realEncKey = VaultSubkeys.encKey(verifiedDek)
                val duressConfigured = try {
                    !VaultFileCodec.authenticatesRegion(currentBytes, realEncKey, regionIndex = 1)
                } finally {
                    Zeroizer.wipe(realEncKey)
                }
                if (duressConfigured) return@withLock RewrapOutcome.DURESS_CONFIGURED

                val newSalt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
                newKek = guardKdfMemory { EnvelopeCrypto.deriveKek(currentPassword, newSalt, newParams) }
                val updatedHeader = header.copy(
                    slots = listOf(
                        header.slots[0].copy(
                            salt = newSalt,
                            kdfParams = newParams,
                            wrappedDek = EnvelopeCrypto.wrapDek(newKek, verifiedDek),
                            // The DEK is unchanged, so its commitment is too — recomputed rather
                            // than copied so a future change to either cannot drift apart silently.
                            commitment = EnvelopeCrypto.slotCommitment(verifiedDek),
                        ),
                        // Re-minted rather than copied: with no decoy configured this slot is
                        // random filler, and filler that kept the old parameters while slot 0
                        // moved would both fail the header's parity rule and, worse, advertise
                        // which slot is real to anyone reading the cost fields.
                        VaultHeaderCodec.randomUnusedSlot(newParams),
                    ),
                )
                // Header-only edit: the payload regions are untouched, and their AAD excludes the
                // slots (see VaultHeaderCodec.payloadAad), so nothing needs resealing.
                val updatedFile = currentBytes.copyOf().also {
                    VaultHeaderCodec.encode(updatedHeader).copyInto(it)
                }
                ensureSessionUnchanged(snapshot)
                AtomicVaultWriter.write(context, updatedFile)
                resetThrottle()
                _onSaved.tryEmit(Unit)
                RewrapOutcome.OK
            } finally {
                Zeroizer.wipe(snapshot.dek)
                Zeroizer.wipe(currentKek)
                Zeroizer.wipe(verifiedDek)
                Zeroizer.wipe(newKek)
            }
        }
    } finally {
        Zeroizer.wipe(currentPassword)
    }

    /**
     * Changes the master password. Consumes and wipes both password arrays.
     *
     * Rotates the DEK rather than only re-wrapping it under a new KEK. Re-wrapping alone leaves
     * every *other* route to the same key intact — above all the Recovery Kit, whose block wraps
     * the DEK under the words and has nothing to do with the password. A user changing the password
     * because it (or the printed kit) leaked would have kept a live 24-word bypass, and been told
     * nothing about it. Rotating retires the old key, so the old kit and the old device-wrapped copy
     * both stop opening this vault; [ChangeResult.oldRecoveryKitVoided] is what tells the user a new
     * kit is needed, and biometric unlock has to be enrolled again.
     */
    internal suspend fun changeMasterPassword(
        currentPassword: CharArray,
        newPassword: CharArray,
    ): ChangeResult = try {
        operationMutex.withLock {
            val snapshot = realSessionSnapshot("change the master password")
            var newKek: ByteArray? = null
            try {
                checkThrottle(Credential.KNOWLEDGE)
                val currentBytes = loadCurrentFile()
                val header = VaultHeaderCodec.decode(currentBytes)
                if (
                    !passwordMatchesSlot(
                        password = currentPassword,
                        slot = header.slots[0],
                        expectedDek = snapshot.dek,
                    )
                ) {
                    recordFailure(Credential.KNOWLEDGE)
                    return@withLock ChangeResult(success = false)
                }
                require(!passwordUnlocksSlot(newPassword, header.slots[1])) {
                    "new master password must differ from the duress password"
                }

                val newSalt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
                newKek = guardKdfMemory { EnvelopeCrypto.deriveKek(
                    newPassword,
                    newSalt,
                    header.slots[0].kdfParams,
                ) }
                rotateDekUnderSlotZero(
                    snapshot = snapshot,
                    currentBytes = currentBytes,
                    header = header,
                    slotSalt = newSalt,
                    slotKek = newKek,
                    biometricCipher = null,
                )
            } finally {
                Zeroizer.wipe(snapshot.dek)
                Zeroizer.wipe(newKek)
            }
        }
    } finally {
        Zeroizer.wipe(currentPassword)
        Zeroizer.wipe(newPassword)
    }

    /**
     * The decoy session's own "change master password": re-wraps the **decoy** data key under a new
     * password, in slot 1, leaving slot 0 and both payload regions untouched byte for byte.
     *
     * **Why this exists.** Settings withheld the Change-master-password row entirely in a decoy
     * session, because the form was reachable and could not work — [changeMasterPassword] goes
     * through [realSessionSnapshot], so submitting the duress password as the current one answered
     * with the generic "That didn't go through. Try again.", an outcome a healthy real vault never
     * produces. Withholding the row traded that tell for a quieter one: a coercer scrolling Settings
     * found four SECURITY rows missing and could tell the two sessions apart with no duress-specific
     * knowledge at all (release sweep 2026-09-20, 3A-F23). The row is back, and this is what it
     * calls there.
     *
     * **Why it is only a header write.** `VaultHeaderCodec.payloadAad` projects the format prefix
     * and both region descriptors into the AAD, and deliberately excludes the unlock slots — the
     * property that already lets a real password change avoid decrypting region 1 under a duress
     * password it does not have. The same property is what makes this safe in the other direction:
     * changing which password unwraps the decoy key touches no ciphertext, so the real vault's
     * region is copied across unread, and slot 0 is carried over exactly as it was found.
     *
     * **What it deliberately does not do:**
     * - **It does not rotate the decoy key.** The commitment is therefore unchanged, and a
     *   re-wrap is the whole operation. Nothing else in the container is keyed to the decoy
     *   password.
     * - **It does not check the new password against slot 0.** [changeMasterPassword] refuses a new
     *   master password that would collide with the duress slot, and the mirror of that check here
     *   would be an oracle: it would answer, inside a decoy session, whether a guessed password
     *   opens the real vault. A collision costs nothing anyway — slot 0 is tried first at unlock,
     *   so the real vault simply wins and the decoy becomes unreachable by that password.
     * - **It does not sweep the stale containers.** `vault.enc.bak` and the snapshot ring carry the
     *   *real* vault's region 0, and a decoy session has no business rewriting a file whose contents
     *   it cannot read. The retired duress password therefore still opens a decoy out of a stale
     *   container, exactly as the retired *master* password does before [StaleContainerSweeper]
     *   runs — and unlock only ever reads `vault.enc`.
     *
     * Consumes and wipes both arrays. Refused outside a duress session by [duressSessionSnapshot],
     * which is the mirror of the guard on every real-vault operation in this class.
     */
    internal suspend fun changeDuressPassword(
        currentPassword: CharArray,
        newPassword: CharArray,
    ): ChangeResult = try {
        operationMutex.withLock {
            val snapshot = duressSessionSnapshot("change the master password")
            var newKek: ByteArray? = null
            try {
                // The same backoff the real change shares: this is one more place a password can be
                // guessed at, and the decoy's is a password too.
                checkThrottle(Credential.KNOWLEDGE)
                val currentBytes = loadCurrentFile()
                val header = VaultHeaderCodec.decode(currentBytes)
                if (
                    !passwordMatchesSlot(
                        password = currentPassword,
                        slot = header.slots[1],
                        expectedDek = snapshot.dek,
                    )
                ) {
                    recordFailure(Credential.KNOWLEDGE)
                    return@withLock ChangeResult(success = false)
                }

                val newSalt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
                // Slot 1's own KDF parameters, which `validateSlotKdfParity` requires to equal slot
                // 0's. Taking them from slot 1 rather than slot 0 keeps the read inside the slot
                // this session owns, and the parity check below would reject any drift anyway.
                newKek = guardKdfMemory {
                    EnvelopeCrypto.deriveKek(newPassword, newSalt, header.slots[1].kdfParams)
                }
                // The splice itself lives in [DuressSlotRewrap] so the "nothing outside slot 1
                // moves" invariant can be asserted on the bytes by a plain-JVM test.
                val updatedFile = DuressSlotRewrap.rewrap(
                    fileBytes = currentBytes,
                    newSalt = newSalt,
                    newKek = newKek,
                    decoyDek = snapshot.dek,
                )
                ensureSessionUnchanged(snapshot)
                AtomicVaultWriter.write(context, updatedFile)
                _onSaved.tryEmit(Unit)
                ChangeResult(success = true)
            } finally {
                Zeroizer.wipe(snapshot.dek)
                Zeroizer.wipe(newKek)
            }
        }
    } finally {
        Zeroizer.wipe(currentPassword)
        Zeroizer.wipe(newPassword)
    }

    /**
     * Sets a new master password with the Recovery Kit as the proof, for the user who has forgotten
     * the old one. Consumes and wipes [newPassword].
     *
     * The Kit could open the vault and could not give it back. [changeMasterPassword] asks for the
     * current password, which is by definition the one thing a recovered user does not have, so a
     * recovery unlock granted read access tied to a sheet of paper forever: every cold start needed
     * the 24 words again, and there was no route to anything else. The design calls the Kit "the
     * only forgotten-master-password recovery path" (§12.5, MUST), and recovery that cannot restore
     * password unlock does not meet that. Found by the F14 QA persona, 2026-08-30.
     *
     * **The words are re-proved here rather than inherited from the unlock.** Marking the session
     * as recovery-established and waiving the password check would have been a much smaller change
     * and a worse one: authority would then last as long as the session, so anyone reaching an
     * already-recovered phone could re-key the vault. Asking for the words at the moment of the
     * change costs the user one retype and keeps the proof contemporaneous with the act.
     *
     * Three properties it inherits deliberately, by going through the same tail as the password
     * change:
     * - **The DEK rotates**, so this kit stops working the moment the new password is set. That is
     *   the correct end state — the user is back on a password and needs a fresh kit — and
     *   [ChangeResult.oldRecoveryKitVoided] is what says so.
     * - **The decoy slot is untouched**, and a new password that would collide with it is refused.
     * - **Local backup copies and blobs are re-keyed with the vault**, so nothing is left opening
     *   under the retired key.
     *
     * Refused outside a real unlocked session by [realSessionSnapshot]: the words must open the
     * vault that is currently open, checked by commitment *and* against the live DEK, so a kit for
     * some other vault cannot re-key this one and a decoy session cannot reach the real slot.
     */
    internal suspend fun setMasterPasswordWithRecoveryKit(
        mnemonic: String,
        newPassword: CharArray,
    ): ChangeResult = try {
        operationMutex.withLock {
            // In a decoy session this is a refusal, not an error. `realSessionSnapshot` below would
            // throw, and a bare IllegalStateException reaches Settings as "That didn't go through.
            // Try again." — an outcome a healthy real vault never produces, and therefore a tell
            // on the one screen a decoy session is now allowed to use (3A-F23). A refusal is also
            // simply the truth: there is one recovery block per container and it wraps the real
            // key, so no sequence of 24 words opens the decoy. The caller renders this as "Those
            // words didn't open this vault", which is what a real vault says to wrong words.
            if (synchronized(stateMonitor) {
                    (_lockState.value as? LockState.Unlocked)?.isDuress == true
                }
            ) {
                return@withLock ChangeResult(success = false)
            }
            // Deliberately the same literal the typed-old-password path uses. These strings are the
            // key into `vaultOperationRes` in :feature:settings, which had no row for a
            // Kit-specific one and so fell back to the generic "an operation was running" sentence
            // — on the one screen where the user has already forgotten their password and is
            // retyping 24 words from paper. Both flows do set a new master password, so the
            // existing localised phrase is true of this one; a new literal here needs a row there
            // and a `settings_op_*` string in all five catalogues before it can be used.
            val snapshot = realSessionSnapshot("change the master password")
            var recoveryKek: ByteArray? = null
            var verifiedDek: ByteArray? = null
            var newKek: ByteArray? = null
            try {
                // The same backoff the password change and the unlock screen share: the Kit is a
                // knowledge factor, and a guess at it must cost what a guess at the password costs.
                checkThrottle(Credential.KNOWLEDGE)
                val currentBytes = loadCurrentFile()
                val header = VaultHeaderCodec.decode(currentBytes)
                // Absent block and wrong words are one outcome, as they are on the unlock path:
                // nothing the user can do tells "this vault has no kit" from "these are not its
                // words", and nothing holding the file should be able to either.
                val wrappedDek = VaultFileCodec.recoveryWrappedDek(currentBytes)
                    ?: run {
                        recordFailure(Credential.KNOWLEDGE)
                        return@withLock ChangeResult(success = false)
                    }
                verifiedDek = try {
                    val kek = RecoveryKit.deriveRecoveryKek(mnemonic).also { recoveryKek = it }
                    EnvelopeCrypto.unwrapDek(kek, wrappedDek)
                } catch (_: Exception) {
                    recordFailure(Credential.KNOWLEDGE)
                    return@withLock ChangeResult(success = false)
                }
                // Both halves matter. The commitment says the words carry *a* valid key for this
                // file; the constant-time compare says it is the key this session is holding. Only
                // the second rules out re-keying the open vault with a kit belonging to another.
                if (
                    !commitmentMatches(verifiedDek, header.slots[0].commitment) ||
                    !ConstantTime.equals(verifiedDek, snapshot.dek)
                ) {
                    recordFailure(Credential.KNOWLEDGE)
                    return@withLock ChangeResult(success = false)
                }
                require(!passwordUnlocksSlot(newPassword, header.slots[1])) {
                    "new master password must differ from the duress password"
                }

                val newSalt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
                newKek = EnvelopeCrypto.deriveKek(
                    newPassword,
                    newSalt,
                    header.slots[0].kdfParams,
                )
                rotateDekUnderSlotZero(
                    snapshot = snapshot,
                    currentBytes = currentBytes,
                    header = header,
                    slotSalt = newSalt,
                    slotKek = newKek,
                    biometricCipher = null,
                )
            } finally {
                Zeroizer.wipe(snapshot.dek)
                Zeroizer.wipe(recoveryKek)
                Zeroizer.wipe(verifiedDek)
                Zeroizer.wipe(newKek)
            }
        }
    } finally {
        Zeroizer.wipe(newPassword)
    }

    /** Rotates the real DEK while preserving an independently keyed decoy region. */
    internal suspend fun rotateRealDek(
        currentPassword: CharArray,
        biometricCipher: Cipher?,
    ): ChangeResult = try {
        operationMutex.withLock {
            val snapshot = realSessionSnapshot("rotate the encryption key")
            var masterKek: ByteArray? = null
            var verifiedDek: ByteArray? = null
            try {
                checkThrottle(Credential.KNOWLEDGE)
                val currentBytes = loadCurrentFile()
                val header = VaultHeaderCodec.decode(currentBytes)
                try {
                    masterKek = EnvelopeCrypto.deriveKek(
                        currentPassword,
                        header.slots[0].salt,
                        header.slots[0].kdfParams,
                    )
                    verifiedDek = EnvelopeCrypto.unwrapDek(
                        masterKek,
                        header.slots[0].wrappedDek,
                    )
                } catch (_: AeadAuthException) {
                    recordFailure(Credential.KNOWLEDGE)
                    return@withLock ChangeResult(success = false)
                }
                if (
                    !commitmentMatches(verifiedDek, header.slots[0].commitment) ||
                    !ConstantTime.equals(verifiedDek, snapshot.dek)
                ) {
                    recordFailure(Credential.KNOWLEDGE)
                    return@withLock ChangeResult(success = false)
                }

                rotateDekUnderSlotZero(
                    snapshot = snapshot,
                    currentBytes = currentBytes,
                    header = header,
                    slotSalt = header.slots[0].salt,
                    slotKek = masterKek,
                    biometricCipher = biometricCipher,
                )
            } finally {
                Zeroizer.wipe(snapshot.dek)
                Zeroizer.wipe(masterKek)
                Zeroizer.wipe(verifiedDek)
            }
        }
    } finally {
        Zeroizer.wipe(currentPassword)
    }

    /**
     * Shared tail of [changeMasterPassword] and [rotateRealDek]: mints a fresh DEK, re-encrypts the
     * real region under it, wraps it into slot 0 with [slotKek]/[slotSalt], revokes any Recovery
     * Kit, and hands the new key to the live session.
     *
     * Callers must hold [operationMutex] and must already have proven that [slotKek] is the key of
     * the credential they verified against slot 0 — this writes without re-checking.
     */
    private fun rotateDekUnderSlotZero(
        snapshot: CommitSnapshot,
        currentBytes: ByteArray,
        header: VaultHeader,
        slotSalt: ByteArray,
        slotKek: ByteArray,
        biometricCipher: Cipher?,
    ): ChangeResult {
        var oldEncKey: ByteArray? = null
        var newDek: ByteArray? = null
        var newEncKey: ByteArray? = null
        // Declared out here so the wipe lands in the outer `finally`, below the blob pass rather
        // than above it. These keys must be read early — while the outgoing DEK still exists — and
        // are needed again at the very end, because every journal write the pass makes has to carry
        // them. It previously had a narrow `finally` of its own around the first journal write, and
        // that is a trap worth naming: handing the pass a list whose arrays have already been
        // zeroed is strictly worse than handing it nothing. Nothing loses the older generations;
        // zeroes persist a journal that looks recoverable, opens cleanly, and unwraps to a key that
        // decrypts none of the blobs it claims to own. The lifetime of the read and the lifetime of
        // the last use of it have to be the same scope.
        var carried: List<DekRotationJournal.Pending> = emptyList()
        try {
            oldEncKey = VaultSubkeys.encKey(snapshot.dek)
            val freshDek = EnvelopeCrypto.generateDek()
            newDek = freshDek
            newEncKey = VaultSubkeys.encKey(freshDek)
            // Every stored file, photo and attachment is encrypted under a stream key derived from
            // the DEK, so a rotation that rewrites only the container leaves all of them unreadable
            // — no error, no warning, and nothing to recover from. Enumerated before a byte is
            // written: if the pass cannot run at all, the rotation must not start either.
            val blobRefs = blobRefsOf(snapshot.state.vault)
            val reKeyStore = blobReKeyStore
            check(blobRefs.isEmpty() || reKeyStore != null) {
                "no BlobReKeyStore is registered - rotating the DEK would orphan stored files"
            }
            val updatedSlot = header.slots[0].copy(
                salt = slotSalt,
                wrappedDek = EnvelopeCrypto.wrapDek(slotKek, newDek),
                commitment = EnvelopeCrypto.slotCommitment(newDek),
            )
            val updatedHeader = header.copy(
                slots = listOf(updatedSlot, header.slots[1]),
            )
            val wrappedBio = biometricCipher?.let {
                WrappedDek(
                    nonce = it.iv,
                    ciphertext = it.doFinal(newDek),
                )
            }

            val noDuress = VaultFileCodec.authenticatesRegion(
                currentBytes,
                oldEncKey,
                regionIndex = 1,
            )
            // Whether there was a kit to revoke, asked of the bytes this rotation replaces — the
            // same read `restoreVaultFile` makes for the same flag. Reported as `true` regardless
            // until 2026-10-04, so every change after the first told the user "Your old Recovery
            // Kit no longer works" about a kit the previous change had already revoked, beside a
            // Settings row reading "Not saved yet". An unreadable block answers yes: of the two
            // wrong sentences, the one telling the user to make a new kit is the safe one.
            val hadRecoveryKit = runCatching { VaultFileCodec.recoveryWrappedDek(currentBytes) != null }
                .getOrDefault(true)
            // The DEK is rotating, so any existing Recovery Kit wraps a key that no longer opens
            // this vault. Both branches therefore write a fresh noise block: the kit is revoked
            // here, not left as a dud that fails at the moment someone is relying on it.
            // `oldRecoveryKitVoided` below is what tells the user.
            val updatedFile = if (noDuress) {
                VaultFileCodec.encrypt(
                    vault = snapshot.state.vault,
                    encKey = newEncKey,
                    prevHeader = updatedHeader,
                    recoveryBlock = VaultFileCodec.revokedRecoveryBlock(),
                )
            } else {
                VaultFileCodec.withoutRecoveryKit(
                    VaultFileCodec.replaceRegion(
                        fileBytes = currentBytes,
                        vault = snapshot.state.vault,
                        encKey = newEncKey,
                        regionIndex = 0,
                    ).also {
                        VaultHeaderCodec.encode(updatedHeader).copyInto(it)
                    },
                )
            }

            ensureSessionUnchanged(snapshot)
            // The journal goes down BEFORE the container, because the window it covers opens the
            // instant the container changes: from here until the last blob is re-keyed, the bytes
            // on disk need two keys to be readable. It records the retired one, wrapped under the
            // new one. See [DekRotationJournal].
            // Carried forward, never overwritten. A previous rotation that ended with blobs left
            // behind — a full disk, a damaged blob — left its retired key still needed by exactly
            // those blobs, and its entry is on disk wrapped under the DEK this rotation is about to
            // retire. Read it here, while that DEK is still held, and hand it to the pass so every
            // generation is re-wrapped under `freshDek` together with this rotation's own entry.
            // Writing only this rotation's entry destroyed the older key, and the blobs sealed
            // under it became permanently unreadable while the user was told nothing was lost.
            //
            // Read even when this rotation has no blobs of its own to do: the older generation's
            // debt survives regardless, and dropping it here is the same loss by another route.
            //
            // A journal that could not be *read* throws out of here, deliberately uncaught. The
            // write below is a whole-file replace, so treating an unread journal as "no carried
            // entries" erases the older generations one line later — the loss the paragraph above
            // describes, arrived at by a different route. This throw lands before
            // AtomicVaultWriter.write, so nothing has been committed: the vault, the old password
            // and the journal are all exactly as they were, and the change reports itself failed.
            carried = DekRotationJournal.read(context, snapshot.dek)
            if (blobRefs.isNotEmpty() || carried.isNotEmpty()) {
                DekRotationJournal.write(
                    context,
                    carried + listOfNotNull(
                        DekRotationJournal.Pending(snapshot.dek, blobRefs)
                            .takeIf { blobRefs.isNotEmpty() },
                    ),
                    freshDek,
                )
            }
            AtomicVaultWriter.write(context, updatedFile)
            // `vault.enc` is not the only container on disk. The write above just moved the
            // pre-rotation vault to `vault.enc.bak`, and `backups/` holds the newest five saves —
            // all of them still opening under the credential this rotation exists to retire. Every
            // one is re-keyed here, or destroyed if it cannot be; see StaleContainerSweeper for why
            // conversion beats deletion and why the header swap after sealing is sound.
            val sweep = StaleContainerSweeper.sweep(
                context = context,
                oldEncKey = oldEncKey,
                newEncKey = newEncKey,
                newSlots = updatedHeader.slots,
            )
            val transferred = synchronized(stateMonitor) {
                if (
                    stateEpoch == snapshot.epoch &&
                    _lockState.value === snapshot.state
                ) {
                    // Clearing `openedWithRecoveryKit` here is the point: this rotation is the
                    // user setting the new master password the note asked for, so the note has
                    // been answered and must not come back (V6, 2026-09-12).
                    _lockState.value =
                        snapshot.state.copy(dek = newDek, openedWithRecoveryKit = false)
                    scheduleAutoLockLocked()
                    true
                } else {
                    false
                }
            }
            if (transferred) {
                Zeroizer.wipe(snapshot.state.dek)
                newDek = null
            }
            try {
                if (wrappedBio == null) {
                    DeviceKeyStore.delete(context)
                } else {
                    DeviceKeyStore.save(context, wrappedBio)
                }
            } catch (_: Exception) {
                DeviceKeyStore.delete(context)
            }
            // After the container and the device-wrapped copy, so a kill at any point up to here
            // leaves a journal a later unlock can finish. Failures are counted, not thrown: a
            // rotation that has already committed must still report itself as done, and the
            // journal survives to retry whatever did not land.
            // `carried` goes in as [others]: the pass rewrites the journal after every blob, as a
            // whole-file replace, so anything not handed to it is erased — including the older
            // generations written a few dozen lines above. That put those generations' blobs beyond
            // recovery while the rotation reported itself clean. The empty-`blobRefs` case never had
            // the defect: it short-circuits above and makes no journal write at all.
            val reKeyed = if (blobRefs.isEmpty()) {
                BlobReKeyResult.NOTHING_TO_DO
            } else {
                runBlobReKeyPass(reKeyStore!!, snapshot.dek, freshDek, blobRefs, carried)
            }
            resetThrottle()
            _onSaved.tryEmit(Unit)
            return ChangeResult(
                success = true,
                oldRecoveryKitVoided = hadRecoveryKit,
                staleContainersReKeyed = sweep.converted,
                staleContainersDestroyed = sweep.destroyed.size,
                staleContainersLeftBehind = sweep.unreachable.size,
                blobsReKeyed = reKeyed.reKeyed,
                blobsLeftBehind = reKeyed.leftBehind,
            )
        } finally {
            carried.forEach { Zeroizer.wipe(it.oldDek) }
            Zeroizer.wipe(oldEncKey)
            Zeroizer.wipe(newDek)
            Zeroizer.wipe(newEncKey)
        }
    }

    private class BlobReKeyResult(
        val reKeyed: Int,
        val leftBehind: Int,
        // What this generation still owes when the pass ends. Carried out so a caller running
        // several generations in turn can rewrite the journal from live state rather than from the
        // ref lists it read before the first pass — stale lists would resurrect work already done.
        val remaining: List<String> = emptyList(),
    ) {
        companion object {
            val NOTHING_TO_DO = BlobReKeyResult(reKeyed = 0, leftBehind = 0)
        }
    }

    /**
     * Re-encrypts [refs] from [oldDek] to [newDek], rewriting the journal after each one.
     *
     * The journal shrinks rather than being written once, so a kill repeats at most one blob's
     * work — and, more importantly, never re-keys an already-re-keyed blob, which would encrypt
     * ciphertext under the new key and destroy it exactly as thoroughly as doing nothing would.
     *
     * A blob that throws is left in the journal and retried on a later unlock. It is not thrown
     * out of here: by this point the rotation has committed, so failing the call would report a
     * password change that did happen as one that did not.
     *
     * [newDek] is copied before a single blob is touched, and the copy is what every seal and every
     * journal write below uses. The rotation path hands in the array it has just installed with
     * `_lockState.value = ...copy(dek = newDek)`, while [lock] wipes that same array in place,
     * holding only `stateMonitor` and not `operationMutex`. A screen-off or an auto-lock part-way
     * through a long pass would therefore seal the rest of the user's media under all zeroes, and
     * nothing here would notice: AES-GCM does not object to a zero key, so the blobs would be
     * counted as re-keyed and the journal carrying the retired key deleted. This is the aliasing
     * hazard [sessionSnapshotOrNull] exists to answer.
     *
     * The copy protects **one call**, and that is the whole of what it protects — it says nothing
     * about the array the caller keeps holding across calls. [resumeBlobReKey] makes one call per
     * retired generation, so a lock between two of them left every later call copying zeroes, and a
     * copy of zeroes is still zeroes. A caller running more than one pass must therefore take its
     * own private copy of the session DEK for the span of all of them; this parameter does not do
     * it for them.
     */
    private fun runBlobReKeyPass(
        store: BlobReKeyStore,
        oldDek: ByteArray,
        newDek: ByteArray,
        refs: List<String>,
        // Entries for other retired generations, already wrapped under [newDek] on disk. They are
        // rewritten alongside this pass's own entry after every blob: a journal write that dropped
        // them would destroy the only surviving copy of a key some blob is still sealed under,
        // which is the whole failure this list exists to prevent.
        others: List<DekRotationJournal.Pending> = emptyList(),
    ): BlobReKeyResult {
        var reKeyed = 0
        val remaining = refs.toMutableList()
        val destination = newDek.copyOf()
        try {
            for (ref in refs) {
                runCatching {
                    // A ref the vault holds but the disk does not is not a failure — a restore that
                    // brought items without their blobs leaves exactly that — and there is nothing
                    // to re-key. Dropping it from the journal is what stops it being retried
                    // forever.
                    if (store.hasBlob(ref)) {
                        store.reKeyBlob(ref, oldDek, destination)
                        true
                    } else {
                        false
                    }
                }.onSuccess { didWork ->
                    if (didWork) reKeyed++
                    remaining.remove(ref)
                    // Swallowed, not thrown. `onSuccess`/`onFailure` run OUTSIDE the `runCatching`
                    // above, so a journal write that fails — a rename that does not finalize, or an
                    // IOException from a full disk — used to unwind a rotation that has already
                    // committed: the container was written, the session DEK transferred and the
                    // Recovery Kit revoked, and the settings screen still said "that didn't go
                    // through. Try again." The ref is provably not owed anything, so counting it in
                    // `leftBehind` would be a false report and would resurrect finished work on the
                    // next resume. Leaving the on-disk journal a superset is the correct outcome:
                    // `resumeBlobReKey` drops a ref it finds nothing to do for. Nothing from the
                    // failure is surfaced — see DekRotationJournal for why its text stays internal.
                    runCatching {
                        DekRotationJournal.write(
                            context,
                            others + DekRotationJournal.Pending(oldDek, remaining.toList()),
                            destination,
                        )
                    }
                }.onFailure {
                    // A blob that already opens under the new key was converted by an earlier run
                    // whose journal write never landed — the process died between `reKeyBlob`'s
                    // rename and the rewrite above. Re-keying it again would decrypt it under the
                    // retired key it is no longer sealed with, which is why it throws; nothing is
                    // owed for it, and leaving it listed meant the pass could never finish, so the
                    // journal carrying the retired DEK was retried at every unlock forever.
                    //
                    // Dropped only on that proof. There is deliberately no attempt counter: the
                    // journal holds the only surviving copy of the old key, so abandoning a ref
                    // because retries have not worked yet would turn a stalled-but-recoverable blob
                    // into a permanently unreadable one. This is the same bounded-abandonment rule
                    // as the `hasBlob` check above — drop when it is provable that there is nothing
                    // to do, never when it merely looks hopeless.
                    // `opensUnder` and the rewrite both inside the catch, for the reason spelled
                    // out on the success branch: neither may unwind a committed rotation.
                    runCatching {
                        if (store.opensUnder(ref, destination)) {
                            remaining.remove(ref)
                            DekRotationJournal.write(
                                context,
                                others + DekRotationJournal.Pending(oldDek, remaining.toList()),
                                destination,
                            )
                        }
                    }
                }
            }
        } finally {
            Zeroizer.wipe(destination)
        }
        return BlobReKeyResult(
            reKeyed = reKeyed,
            leftBehind = remaining.size,
            remaining = remaining.toList(),
        )
    }

    /**
     * Finishes a walk over the stale containers that a process death interrupted.
     *
     * The sibling of [resumeBlobReKey], on the same unlock-time hook and for the same reason: the
     * journal is sealed under a subkey of the real DEK, so a live real session is the only moment
     * it can be opened. Today one walk uses it — the decoy sweep `removeDuress` runs after its live
     * write (F38). Killed at file 3 of 9, that walk left some containers clean and some still
     * carrying the decoy, and nothing retried it.
     *
     * **Costs nothing when there is nothing to do.** No journal means one `isFile` and a return,
     * with no container read and no AEAD open — which matters because this runs at every single
     * unlock for the life of the install, while the state it repairs is produced by a process death
     * inside one rare operation.
     *
     * Refuses in a duress session before it reads anything: the decoy DEK derives a different
     * subkey, so the journal would not open anyway, but [ContainerMaintenanceJournal.readFrom]
     * *deletes* what it cannot open — and a decoy session must not be able to throw away a real
     * session's pending work.
     *
     * Returns true only when a walk actually ran.
     */
    suspend fun resumeContainerMaintenance(): Boolean = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val dek = synchronized(stateMonitor) {
                (_lockState.value as? LockState.Unlocked)?.takeUnless { it.isDuress }?.dek?.copyOf()
            } ?: return@withLock false
            var encKey: ByteArray? = null
            try {
                val pass = ContainerMaintenanceJournal.read(context, dek) ?: return@withLock false
                when (pass) {
                    ContainerMaintenanceJournal.Pass.REMOVED_DECOY -> {
                        val liveBytes = when (val loaded = VaultLoader.loadFileBytes(context)) {
                            is Outcome.Success -> loaded.value
                            // The journal stays: an unreadable live container is a state the next
                            // unlock may well not be in, and dropping the journal here would
                            // abandon the walk permanently over a transient failure.
                            is Outcome.Failure -> return@withLock false
                        }
                        encKey = VaultSubkeys.encKey(dek)
                        val outcome = StaleContainerSweeper.resumeRemovedDecoySweep(
                            liveBytes = liveBytes,
                            files = StaleContainerSweeper.staleContainers(context),
                            realEncKey = encKey,
                        )
                        if (outcome != StaleContainerSweeper.ResumeOutcome.UNKNOWN) {
                            ContainerMaintenanceJournal.clear(context)
                        }
                        outcome == StaleContainerSweeper.ResumeOutcome.SWEPT
                    }
                }
            } catch (_: Exception) {
                // Best effort, like every other unlock-time recovery pass: a tidy-up that fails must
                // not be what stops the user reaching their vault.
                false
            } finally {
                Zeroizer.wipe(encKey)
                Zeroizer.wipe(dek)
            }
        }
    }

    /**
     * Finishes a blob re-key pass that a process death interrupted, and reports how many blobs it
     * recovered. A no-op in the ordinary case, where no journal exists.
     *
     * Runs on unlock because that is the only moment the key exists: the journal is wrapped under
     * the DEK the rotation installed, so nothing but a live real session can open it. A duress
     * session is refused rather than skipped silently in [DekRotationJournal.read] — the decoy DEK
     * will not unwrap the journal, and read() deletes what it cannot open.
     */
    suspend fun resumeBlobReKey(): Int = operationMutex.withLock {
        val store = blobReKeyStore ?: return@withLock 0
        // One private copy of the session DEK for the whole resume, taken atomically with the lock
        // state — the [sessionSnapshotOrNull] pattern, applied here because this is a loop and not a
        // single call. Reading `unlocked.dek` fresh on each generation aliased the live array, which
        // [lock] zeroes in place under `stateMonitor` alone; `operationMutex` does not serialise it
        // and this takes no [withSessionDek] borrow, so the drain in [wipeSessionDek] is never armed
        // for it. A lock landing during generation N then had every later generation seal its blobs
        // *and* rewrite the journal under 32 zero bytes, with the counts staying green throughout.
        // The per-call copy inside [runBlobReKeyPass] only ever covered the generation in flight.
        val (isDuress, epoch, sessionDek) = synchronized(stateMonitor) {
            val unlocked = _lockState.value as? LockState.Unlocked ?: return@withLock 0
            Triple(unlocked.isDuress, stateEpoch, unlocked.dek.copyOf())
        }
        try {
            if (isDuress) return@withLock 0
            val pending = try {
                DekRotationJournal.read(context, sessionDek)
            } catch (_: DekRotationJournal.UnreadableException) {
                // Nothing recovered, and nothing destroyed: the journal stays on disk and the next
                // unlock tries again. This pass is best-effort by construction — it runs on every
                // Locked -> Unlocked transition — so a read that failed once is a retry, not a
                // reason to decide the retired keys are no longer owed anything.
                return@withLock 0
            }
            if (pending.isEmpty()) return@withLock 0
            try {
                // One pass per retired generation. A blob is sealed under whichever generation last
                // converted it, so a single (oldDek, refs) pass cannot speak for all of them. Oldest
                // first: a blob still owed by the oldest key is the one closest to unrecoverable.
                var reKeyed = 0
                val live = pending.toMutableList()
                for (index in pending.indices) {
                    // The copy above means a lock cannot corrupt what is written; this stops the
                    // work continuing on behalf of a session the user has already ended. Whatever
                    // is left stays in the journal and the next unlock picks it up.
                    val stillOurs = synchronized(stateMonitor) {
                        stateEpoch == epoch && _lockState.value is LockState.Unlocked
                    }
                    if (!stillOurs) break
                    val entry = live[index]
                    // Every other generation's entry is rewritten with each journal update this pass
                    // makes. Taken from `live`, not from `pending`, so an entry an earlier iteration
                    // already drained is carried at its current size rather than resurrected whole.
                    val others = live.filterIndexed { i, _ -> i != index }
                    val result = runBlobReKeyPass(store, entry.oldDek, sessionDek, entry.refs, others)
                    reKeyed += result.reKeyed
                    live[index] = DekRotationJournal.Pending(entry.oldDek, result.remaining)
                }
                reKeyed
            } finally {
                pending.forEach { Zeroizer.wipe(it.oldDek) }
            }
        } finally {
            Zeroizer.wipe(sessionDek)
        }
    }

    fun lock() {
        val prior = synchronized(stateMonitor) {
            stateEpoch++
            autoLockJob?.cancel()
            autoLockJob = null
            // A kit prepared but never saved dies with the session that showed it. The epoch check
            // in [commitPreparedRecoveryKit] would refuse it anyway; dropping the wrapped key here
            // means it does not sit in memory for the lifetime of the process either.
            preparedKit = null
            // Conceal in the SAME critical section that locks and zeroizes the DEK, before any
            // subscriber can observe Locked — a reveal session must never outlive the vault it
            // belonged to. Bumping the generation invalidates any in-flight reveal attempt from the
            // old epoch, and the failure counter resets with the session.
            revealExpiryJob?.cancel()
            revealExpiryJob = null
            revealSession.conceal()
            revealAttemptGeneration++
            revealFailureCount = 0
            // Same critical section, same reason as the conceal above: a generated password offered
            // to an editor that never opened it is plaintext this process is still holding, and it
            // was being dropped by an `appScope` collector on Dispatchers.Default that observes
            // Locked *after* the fact. So the DEK was zeroized, the app reported itself locked, and
            // the password was still reachable until a coroutine got scheduled. The window is small
            // and it is not the kind of window to leave open. The application-level collector stays
            // — it is idempotent, and it also covers [PendingReveal], which is not a secret.
            PendingSecret.clear()
            val current = _lockState.value
            _lockState.value = LockState.Locked
            current
        }
        if (prior is LockState.Unlocked) wipeSessionDek(prior.dek)
    }

    /**
     * Zeroes the session DEK, waiting out any borrow still reading it — see [withSessionDek].
     *
     * The wait is deliberately off the caller's thread and deliberately bounded. Auto-lock, the
     * screen-off receiver and the panic lock are security controls: [lock] publishes Locked and
     * returns at the same moment it always did, and this runs behind it. And a borrow that never
     * returns must not be able to keep the key alive, so after [DEK_BORROW_DRAIN_MS] the array is
     * zeroed regardless.
     *
     * **What that does and does not buy.** A read that finishes inside the bound — a frame decrypt,
     * the construction of a seekable reader, the small capped text and Office reads — now completes
     * against a live key instead of against zeroes. A read that does not, a full streaming export of
     * a large video above all, still has the key zeroed underneath it and still fails closed on an
     * AEAD tag mismatch, exactly as it did before borrows existed. That case is not fixed here and
     * is not meant to be: holding the vault open for the length of an arbitrary read is a worse
     * property than the one being repaired. The bound is where those two are traded off, and the
     * lock is prompt on both sides of it.
     *
     * The ordinary case takes neither path — with nothing in flight the wipe happens inline, on the
     * caller's thread, before [lock] returns.
     */
    private fun wipeSessionDek(dek: ByteArray) {
        // Safe to read outside the monitor: the state is already Locked, so [withSessionDek] can no
        // longer hand out a borrow and the count only falls from here. That is also what makes the
        // StateFlow's conflation harmless below — there is no 1 → 0 → 1 left for a collector to miss.
        if (dekBorrows.value == 0) {
            Zeroizer.wipe(dek)
            return
        }
        scope.launch {
            withTimeoutOrNull(DEK_BORROW_DRAIN_MS) { dekBorrows.first { it == 0 } }
            Zeroizer.wipe(dek)
        }
    }

    /**
     * Runs [block] with the live session DEK, holding [lock]'s zeroization off until it returns.
     *
     * [block] is handed the manager's own array, deliberately: a copy per reader is the obvious
     * alternative and it is the wrong one, because everything else here works to keep the number of
     * live copies of the key at one. What the borrow adds is that [lock] no longer zeroes that array
     * out from under a reader. The media viewer used to be given it outright and to hold it for as
     * long as it was on screen, so an auto-lock, a screen-off or a panic lock landing inside a frame
     * decrypt left the operation finishing against all zeroes.
     *
     * Returns null without running [block] when the vault is locked, or when [sessionToken] no
     * longer names the session it was taken from. A caller that authenticated before it borrowed —
     * the standalone viewer's reveal gate — passes its token and gets the epoch checked at every
     * use rather than once at the prompt.
     *
     * [block] runs outside [stateMonitor] so it may do I/O, and must not call back into the
     * manager's own locking API.
     */
    fun <T> withSessionDek(sessionToken: Long? = null, block: (ByteArray) -> T): T? {
        val dek = synchronized(stateMonitor) {
            val state = _lockState.value as? LockState.Unlocked ?: return null
            if (sessionToken != null && stateEpoch != sessionToken) return null
            dekBorrows.value += 1
            state.dek
        }
        return try {
            block(dek)
        } finally {
            synchronized(stateMonitor) { dekBorrows.value -= 1 }
        }
    }

    /**
     * A borrow handle for UI that outlives any single read — see [SessionKey]. Null when the vault
     * is locked, or when [sessionToken] is no longer the live session.
     */
    fun sessionKeyOrNull(sessionToken: Long? = null): SessionKey? = synchronized(stateMonitor) {
        if (_lockState.value !is LockState.Unlocked) return null
        if (sessionToken != null && stateEpoch != sessionToken) return null
        SessionKey(this, sessionToken)
    }

    private val revealFreeAttempts = 5
    private val revealBackoffSeconds = intArrayOf(2, 4, 8, 16, 30)

    /** Delay before the next reveal derivation, from the failure count. Call under [stateMonitor]. */
    private fun revealDelayMillisLocked(): Long {
        val over = revealFailureCount - revealFreeAttempts
        if (over < 0) return 0
        return revealBackoffSeconds[minOf(over, revealBackoffSeconds.size - 1)] * 1000L
    }

    /** A fresh attempt generation for the UI to carry into [attemptHiddenReveal]. */
    fun nextRevealAttemptGeneration(): Long = synchronized(stateMonitor) { ++revealAttemptGeneration }

    /** Marks [attemptGeneration] stale so an in-flight attempt carrying it can no longer reveal. */
    fun invalidateHiddenRevealAttempt(attemptGeneration: Long) {
        synchronized(stateMonitor) {
            if (revealAttemptGeneration == attemptGeneration) revealAttemptGeneration++
        }
    }

    /** Conceals the reveal session on demand ("Make invisible again now"), invalidating any in-flight attempt. */
    fun concealHiddenSession() {
        synchronized(stateMonitor) {
            revealExpiryJob?.cancel()
            revealExpiryJob = null
            revealSession.conceal()
            revealAttemptGeneration++
        }
    }

    /**
     * Arms the timer that ends the reveal session on its own. Call while holding [stateMonitor],
     * immediately after [RevealSession.reveal].
     *
     * The job re-checks the deadline instead of assuming it is still the current one. A cancel can
     * lose the race with a job already past its `delay`, and the cost of getting that wrong is a
     * stale timer concealing a session the user revealed a moment ago — so the check is against
     * [RevealSession.expiresAtElapsedMs], which a fresh reveal has already moved forward and a
     * conceal has already nulled. That makes the cancel above an optimisation rather than a
     * correctness requirement.
     */
    private fun scheduleRevealExpiryLocked() {
        revealExpiryJob?.cancel()
        revealExpiryJob = scope.launch {
            delay(RevealSession.WINDOW_MS)
            synchronized(stateMonitor) {
                val due = revealSession.expiresAtElapsedMs.value ?: return@synchronized
                if (SystemClock.elapsedRealtime() < due) return@synchronized
                revealSession.conceal()
                revealAttemptGeneration++
            }
        }
    }

    /**
     * The only path that turns a typed phrase into a revealed session. Owns verifier validation,
     * floor Argon2, constant-time comparison, throttling, and epoch/generation validation; the UI
     * owns only debounce, eligibility (zero visible results) and generation invalidation, and never
     * sees the stored verifier bytes.
     *
     * Every non-success returns [RevealAttemptOutcome.Rejected] at the same cost — a wrong phrase, a
     * duress session, an absent or invalid verifier, and a stale epoch/generation are
     * indistinguishable. Derivation, comparison, [candidate] wiping and wrong-attempt accounting run
     * to completion even if the caller cancels mid-attempt (Bouncy Castle cannot be interrupted, and a
     * cancelled `mapLatest` is not proof the KDF stopped); a superseded *success* is discarded by the
     * generation/epoch check, while a superseded *wrong* attempt still consumes the throttle.
     */
    suspend fun attemptHiddenReveal(
        candidate: CharArray,
        attemptGeneration: Long,
    ): RevealAttemptOutcome {
        try {
            val verifier: RevealPhraseVerifier?
            val expectedEpoch: Long
            val isDuress: Boolean
            val delayMs: Long
            synchronized(stateMonitor) {
                val state = _lockState.value
                if (state !is LockState.Unlocked) return RevealAttemptOutcome.Rejected
                verifier = state.vault.revealPhrase
                expectedEpoch = stateEpoch
                isDuress = state.isDuress
                delayMs = revealDelayMillisLocked()
            }
            if (delayMs > 0) delay(delayMs)
            // `Dispatchers.Default` for the same reason [unlock] carries its own: this is the
            // second Argon2id entry point on this class, and its only production caller schedules
            // it on `Dispatchers.Main.immediate`. `NonCancellable` is a Job element and carries no
            // ContinuationInterceptor, so `withContext(NonCancellable)` alone kept the floor-cost
            // derivation on the caller's thread. `NonCancellable` stays: the search-box caller
            // cancels the very job this runs in, and it is only the substituted Job that lets
            // execution reach the KDF at all.
            return withContext(Dispatchers.Default + NonCancellable) {
                val useReal = verifier != null && verifier.isValid && !isDuress
                val matched = revealMutex.withLock {
                    RevealPhraseGate.verify(candidate, if (useReal) verifier else dummyRevealVerifier)
                }
                val correct = matched && useReal
                synchronized(stateMonitor) {
                    val state = _lockState.value
                    val stillCurrent = revealAttemptGeneration == attemptGeneration &&
                        stateEpoch == expectedEpoch &&
                        state is LockState.Unlocked && !state.isDuress
                    when {
                        correct && stillCurrent -> {
                            revealFailureCount = 0
                            revealSession.reveal(
                                SystemClock.elapsedRealtime() + RevealSession.WINDOW_MS,
                            )
                            scheduleRevealExpiryLocked()
                            RevealAttemptOutcome.Revealed
                        }
                        else -> {
                            if (!correct) revealFailureCount++
                            RevealAttemptOutcome.Rejected
                        }
                    }
                }
            }
        } finally {
            Zeroizer.wipe(candidate)
        }
    }

    /**
     * The "someone is standing over me" lock: locks now, and stops biometric unlock working.
     *
     * The block used to be [biometricBlockedUntilMasterUnlock] alone — an in-memory field on a
     * process-scoped singleton, so it survived exactly as long as the process did. A coercer who
     * force-stops the app, or a low-memory kill in a pocket, restored the fingerprint route without
     * the master password ever being entered. Nothing on disk remembered anything: the only
     * persisted keys are the auto-lock preset, the cloud-backup flag and the Pro cache.
     *
     * So the device-wrapped copy of the DEK is destroyed here as well. That is what the functional
     * spec means by "panic lock / disable biometric" being one action, and it is durable by
     * construction rather than by a flag someone could clear. The cost is real and stated in the UI:
     * this is a one-way door, and biometric unlock has to be enrolled again afterwards.
     */
    fun panicLock() {
        synchronized(stateMonitor) {
            biometricBlockedUntilMasterUnlock = true
        }
        // Committed, not applied: the lock below may be followed by the process dying, and a
        // suspension that never reached disk is no suspension. See [isBiometricSuspendedByPanic].
        panicPrefs.edit().putBoolean(PANIC_SUSPENDED_KEY, true).commit()
        // Before the lock, not after: `lock()` publishes a state change that routes the UI away,
        // and the delete must not be left to race that.
        DeviceKeyStore.delete(context)
        lock()
    }

    /**
     * Restores [fileBytes] as the live vault file, then forces a re-lock. The whole operation runs
     * under [operationMutex] — the same guard every save takes — so a concurrent `commit`/duress/
     * rotate write cannot interleave and silently revert the restore (the TOCTOU that a direct
     * [AtomicVaultWriter.write] from outside the manager was exposed to).
     *
     * Validation before overwriting: [fileBytes] must decode as a vault header. If the session is
     * currently unlocked with the REAL key and the snapshot was written under that same DEK (its
     * slot-0 commitment matches the session DEK), its real region must also authenticate — a
     * same-DEK snapshot that fails AEAD is [RestoreOutcome.CORRUPT] and is NOT written, so a good
     * live vault can never be replaced by a corrupt snapshot. A container under a different DEK (no
     * commitment match) can't be AEAD-checked here and passes on structural validity alone. Rolling
     * snapshots are not such a container after a rotation: [StaleContainerSweeper] re-keys every one
     * to the new password, so a restored snapshot opens with the CURRENT master password. An
     * exported file restored through here keeps whatever password it was exported with.
     */
    suspend fun restoreVaultFile(fileBytes: ByteArray): RestoreOutcome = operationMutex.withLock {
        val header = try {
            VaultHeaderCodec.decode(fileBytes)
        } catch (_: UnsupportedVaultFormatException) {
            // A well-formed header this build does not understand — see [VaultLoader
            // .unsupportedFormatVersion], which already distinguishes this from a damaged header at
            // unlock-screen load time. restoreVaultFile used to fold it into the blanket catch below,
            // which reports it as INVALID_FILE — "not a valid vault file" — and that is the one claim
            // that is false here: the snapshot is a fine container from a future build.
            return@withLock RestoreOutcome.NEWER_VERSION
        } catch (_: Exception) {
            return@withLock RestoreOutcome.INVALID_FILE
        }
        // A decoy session restores its OWN region and nothing else.
        //
        // `AtomicVaultWriter.write` below replaces the whole container, and a snapshot is a byte
        // copy of a whole container - both payload regions. So from a duress session the ordinary
        // path rewrote region 0 as well, rolling the REAL vault back to whatever the chosen
        // snapshot held and detaching every item saved since. That is the one place in the app
        // where a decoy session could reach the real vault's bytes: the duress SAVE path is
        // deliberately region-addressed for exactly this reason (see the `isDuress ->
        // replaceRegion(..., regionIndex = 1)` branch in the save), and restore was the control
        // that had never been given the same treatment.
        //
        // It is an integrity hole, not a confidentiality one - the decoy session never holds the
        // real DEK, so nothing here can read region 0, only overwrite it. The sharp case is not
        // the coercer the duress vault exists for (out of scope by name in THREAT-MODEL.md) but
        // someone handed the decoy password who taps Restore innocently and silently rolls back a
        // vault they do not know is there.
        //
        // Splices rather than refuses, so a decoy session behaves like a real one: refusing
        // outright would be a repeatable decoy-only outcome, which is a tell. A snapshot whose
        // region 1 does not authenticate under the session key answers CORRUPT - an outcome a real
        // session can also produce - so that path is not a tell either.
        val duressDek = synchronized(stateMonitor) {
            (_lockState.value as? LockState.Unlocked)?.takeIf { it.isDuress }?.dek?.copyOf()
        }
        if (duressDek != null) {
            var encKey: ByteArray? = null
            try {
                encKey = VaultSubkeys.encKey(duressDek)
                if (!VaultFileCodec.authenticatesRegion(fileBytes, encKey, regionIndex = 1)) {
                    return@withLock RestoreOutcome.CORRUPT
                }
                val decoy = VaultFileCodec.decryptRegion(fileBytes, encKey, regionIndex = 1).second
                val currentBytes = (VaultLoader.loadFileBytes(context) as? Outcome.Success)?.value
                    ?: return@withLock RestoreOutcome.CORRUPT
                val spliced = VaultFileCodec.replaceRegion(
                    currentBytes,
                    decoy,
                    encKey,
                    regionIndex = 1,
                )
                AtomicVaultWriter.write(context, spliced)
            } catch (_: VaultSchemaTooNewException) {
                // The decoy region fully decodes here (unlike the real path's AEAD-only check
                // below), so a decoy payload stamped with a schema this build predates is reachable
                // and must not fall into the blanket CORRUPT below — same reasoning as the header
                // catch above, scoped to the one branch that decodes far enough to see it.
                return@withLock RestoreOutcome.NEWER_VERSION
            } catch (_: Exception) {
                return@withLock RestoreOutcome.CORRUPT
            } finally {
                Zeroizer.wipe(encKey)
                Zeroizer.wipe(duressDek)
            }
            // No Recovery Kit reconciliation on this path: `replaceRegion` leaves the container's
            // single kit block untouched, so the real kit neither changes nor needs forgetting -
            // and a decoy session must not be able to mark the real vault's kit as unsaved.
            lock()
            return@withLock RestoreOutcome.OK
        }
        val realDek = synchronized(stateMonitor) {
            (_lockState.value as? LockState.Unlocked)?.takeUnless { it.isDuress }?.dek?.copyOf()
        }
        if (realDek != null) {
            var encKey: ByteArray? = null
            try {
                if (commitmentMatches(realDek, header.slots[0].commitment)) {
                    encKey = VaultSubkeys.encKey(realDek)
                    if (!VaultFileCodec.authenticatesRegion(fileBytes, encKey, regionIndex = 0)) {
                        return@withLock RestoreOutcome.CORRUPT
                    }
                }
            } finally {
                Zeroizer.wipe(encKey)
                Zeroizer.wipe(realDek)
            }
        }
        // Read before the overwrite, or there is nothing left to compare against. A container holds
        // exactly one Recovery Kit block, and `commitPreparedRecoveryKit` revokes the previous kit
        // when it splices a new one in — so a snapshot from before that commit carries a block the
        // user's printed words no longer unwrap. `recovery_kit_saved` is a device-local preference,
        // not a fact about the file, so nothing else notices; the Settings row keeps asserting a
        // working kit for the only forgotten-master-password route there is.
        //
        // Compared rather than cleared unconditionally: snapshots are taken on `onSaved` and the
        // commit emits it, so the newest snapshot does hold the current kit and restoring that one
        // must not manufacture a no-kit debt. An unreadable current container answers "different",
        // which is the direction `isRecoveryKitSaved`'s default already errs in.
        val previousKit = runCatching {
            val current = VaultLoader.loadFileBytes(context) as? Outcome.Success
                ?: return@runCatching null
            VaultFileCodec.recoveryWrappedDek(current.value)
        }
        val restoredKit = runCatching { VaultFileCodec.recoveryWrappedDek(fileBytes) }
        AtomicVaultWriter.write(context, fileBytes)
        val sameKit = previousKit.isSuccess && restoredKit.isSuccess &&
            previousKit.getOrNull() == restoredKit.getOrNull()
        if (!sameKit) {
            LocalKeepSettings.forgetRecoveryKitSaved(context)
        }
        lock()
        RestoreOutcome.OK
    }

    /**
     * The hidden-items management gate's knowledge-factor fallback (Decision 10/15): verifies the
     * *current* vault password — slot 0 in a normal session, only the decoy slot 1 in duress — so a
     * decoy screen never solicits or accepts the real password. A thin alias over [reauthenticate],
     * which already selects the slot by session; named for the call site so the intent is legible.
     */
    suspend fun reauthenticateForCurrentVault(password: CharArray): Boolean = reauthenticate(password)

    /**
     * Consumes and wipes [password], verifying the slot belonging to the current session.
     *
     * Owns its dispatcher, as [unlock] does (2026-10-02): this is a full calibrated Argon2id, and
     * the Hidden-items gate, the Recovery Kit gate, Export and Restore-snapshot all call it from a
     * bare `viewModelScope.launch` — `Main.immediate` — so the derivation froze the UI they had
     * just put a progress label on. The wipe stays in the outer `finally`, outside the switch, so a
     * caller cancelled mid-derivation still wipes.
     */
    suspend fun reauthenticate(password: CharArray): Boolean = try {
        withContext(Dispatchers.Default) {
            operationMutex.withLock {
                val state = synchronized(stateMonitor) {
                    // A bare `error(...)` here turned "the auto-lock timer fired while the user sat
                    // at the reprompt" into a generic IllegalStateException that the autofill gate's
                    // catch-all collapsed into "vault unreadable" — the corruption wording, which
                    // can push a user toward restoring a backup for a healthy vault. Name the
                    // condition so callers can tell a lock from a fault.
                    _lockState.value as? LockState.Unlocked
                        ?: throw VaultSessionEndedException("reauthenticate")
                }
                // Re-auth is a master-password check, so it shares the password's backoff: it is
                // one more place the same secret can be guessed at.
                checkThrottle(Credential.KNOWLEDGE)
                val fileBytes = when (val loaded = VaultLoader.loadFileBytes(context)) {
                    is Outcome.Success -> loaded.value
                    is Outcome.Failure -> return@withLock false
                }
                val header = VaultHeaderCodec.decode(fileBytes)
                val slot = header.slots[if (state.isDuress) 1 else 0]
                var kek: ByteArray? = null
                var unwrapped: ByteArray? = null
                try {
                    // Guarded: this runs beside the open UI (export, the Recovery Kit gate, the
                    // invisible-items gate), where the heap is what is left after the app itself.
                    kek = guardKdfMemory {
                        EnvelopeCrypto.deriveKek(password, slot.salt, slot.kdfParams)
                    }
                    unwrapped = EnvelopeCrypto.unwrapDek(kek, slot.wrappedDek)
                    val valid =
                        commitmentMatches(unwrapped, slot.commitment) &&
                            ConstantTime.equals(unwrapped, state.dek)
                    if (!valid) recordFailure(Credential.KNOWLEDGE)
                    valid
                } catch (_: AeadAuthException) {
                    recordFailure(Credential.KNOWLEDGE)
                    false
                } finally {
                    Zeroizer.wipe(kek)
                    Zeroizer.wipe(unwrapped)
                }
            }
        }
    } finally {
        Zeroizer.wipe(password)
    }

    /**
     * The open session's vault and a *private copy* of its DEK, taken atomically with the lock
     * state. Null when the vault is locked. The caller owns the copy and must wipe it.
     *
     * `currentDekOrThrow()` hands out the manager's own array, which [lock] zeroes the instant the
     * auto-lock or background-grace timer fires. That is fine for a read that finishes in
     * microseconds and wrong for anything long-running. The independent-passphrase export is the
     * long-running case: it calibrates Argon2id and derives a KEK — hundreds of milliseconds,
     * seconds on a slow device — before it first touches the DEK, and the user backgrounding the
     * app in that window fired the grace timer underneath it. What it then wrote was a backup
     * wrapped and encrypted under an all-zero key: at best a file that will not restore, at worst
     * one whose payload key is a known constant, so the passphrase it was supposed to be sealed
     * with protects nothing.
     */
    fun sessionSnapshotOrNull(): SessionSnapshot? = synchronized(stateMonitor) {
        val unlocked = _lockState.value as? LockState.Unlocked ?: return null
        SessionSnapshot(vault = unlocked.vault, dek = unlocked.dek.copyOf())
    }

    /**
     * Whether [fileBytes] still authenticates under **this session's own** payload region.
     *
     * The one thing a header check cannot tell you. [reauthenticate] verifies an unlock slot, which
     * lives entirely in the header, so it proves the password and says nothing at all about the
     * ciphertext underneath it. That gap is fine everywhere a caller goes on to decrypt — a save,
     * an unlock, a restore all fail loudly on a payload that will not open — and it is not fine for
     * the one caller that *copies* the container instead of reading it: the master-password export
     * (`VaultExporter.exportWithMasterPassword`) re-authenticated the header and then streamed
     * `vault.enc` out byte for byte, so an export taken between a payload corruption and the next
     * save or unlock was a bad backup reported as "Exported 4.5 MB" (fixed
     * 2026-09-13).
     *
     * **Region-addressed by session, never hard-coded to 0**, and this is the load-bearing half. A
     * decoy session holds the decoy DEK, so it cannot authenticate region 0 by construction — the
     * whole duress design rests on that — and a check written against region 0 would refuse every
     * export made from a decoy session while succeeding from a real one. That is a repeatable,
     * observable difference between the two sessions, which is exactly the tell the feature exists
     * not to produce. A duress session therefore checks its own region 1 and exports normally.
     *
     * False when the vault is locked, when the container will not decode, and when the region does
     * not authenticate — three different facts that all mean the same thing to the one caller:
     * do not copy this file. A caller that needs to tell them apart should not use this.
     *
     * The DEK is copied under [stateMonitor] and the AEAD open runs outside it: the payload is
     * whole-vault sized, and holding the monitor across it would stall [lock] — the timer this
     * app's whole auto-lock story depends on — for the length of an 8 MiB decrypt.
     */
    fun sessionAuthenticatesContainer(fileBytes: ByteArray): Boolean {
        val dek: ByteArray
        val regionIndex: Int
        synchronized(stateMonitor) {
            val unlocked = _lockState.value as? LockState.Unlocked ?: return false
            dek = unlocked.dek.copyOf()
            regionIndex = if (unlocked.isDuress) 1 else 0
        }
        var encKey: ByteArray? = null
        return try {
            encKey = VaultSubkeys.encKey(dek)
            VaultFileCodec.authenticatesRegion(fileBytes, encKey, regionIndex)
        } catch (_: Exception) {
            // A header that will not decode, a container whose region table does not fit the file:
            // both are "do not copy this", which is what false says.
            false
        } finally {
            Zeroizer.wipe(encKey)
            Zeroizer.wipe(dek)
        }
    }

    /**
     * Returns the current unlock epoch for a long-lived reveal operation. The token is not secret;
     * it only lets a caller prove that the DEK it is about to read belongs to the same session it
     * observed before showing an authentication prompt. Carry it into [sessionKeyOrNull] and the
     * check is then made again at every read, not just at the prompt.
     */
    fun unlockedSessionToken(): Long? = synchronized(stateMonitor) {
        if (_lockState.value is LockState.Unlocked) stateEpoch else null
    }

    /** See [sessionSnapshotOrNull]. [dek] is the caller's own copy, and the caller wipes it. */
    class SessionSnapshot(val vault: Vault, val dek: ByteArray)

    /**
     * User interaction: restart the idle countdown. Ignored while backgrounded — nothing there is
     * the user touching the app, and letting a stray event extend the grace period would defeat it.
     */
    fun extendAutoLock() {
        synchronized(stateMonitor) {
            if (inBackground) return
            if (_lockState.value is LockState.Unlocked) scheduleAutoLockLocked()
        }
    }

    /**
     * Stores a device-bound wrapper for the current real-vault DEK using an already
     * biometric-authenticated ENCRYPT_MODE cipher.
     */
    suspend fun enableBiometricUnlock(cipher: Cipher): Boolean = operationMutex.withLock {
        val snapshot = realSessionSnapshot("enable biometric unlock")
        val wrapped = try {
            WrappedDek(
                nonce = cipher.iv?.copyOf() ?: return@withLock false,
                ciphertext = cipher.doFinal(snapshot.dek),
            )
        } catch (_: Exception) {
            return@withLock false
        } finally {
            Zeroizer.wipe(snapshot.dek)
        }
        return@withLock try {
            ensureSessionUnchanged(snapshot)
            DeviceKeyStore.save(context, wrapped)
            // Setting biometric unlock up again is the one way back from a panic lock's suspension.
            panicPrefs.edit().remove(PANIC_SUSPENDED_KEY).apply()
            true
        } catch (_: Exception) {
            // `runCatching`, because this is cleanup on a path that has already failed: a delete
            // that throws here would replace a handled "biometric setup did not work" with an
            // exception thrown out of a mutex-held suspend function, which the caller has no
            // branch for. Half-written key material is exactly what must not survive a failed
            // setup, and best-effort removal is the most this can promise.
            runCatching { DeviceKeyStore.delete(context) }
            false
        } finally {
            Zeroizer.wipe(wrapped.nonce)
            Zeroizer.wipe(wrapped.ciphertext)
        }
    }

    private fun tryPasswordSlot(
        fileBytes: ByteArray,
        header: VaultHeader,
        slotIndex: Int,
        password: CharArray,
        /**
         * Invoked with 1 the moment this slot's Argon2id derivation returns — unconditionally, and
         * before anything has been decided about the password. It must stay above the `unwrapDek`
         * call below: `unwrapValid` and `commitmentValid` are per-slot verdicts, and reporting
         * either of them would answer "was that the right password" seconds before the unlock does.
         */
        onDerived: (Int) -> Unit = {},
    ): SlotTrial {
        val slot = header.slots[slotIndex]
        var kek: ByteArray? = null
        var candidateDek: ByteArray? = null
        var encKey: ByteArray? = null
        var commitmentValid = false
        var unwrapValid = false
        var vault: Vault? = null
        try {
            kek = EnvelopeCrypto.deriveKek(password, slot.salt, slot.kdfParams)
            onDerived(1)
            candidateDek = try {
                EnvelopeCrypto.unwrapDek(kek, slot.wrappedDek).also { unwrapValid = true }
            } catch (_: AeadAuthException) {
                ByteArray(CryptoConstants.KEY_LEN)
            }
            commitmentValid = commitmentMatches(candidateDek, slot.commitment)
            encKey = VaultSubkeys.encKey(candidateDek)
            var schemaTooNew = false
            vault = try {
                VaultFileCodec.decryptRegion(fileBytes, encKey, slotIndex).second
            } catch (_: VaultSchemaTooNewException) {
                // Caught apart from every other decode failure, and only recorded — never acted on
                // before `credentialValid` is known below. The AEAD has already authenticated by the
                // time this can be thrown, so it says nothing a wrong password could learn.
                schemaTooNew = true
                null
            } catch (_: Exception) {
                null
            }
            val credentialValid = unwrapValid && commitmentValid
            return when {
                credentialValid && vault != null ->
                    SlotTrial.Match(candidateDek, vault).also { candidateDek = null }
                // Right password, payload from a build this one predates. Split out from
                // [PayloadUndecryptable] for the same reason that was split out from "no match":
                // the file is intact, and the advice damage earns — restore a backup — is the one
                // action that would destroy it.
                credentialValid && schemaTooNew -> SlotTrial.PayloadTooNew
                // Right password, unreadable payload. Split out from the null return this used to
                // share with a wrong password: the two look identical from here and mean opposite
                // things to the person holding the phone.
                credentialValid -> SlotTrial.PayloadUndecryptable
                else -> SlotTrial.NoMatch
            }
        } finally {
            Zeroizer.wipe(kek)
            Zeroizer.wipe(candidateDek)
            Zeroizer.wipe(encKey)
        }
    }

    private fun commitmentMatches(
        dek: ByteArray,
        expected: ByteArray,
    ): Boolean {
        val actual = EnvelopeCrypto.slotCommitment(dek)
        return try {
            ConstantTime.equals(actual, expected)
        } finally {
            Zeroizer.wipe(actual)
        }
    }

    private fun passwordMatchesSlot(
        password: CharArray,
        slot: UnlockSlot,
        expectedDek: ByteArray,
    ): Boolean {
        var kek: ByteArray? = null
        var candidateDek: ByteArray? = null
        return try {
            kek = guardKdfMemory { EnvelopeCrypto.deriveKek(password, slot.salt, slot.kdfParams) }
            candidateDek = EnvelopeCrypto.unwrapDek(kek, slot.wrappedDek)
            commitmentMatches(candidateDek, slot.commitment) &&
                ConstantTime.equals(candidateDek, expectedDek)
        } catch (_: AeadAuthException) {
            false
        } finally {
            Zeroizer.wipe(kek)
            Zeroizer.wipe(candidateDek)
        }
    }

    private fun passwordUnlocksSlot(
        password: CharArray,
        slot: UnlockSlot,
    ): Boolean {
        var kek: ByteArray? = null
        var candidateDek: ByteArray? = null
        return try {
            kek = EnvelopeCrypto.deriveKek(password, slot.salt, slot.kdfParams)
            candidateDek = EnvelopeCrypto.unwrapDek(kek, slot.wrappedDek)
            commitmentMatches(candidateDek, slot.commitment)
        } catch (_: AeadAuthException) {
            false
        } finally {
            Zeroizer.wipe(kek)
            Zeroizer.wipe(candidateDek)
        }
    }

    /**
     * The mirror of [realSessionSnapshot]: refuses anything but an open **decoy** session.
     *
     * Written as its own guard rather than a boolean parameter on the one above, so that a
     * decoy-scoped operation cannot be reached from a real session by getting an argument wrong.
     * The `dek` it hands back is the decoy's.
     */
    private fun duressSessionSnapshot(operation: String): CommitSnapshot =
        synchronized(stateMonitor) {
            val unlocked = _lockState.value as? LockState.Unlocked
                ?: error("vault is locked")
            check(unlocked.isDuress) {
                "must be unlocked with the decoy to $operation"
            }
            CommitSnapshot(
                state = unlocked,
                dek = unlocked.dek.copyOf(),
                epoch = stateEpoch,
                operation = operation,
            )
        }

    private fun realSessionSnapshot(operation: String): CommitSnapshot =
        synchronized(stateMonitor) {
            val unlocked = _lockState.value as? LockState.Unlocked
                ?: error("vault is locked")
            check(!unlocked.isDuress) {
                "must be unlocked with the real vault to $operation"
            }
            CommitSnapshot(
                state = unlocked,
                dek = unlocked.dek.copyOf(),
                epoch = stateEpoch,
                operation = operation,
            )
        }

    private fun ensureSessionUnchanged(snapshot: CommitSnapshot) {
        val unchanged = synchronized(stateMonitor) {
            stateEpoch == snapshot.epoch && _lockState.value === snapshot.state
        }
        // Typed, not `check`. A bare IllegalStateException here reached the settings screen as
        // "Operation could not be completed", which is what the app says when it knows nothing —
        // and it knows exactly what happened. The abort itself is clean: every caller runs this
        // before `AtomicVaultWriter.write`, so nothing is half-written.
        if (!unchanged) throw VaultSessionEndedException(snapshot.operation)
    }

    private fun loadCurrentFile(): ByteArray =
        when (val loaded = VaultLoader.loadFileBytes(context)) {
            is Outcome.Success -> loaded.value
            is Outcome.Failure -> error("vault file unavailable")
        }

    private fun publishUnlocked(
        dek: ByteArray,
        vault: Vault,
        isDuress: Boolean,
        expectedEpoch: Long,
        openedWithRecoveryKit: Boolean = false,
    ): Boolean = synchronized(stateMonitor) {
        if (stateEpoch != expectedEpoch || _lockState.value !is LockState.Locked) {
            false
        } else {
            _lockState.value = LockState.Unlocked(dek, vault, isDuress, openedWithRecoveryKit)
            if (!isDuress) biometricBlockedUntilMasterUnlock = false
            scheduleAutoLockLocked()
            true
        }
    }

    private fun scheduleAutoLockLocked() {
        autoLockJob?.cancel()
        autoLockJob = null
        // Whichever timer currently applies, counted in full from now: there is no elapsed-idle
        // state anywhere in this class, and every caller re-enters here to cancel and restart.
        //
        // So what the grace bounds is *time spent backgrounded*, and it bounds it exactly — 5 s,
        // 30 s or 60 s by preset, whenever the app leaves the foreground. It does not bound the
        // session: idle almost to the timeout and then backgrounding gives idleMs + graceMs of
        // total unlocked lifetime, not idleMs. This comment used to claim the opposite ("shortens
        // the remaining window"), which is false for the last graceMs of any idle period.
        //
        // Restarting rather than taking min(remaining, grace) is not obviously wrong — leaving the
        // app is the user acting on the device, so the in-app idle clock is stale at that instant —
        // but it is a choice, and it is this one. The unattended cases the grace is really for are
        // covered ahead of it: `LocalKeepApplication` locks immediately and unconditionally on
        // ACTION_SCREEN_OFF.
        //
        // The picker excursion is the exception: backgrounded only because of a picker we launched
        // is still the user working inside LocalKeep, so it keeps the (longer) idle timer.
        val useGrace = inBackground && externalPickerDepth == 0
        val timeout = if (useGrace) configuredBackgroundGraceMs else configuredAutoLockTimeoutMs
        if (timeout == 0L || _lockState.value !is LockState.Unlocked) return
        autoLockJob = scope.launch {
            delay(timeout)
            lock()
        }
    }

    /**
     * How long the knowledge factor — the master password, or the Recovery Kit — stays locked out,
     * or 0. Read-only: [checkThrottle] remains the one authority on refusing an attempt.
     *
     * The backoff is persisted, so a cold start into a lockout can show its countdown before
     * anything is typed rather than after a submit that was always going to be refused (QA
     * 2026-10-03).
     */
    fun knowledgeLockoutRemainingMs(): Long = throttle.remainingLockoutMs(Credential.KNOWLEDGE.name)

    private fun checkThrottle(credential: Credential) {
        val remaining = throttle.remainingLockoutMs(credential.name)
        if (remaining > 0L) {
            throw VaultLockedException(remaining)
        }
    }

    private fun recordFailure(credential: Credential) {
        throttle.recordFailure(credential.name)
    }

    /**
     * Clears the backoff on **every** credential.
     *
     * Any successful unlock proves the legitimate user is present, which is the thing throttling
     * was buying time against — so a biometric unlock clears a password lockout and vice versa.
     * Only ever called after [publishUnlocked] has actually taken.
     */
    private fun resetThrottle() {
        throttle.reset()
    }

    /**
     * What one slot made of a password.
     *
     * The third case is the one that matters and used to be folded into "no match": the wrapped key
     * unwrapped and matched the slot commitment — so the password is right — but the payload region
     * it keys would not authenticate. That is a damaged container, and reporting it as a wrong
     * password is how a user with a perfectly good `.bak` gets throttled out of ever reaching it.
     */
    private sealed interface SlotTrial {
        class Match(
            val dek: ByteArray,
            val vault: Vault,
        ) : SlotTrial

        /** The password does not open this slot. */
        data object NoMatch : SlotTrial

        /** The password opens this slot; the slot's payload is corrupt. */
        data object PayloadUndecryptable : SlotTrial

        /** The password opens this slot; its payload declares a schema this build cannot read. */
        data object PayloadTooNew : SlotTrial
    }

    private data class CommitSnapshot(
        val state: LockState.Unlocked,
        val dek: ByteArray,
        val epoch: Long,
        /** Carried only so an aborted operation can name itself to the user. */
        val operation: String,
    )

    private companion object {
        const val PANIC_SUSPENDED_KEY = "suspended"
        const val DEFAULT_AUTO_LOCK_TIMEOUT_MS = 60_000L
        const val MAX_FAILED_ATTEMPTS = 5
        const val MAX_BACKOFF_MS = 300_000L

        /** How long [wipeSessionDek] waits for in-flight borrows before zeroing anyway. */
        const val DEK_BORROW_DRAIN_MS = 1_000L
    }
}

/**
 * How many times one unlock may run its derivation before a memory failure is reported.
 *
 * Two, not more: this is a retry, not a loop. A vault whose own cost genuinely does not fit reaches
 * the same message one failed allocation later, and an allocation that cannot succeed fails at once
 * rather than running a cost.
 */
internal const val UNLOCK_MEMORY_ATTEMPTS = 2

/** The two results `VaultLockManager.unlock`'s `OutOfMemoryError` handler can produce. */
internal fun UnlockResult.isMemoryFailure(): Boolean =
    this == UnlockResult.VAULT_NEEDS_MORE_MEMORY || this == UnlockResult.VAULT_MEMORY_TRANSIENT

/**
 * Runs [attempt] again if it came back a memory failure, and answers with the second attempt
 * whatever it says.
 *
 * Neither memory message is worth showing on the strength of one lost allocation. "This vault needs
 * more memory than this device allows an app. […] Open it on a phone with more memory" is advice
 * about a vault that cannot be opened here at all, and it was being given for a three-item vault the
 * device itself had made, on a brand-new user's very first unlock (API 29, filed 2026-09-08). The
 * fault's own reported shape — fails once, opens on the next try, three times out of three — is the
 * argument for retrying rather than reporting: the next try is the answer, so the app takes it.
 *
 * Safe to repeat because the failing path has no effects. The handler is reached only from inside
 * the derivation, before any session is published, before anything is written, and `recordFailure`
 * belongs to a slot scan that ran to completion — so a memory failure never counted as a guess and a
 * second attempt is not a second guess either. [yield] between the two hands the thread over, since
 * whatever won the allocation race is the thing that has to finish.
 */
internal suspend fun retryingMemoryFailures(
    attempts: Int = UNLOCK_MEMORY_ATTEMPTS,
    attempt: suspend () -> UnlockResult,
): UnlockResult {
    require(attempts >= 1) { "an unlock needs at least one attempt" }
    var remaining = attempts
    while (true) {
        remaining--
        val result = attempt()
        if (remaining <= 0 || !result.isMemoryFailure()) return result
        yield()
    }
}

/**
 * Which of the two memory failures an [OutOfMemoryError] during unlock actually was.
 *
 * The exception says only that an allocation failed; the vault says whether that was avoidable. A
 * vault whose own Argon2id cost is within [KdfParams.PORTABLE_MAX_MEMORY_KIB] fits every device this
 * app supports — the calibrator will not create one above it — so failing to open it means the
 * *process* had no room, not that the phone is too small, and the fix is to close the app fully.
 * Above that ceiling (a vault carried over from a stronger device, or from a build that predates the
 * ceiling) the older answer is the true one and rewrapping the KDF params is the real remedy.
 *
 * @param attemptedMemoryKib the cost from the header that was being derived against, or null if no
 *   header was read before the failure — in which case the older, more cautious answer is kept
 *   rather than guessing "transient" and sending the user round a loop that will not help.
 */
internal fun memoryFailureResult(attemptedMemoryKib: Int?): UnlockResult =
    if (attemptedMemoryKib != null && attemptedMemoryKib <= KdfParams.PORTABLE_MAX_MEMORY_KIB) {
        UnlockResult.VAULT_MEMORY_TRANSIENT
    } else {
        UnlockResult.VAULT_NEEDS_MORE_MEMORY
    }

/**
 * Runs one in-app key derivation and turns an `OutOfMemoryError` into
 * [KdfNeedsMoreMemoryException]. Catching an `Error` is deliberate on the same reasoning as
 * `unlock` and `Argon2Calibrator.fits`: the failed allocation is Argon2's own block array,
 * unreachable the moment the frame unwinds, so the heap recovers and nothing has been written.
 * Only the operations that run while the vault is open use this; unlock keeps its own handler,
 * which distinguishes two different memory failures.
 */
internal inline fun <T> guardKdfMemory(block: () -> T): T = try {
    block()
} catch (_: OutOfMemoryError) {
    throw KdfNeedsMoreMemoryException(
        heapLimitMib = Runtime.getRuntime().maxMemory() / (1024 * 1024),
    )
}

/** Where [VaultLockManager.isBiometricSuspendedByPanic] is kept. */
internal const val PANIC_BIOMETRIC_PREFS = "biometric_panic"
