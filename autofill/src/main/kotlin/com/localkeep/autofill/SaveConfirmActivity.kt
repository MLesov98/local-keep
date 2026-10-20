package com.localkeep.autofill

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.localkeep.core.common.fold
import com.localkeep.core.common.runCatchingOutcome
// Aliased: `Card` is also the name of the captured-but-not-yet-stored form in PendingSaveStore, and
// this file handles both. Two `Card`s one scope apart is a mix-up waiting to happen.
import com.localkeep.core.model.Card as CardContent
import com.localkeep.core.model.Login
import com.localkeep.core.model.PasswordHistory
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.SecureActivity
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.ProTypeQuota
import com.localkeep.core.vault.UnlockResult
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultLockedException
import com.localkeep.core.vault.VaultRepository
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit confirmation for an autofill save or password update. */
@AndroidEntryPoint
class SaveConfirmActivity : SecureActivity() {
    @Inject
    lateinit var repository: VaultRepository

    @Inject
    lateinit var lockManager: VaultLockManager

    /** The free tier's card allowance is a tier question. Read with no network — see `isPro`. */
    @Inject
    lateinit var entitlement: com.localkeep.core.billing.Entitlement

    private var token: UUID? = null
    private var resolved = false

    /**
     * Set the moment [save] starts; Discard is disabled from then on.
     *
     * The commit runs in [NonCancellable] (see [performSave] — the wipe has to land no matter how
     * the screen ends), so a Discard tapped mid-write could not stop it: the token was already
     * consumed, the vault write kept running, and the user's explicit "do not save" control
     * finished the activity while the credential was stored anyway. A disable is the honest
     * control — the write cannot be cancelled, so the button must not pretend it can be.
     */
    private var saving by mutableStateOf(false)

    /** Whether the unlock-to-save step (F-47) is showing instead of the save/discard confirm. */
    private var unlockVisible by mutableStateOf(false)
    private var unlockPending by mutableStateOf(false)
    private var unlockError by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra(EXTRA_TOKEN)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val entry = token?.let(PendingSaveStore::peek)
        if (entry == null && saveAlreadyResolved(savedInstanceState)) {
            // Recreated (rotation, fold, dark mode) after the previous instance consumed the entry
            // and started writing it. That write runs on and reports "Saved" itself; calling this
            // expired too told the user both things about one password (2026-10-02).
            resolved = true
            finish()
            return
        }
        if (entry == null) {
            // Reachable on the ordinary path, not just a malformed intent: PendingSaveStore's TTL
            // starts when SaveHandler mints the token, and the platform's own save prompt can sit
            // on screen far longer than that while the user does something else. Tapping it then
            // used to open this activity and finish() in silence — the OS affordance the user
            // pressed visibly did nothing, and the credential was already wiped. Saying so is all
            // that is left to do; the password is gone and cannot be recovered from here.
            reportExpired()
            return
        }

        val locked = lockManager.lockState.value !is LockState.Unlocked
        val isUpdate = entry.existingItemId != null
        // The app's own label, not its package name — the same way the picker names the same
        // caller. See [callerLabel].
        val target = callerLabel(entry.webDomain, entry.packageName)
        // Which of the three titles, and which primary-action word — see SaveConfirmCopy.kt.
        val titleRes = saveConfirmTitleRes(
            hasCard = entry.card != null,
            locked = locked,
            isUpdate = isUpdate,
            awaitingUnlock = entry.awaitingUnlock,
            hasUsername = entry.username.isNotBlank(),
        )
        val title = if (entry.card != null) {
            // Named by its last four digits, never in full. This screen can appear over a
            // merchant's checkout page in a shared or shoulder-surfable setting, and the user does
            // not need the number read back to confirm the one they just typed. Held as a
            // CharArray until here — see PendingSaveStore.Card.
            getString(titleRes, entry.card.number.concatToString().takeLast(4))
        } else if (entry.username.isBlank()) {
            getString(titleRes, target)
        } else {
            getString(titleRes, entry.username, target)
        }
        val lockedNotice = if (locked) getString(R.string.autofill_save_confirm_locked_notice) else null
        // What is actually about to be written, under the title that asks about it — see
        // SaveConfirmCopy.kt. Carries no secret; the masked row is drawn from a label alone.
        val summary = saveConfirmSummary(entry, target) { getString(it) }
        val saveLabel = getString(saveConfirmActionRes(locked = locked, isUpdate = isUpdate))
        val discardLabel = getString(R.string.autofill_save_confirm_discard)

        setContent {
            LocalKeepTheme {
                LkScreen {
                    if (unlockVisible) {
                        SaveConfirmUnlockContent(
                            title = getString(R.string.autofill_unlock_title),
                            subtitle = getString(R.string.autofill_save_confirm_unlock_subtitle),
                            passwordLabel = getString(R.string.autofill_password_prompt_hint),
                            continueLabel = getString(R.string.autofill_password_prompt_continue),
                            unlockingLabel = getString(R.string.autofill_password_prompt_unlocking),
                            errorMessage = unlockError,
                            pending = unlockPending,
                            onSubmit = ::unlockThenSave,
                        )
                    } else {
                        SaveConfirmContent(
                            title = title,
                            lockedNotice = lockedNotice,
                            summary = summary,
                            saveLabel = saveLabel,
                            discardLabel = discardLabel,
                            saving = saving,
                            onSave = ::save,
                            // Separated from Save so the two never share a touch boundary; the
                            // `enabled = !saving` on the button already covers the case this
                            // guards, kept for the same reason `refuseCardNoRoom` double-checks a
                            // condition the caller believes it already established.
                            onDiscard = { if (!saving) discard() },
                        )
                    }
                }
            }
        }
    }

    private fun save() {
        val pendingToken = token ?: return
        // The write starts now; from here Discard cannot stop it (see the `saving` KDoc).
        saving = true
        // Writing needs the session DEK. Rather than dropping the credential and making the user
        // reproduce it on the originating site, hold the proposal and unlock in place.
        //
        // Deliberately inline rather than launching AuthActivity for a result: these autofill
        // activities are android:noHistory, and a noHistory activity never receives
        // onActivityResult — routing through one would silently swallow the save, which is exactly
        // the failure this whole path exists to fix.
        if (lockManager.lockState.value !is LockState.Unlocked) {
            PendingSaveStore.touch(pendingToken)
            promptForUnlock()
            return
        }
        if (refuseCardNoRoom()) return
        performSave()
    }

    /**
     * Refuses a captured card the free tier has no room for, says so, and returns true.
     *
     * The allowance is [ProTypeQuota]'s — the same one `ItemEditViewModel` applies to a card created
     * in the app. The capture route used to carry a binary Pro test in `SaveHandler.handleCard`
     * instead, which both refused a free vault's *first* card, creatable in the editor since
     * 2026-09-03, and did it in silence after the platform's own prompt had already been accepted.
     * The check belongs here because this is the only point on that route with an unlocked vault to
     * count against — the unlock detour below runs first — and a screen to answer on.
     *
     * A repository read that throws counts as "not blocked", exactly as the editor treats it: the
     * ordinary cause is the vault locking mid-flow, and failing closed would drop a card the user
     * explicitly asked to store for a reason this toast could not explain.
     *
     * Discarded rather than left to expire, which wipes the PAN and the security code: the user has
     * been told it is not being stored, so nothing is waiting for a second attempt at it.
     */
    private fun refuseCardNoRoom(): Boolean {
        val pendingToken = token ?: return false
        if (PendingSaveStore.peek(pendingToken)?.card == null) return false
        val used = runCatching {
            ProTypeQuota.usedFromRepository(repository)[ProTypeQuota.Kind.CARD] ?: 0
        }.getOrElse { return false }
        if (!ProTypeQuota.exceedsFreeLimit(used, isPro = entitlement.isPro.value)) return false
        resolved = true
        PendingSaveStore.discard(pendingToken)
        Toast.makeText(
            this,
            getString(R.string.autofill_save_confirm_toast_card_no_room),
            Toast.LENGTH_LONG,
        ).show()
        setResult(Activity.RESULT_CANCELED)
        finish()
        return true
    }

    /**
     * Tells the user the proposed save is gone, then closes.
     *
     * A Toast rather than a dialog: this activity can be launched over another app's checkout or
     * sign-in page, and there is no decision left to make. Names neither the site nor the
     * credential, for the same reason the card title shows only the last four digits — this can
     * appear on a screen someone else is looking at.
     */
    private fun reportExpired() {
        Toast.makeText(
            this,
            getString(R.string.autofill_save_confirm_toast_expired),
            Toast.LENGTH_LONG,
        ).show()
        finish()
    }

    private fun promptForUnlock() {
        unlockError = ""
        unlockVisible = true
    }

    private fun unlockThenSave(password: CharArray) {
        unlockPending = true
        unlockError = ""
        lifecycleScope.launch {
            // The same mapping AuthActivity uses, one file over. This gate used to collapse every
            // failure into `false` and render "Couldn't unlock" — the wrong-password string — so a
            // throttled attempt and an unreadable vault file both accused the user of mistyping a
            // password that was correct, with no countdown to explain the refusal. Retyping cannot
            // help, and the PendingSaveStore entry expires after 120 s: the captured credential is
            // then wiped and the save is lost.
            val outcome = withContext(Dispatchers.Default) {
                try {
                    lockManager.unlock(password).let { result ->
                        if (result.opened) AuthOutcome.Success else AuthOutcome.Failed(result)
                    }
                } catch (locked: VaultLockedException) {
                    AuthOutcome.Throttled(locked.retryAfterMs)
                } catch (_: Exception) {
                    // Anything else is a fault in this gate, not a claim about the password.
                    AuthOutcome.Failed(UnlockResult.VAULT_UNREADABLE)
                }
            }
            password.fill('\u0000')
            unlockPending = false
            if (outcome is AuthOutcome.Success) {
                // The allowance could not be counted before the unlock, and this is the path a
                // captured card usually takes — a checkout happens with LocalKeep locked far more
                // often than not — so the gate has to sit here as well as in `save`.
                if (!refuseCardNoRoom()) performSave()
            } else {
                unlockError = authPromptMessage(outcome).resolve(this@SaveConfirmActivity)
            }
        }
    }

    private fun performSave() {
        val pendingToken = token ?: return
        val entry = PendingSaveStore.consume(pendingToken) ?: run {
            // The same expiry, one step later: the entry ran out while the user was typing their
            // master password into the unlock detour. `touch()` extends the TTL when that detour
            // starts, but a long enough unlock still outlives it.
            reportExpired()
            return
        }
        resolved = true
        // NonCancellable around the whole thing, and the wipe in a finally. The zeroization used to
        // sit after the suspend block on lifecycleScope, so destroying the activity mid-save --
        // rotation, the user swiping the task away, the system reclaiming it -- cancelled the
        // coroutine before the wipe and left the captured password sitting in a CharArray on the
        // heap. This is a password the user never chose to give us twice; it has to go regardless
        // of how the screen ends.
        lifecycleScope.launch(NonCancellable) {
          try {
            val outcome = withContext(Dispatchers.Default) {
                runCatchingOutcome {
                    val card = entry.card
                    if (card != null) {
                        saveCard(card, entry)
                        return@runCatchingOutcome
                    }
                    val password = entry.password.concatToString()
                    val existingItemId = resolveExisting(entry)
                    if (existingItemId != null) {
                        val now = Instant.now()
                        // Read-modify-write under one lock: the item is re-read inside
                        // `updateWith`, so an edit committed between resolving the id and writing
                        // survives instead of being overwritten by a copy of the pre-read item.
                        repository.updateWith(existingItemId) { existing ->
                            val login = existing.content as Login
                            // Through PasswordHistory.append, which is what bounds this. Appending
                            // directly — as this did until 2026-07-31 — meant every password the
                            // user had ever had at this site stayed in the vault forever,
                            // invisibly.
                            val history = if (login.password != password) {
                                PasswordHistory.append(login.passwordHistory, login.password, now)
                            } else {
                                PasswordHistory.trim(login.passwordHistory)
                            }
                            existing.copy(
                                content = login.copy(
                                    password = password,
                                    // Stamped here as well as in the editor. `updateItem` resolves
                                    // a concurrent write through `ItemMerge.preserveNewerPassword`,
                                    // which compares *this* field — leaving it at the old value
                                    // meant an autofill-captured password looked no fresher than a
                                    // stale editor copy, lost the comparison, and was discarded.
                                    // It also drives the health screen's "old password" age, which
                                    // never cleared for a password rotated this way.
                                    passwordChangedAt = now,
                                    passwordHistory = history,
                                ),
                                updatedAt = now,
                            )
                        }
                    } else {
                        val now = Instant.now()
                        repository.addItem(
                            VaultItem(
                                id = UUID.randomUUID(),
                                // What the user will read in their vault for the life of this
                                // item, so it is the caller's label rather than its package name.
                                // The username stays the last resort, for a caller with neither.
                                title = callerLabel(
                                    webDomain = entry.webDomain,
                                    packageName = entry.packageName,
                                    fallback = entry.username,
                                    resolveAppLabel = { pkg ->
                                        runCatching {
                                            packageManager.getApplicationLabel(
                                                packageManager.getApplicationInfo(pkg, 0),
                                            ).toString()
                                        }.getOrNull()
                                    },
                                ),
                                createdAt = now,
                                updatedAt = now,
                                content = Login(
                                    username = entry.username,
                                    password = password,
                                    uris = entry.webDomain
                                        ?.let { listOf(UriMatch(uri = it)) }
                                        .orEmpty(),
                                    // Browser credentials bind to their web domain, never to the
                                    // browser package (which would match unrelated sites).
                                    androidPackages = if (entry.webDomain == null) {
                                        entry.packageName?.let(::listOf).orEmpty()
                                    } else {
                                        emptyList()
                                    },
                                ),
                            ),
                        )
                    }
                }
            }
            Toast.makeText(
                this@SaveConfirmActivity,
                outcome.fold(
                    onSuccess = { getString(R.string.autofill_save_confirm_toast_saved) },
                    onFailure = { getString(R.string.autofill_save_confirm_toast_failed) },
                ),
                Toast.LENGTH_SHORT,
            ).show()
            setResult(
                if (outcome.fold({ true }, { false })) Activity.RESULT_OK
                else Activity.RESULT_CANCELED,
            )
            finish()
          } finally {
            entry.password.fill('\u0000')
            // Same guarantee the password gets, and for the same reason: NonCancellable plus a
            // finally, so the number is gone whether the save succeeded, threw, or the user swiped
            // the task away mid-write. A PAN is not a secret the user chose to hand over twice.
            entry.card?.wipe()
          }
        }
    }

    /**
     * Stores a card captured from a checkout form.
     *
     * Always a new item, never an update: matching an incoming PAN against stored ones would mean
     * comparing card numbers across the whole vault on every checkout, and the failure mode of
     * getting that wrong is overwriting the wrong card. A duplicate the user can see and delete is
     * the better error.
     */
    private suspend fun saveCard(card: PendingSaveStore.Card, entry: PendingSaveStore.Entry) {
        val now = Instant.now()
        val number = card.number.concatToString()
        val (month, year) = parseExpiry(card.expiry)
        repository.addItem(
            VaultItem(
                id = UUID.randomUUID(),
                // Named for where it was used and what it ends in, because that is what the user
                // will recognise in a list. Never the full number — a vault-list row is visible
                // over a shoulder, and the title is the one field nothing masks.
                title = buildString {
                    append(
                        entry.webDomain ?: entry.packageName
                            ?: getString(R.string.autofill_save_confirm_card_title_fallback),
                    )
                    append(" ****")
                    append(number.takeLast(4))
                },
                createdAt = now,
                updatedAt = now,
                content = CardContent(
                    holder = card.holder,
                    number = number,
                    expMonth = month,
                    expYear = year,
                    // Captured because the merchant's form asked for it. PCI-DSS forbids a
                    // *processor* retaining the security code after authorisation; a person storing
                    // their own card in their own vault is a different actor, and every mainstream
                    // password manager stores it. It is masked and re-auth gated like every other
                    // high-value field.
                    cvv = card.securityCode.concatToString(),
                ),
            ),
        )
    }

    /**
     * `MM/YY`, `MM/YYYY`, `YYYY-MM` or a bare `MMYY`, and nulls when it is none of those.
     *
     * A merchant's expiry field can hold almost anything, so this takes only what it can recognise
     * unambiguously and declines the rest. Guessing is worse than leaving it blank: a wrong expiry
     * is invisible until it fails at a till months later, whereas an empty one is obvious the first
     * time the user opens the item.
     */
    private fun parseExpiry(raw: String): Pair<Int?, Int?> {
        val digits = raw.filter(Char::isDigit)
        val (m, y) = when {
            // Year-first is only distinguishable because a month can never exceed 12.
            digits.length == 6 && digits.take(4).toInt() > 12 ->
                digits.drop(4).toInt() to digits.take(4).toInt()
            digits.length == 6 -> digits.take(2).toInt() to digits.drop(2).toInt()
            digits.length == 4 -> digits.take(2).toInt() to 2000 + digits.drop(2).toInt()
            else -> return null to null
        }
        return if (m in 1..12) m to y else null to null
    }

    /**
     * The item this save should update, or null to add a new one.
     *
     * A proposal captured while the vault was locked carries no match, because [SaveHandler] had
     * nothing to search. Re-resolving here means the unlock detour updates the existing login
     * rather than leaving a second copy of it behind. On the already-unlocked path this repeats a
     * lookup SaveHandler already did and returns the same answer.
     */
    private fun resolveExisting(entry: PendingSaveStore.Entry): UUID? =
        entry.existingItemId ?: SaveHandler.updateCandidate(
            // Deliberately NOT wrapped in `runCatching { }.getOrNull()`. A lookup that *failed* is
            // not a lookup that found nothing, and collapsing the two meant a repository error —
            // the vault re-locking mid-save being the realistic one — read as "no such login" and
            // saved a second copy of a password the user already had. Two entries for one account
            // is the failure mode a password manager can least afford: the next password change
            // takes on one of them and autofill keeps offering the other.
            //
            // Letting it throw puts it in the same `runCatching` as the save itself, which already
            // shows "Couldn't save" and returns RESULT_CANCELED. Doing nothing and saying so beats
            // doing the wrong thing quietly.
            matches = repository.findMatching(
                webDomain = entry.webDomain,
                packageName = entry.packageName.takeIf { entry.webDomain == null },
            ).mapNotNull { item -> (item.content as? Login)?.let { item to it } },
            // The same rule SaveHandler applied, so a parent domain's login is not replaced here
            // either (2026-10-02).
            webDomain = entry.webDomain,
            username = entry.username,
        )?.first?.id

    private fun discard() {
        resolved = true
        token?.let(PendingSaveStore::discard)
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_RESOLVED, resolved)
    }

    override fun onDestroy() {
        if (isFinishing && !resolved) token?.let(PendingSaveStore::discard)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TOKEN = "com.localkeep.autofill.SAVE_TOKEN"
        internal const val STATE_RESOLVED = "com.localkeep.autofill.SAVE_RESOLVED"

        /** Whether the instance this one replaces had already saved or discarded its entry. */
        internal fun saveAlreadyResolved(savedInstanceState: Bundle?): Boolean =
            savedInstanceState?.getBoolean(STATE_RESOLVED, false) == true

        fun intent(context: Context, token: UUID): Intent =
            Intent(context, SaveConfirmActivity::class.java).apply {
                data = Uri.parse("localkeep-autofill://save/$token")
                putExtra(EXTRA_TOKEN, token.toString())
            }
    }
}
