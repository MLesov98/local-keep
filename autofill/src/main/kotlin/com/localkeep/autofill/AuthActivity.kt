package com.localkeep.autofill

import com.localkeep.core.vault.AppSignatureCache

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import androidx.lifecycle.lifecycleScope
import com.localkeep.core.model.Card
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.SecureActivity
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.UnlockResult
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultLockedException
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultSessionEndedException
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Secure password gate for locked-vault and per-item reprompt autofill. */
@AndroidEntryPoint
class AuthActivity : SecureActivity() {
    @Inject
    lateinit var lockManager: VaultLockManager

    @Inject
    lateinit var repository: VaultRepository

    @Inject
    lateinit var appSignatureCache: AppSignatureCache

    private lateinit var prompt: MasterPasswordPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getStringExtra(EXTRA_MODE)
        if (mode != MODE_UNLOCK && mode != MODE_REPROMPT) {
            cancel()
            return
        }
        val fields = fieldsFromExtras(intent)
        if (fields.isEmpty()) {
            cancel()
            return
        }

        // The row that launched this can outlive the session that built it, so the title has to be
        // chosen from the lock state rather than from the mode alone: a reprompt over a locked
        // vault is an unlock, and calling it "confirm it's you" would name a check that cannot run.
        val mustUnlock = autofillGateMustUnlock(
            mode = mode,
            unlocked = lockManager.lockState.value is LockState.Unlocked,
        )

        // This gate is master-password-only, and that asymmetry is deliberate rather than an
        // oversight — recorded here on 2026-09-13, after a sweep found it undocumented and had to
        // reconstruct the reason from scratch. `CredentialActivity` (Credential Manager) raises a
        // real `BiometricPrompt` and calls `VaultLockManager.unlockWithBiometric`; this one never
        // does, which is also why `AuthPromptMessage`'s BIOMETRIC_UNAVAILABLE arm cannot fire here.
        //
        // The reason is the process, not the class — both extend `SecureActivity`. A classic
        // Autofill gate is raised inside the target app's fill flow, where the working set is
        // tightest; G7 measured this side reporting "needs more memory" for a vault the main app
        // opens seconds later. A biometric unlock is not free of that: it unwraps the device key
        // and then opens the vault, so it lands in the same allocation the password path does.
        // Adding it here therefore needs a measurement on API 28/29, not just the prompt wiring —
        // which is why it is a scoped follow-up and not a line in this file.
        prompt = MasterPasswordPrompt(
            context = this,
            title = if (mustUnlock) {
                getString(R.string.autofill_unlock_title)
            } else {
                getString(R.string.autofill_auth_reprompt_title)
            },
        ) { password ->
            authenticate(
                mode = mode,
                password = password,
                fields = fields,
                webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN),
                packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME),
                itemId = intent.getStringExtra(EXTRA_ITEM_ID),
            )
        }
        setContentView(prompt.view)
    }

    private fun authenticate(
        mode: String,
        password: CharArray,
        fields: List<FillableField>,
        webDomain: String?,
        packageName: String?,
        itemId: String?,
    ) {
        lifecycleScope.launch {
            // Every failure used to collapse to `false` and be reported as "Couldn't authenticate",
            // which is the wrong-password string: a throttled attempt, an unreadable vault file and
            // a lock landing inside Argon2 all accused the user of mistyping. The unlock screen has
            // said what actually happened since `UnlockResult` was split — this gate never caught
            // up, and it is the one people meet inside another app, where there is no vault UI to
            // contradict it. Same reasoning as `LockState.kt`: someone holding the device can
            // observe all of these states directly, so naming them leaks nothing.
            val outcome = withContext(Dispatchers.Default) {
                try {
                    // Read again here, not once in `onCreate`: the vault can lock while the
                    // password is being typed into this very prompt.
                    val unlocked = lockManager.lockState.value is LockState.Unlocked
                    if (autofillGateMustUnlock(mode, unlocked)) {
                        lockManager.unlock(password).let { result ->
                            if (result.opened) AuthOutcome.Success else AuthOutcome.Failed(result)
                        }
                    } else if (lockManager.reauthenticate(password)) {
                        AuthOutcome.Success
                    } else {
                        AuthOutcome.Failed(UnlockResult.WRONG_CREDENTIAL)
                    }
                } catch (locked: VaultLockedException) {
                    AuthOutcome.Throttled(locked.retryAfterMs)
                } catch (_: VaultSessionEndedException) {
                    // The auto-lock timer fired while the reprompt sat open: the vault re-locked,
                    // it is not damaged. Naming it (rather than letting the generic catch call it
                    // VAULT_UNREADABLE, the corruption wording) is what stops a user restoring a
                    // backup for a healthy vault. Reachable only for a lock landing between the
                    // read above and `reauthenticate`'s own — which is why its "try again" is now
                    // true advice: the retry sees the lock and unlocks instead.
                    AuthOutcome.Failed(UnlockResult.INTERRUPTED)
                } catch (_: Exception) {
                    // Anything else is a fault in this gate, not a claim about the password.
                    AuthOutcome.Failed(UnlockResult.VAULT_UNREADABLE)
                }
            }
            password.fill('\u0000')

            if (outcome !is AuthOutcome.Success) {
                prompt.showError(authPromptMessage(outcome).resolve(this@AuthActivity))
                return@launch
            }

            when (mode) {
                MODE_UNLOCK -> finishWithResponse(fields, webDomain, packageName)
                MODE_REPROMPT -> finishWithDataset(itemId, fields)
            }
        }
    }

    private suspend fun finishWithResponse(
        fields: List<FillableField>,
        webDomain: String?,
        packageName: String?,
    ) {
        val response = runCatching {
            FillResponseFactory.buildUnlockedResponse(
                context = this,
                repository = repository,
                appSignatureCache = appSignatureCache,
                fields = fields,
                webDomain = webDomain,
                packageName = packageName,
                inlineSpec = inlineSpecFromExtras(intent),
                isNewPasswordForm = intent.getBooleanExtra(EXTRA_NEW_PASSWORD_FORM, false),
            )
        }.getOrNull()
        if (response == null) {
            cancel()
            return
        }
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response),
        )
        finish()
    }

    private fun finishWithDataset(itemId: String?, fields: List<FillableField>) {
        val id = itemId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        // Through the projection. The dataset that carried this id was itself built from the
        // projection, but the two are separated by a biometric prompt the user can sit on: a lease
        // can expire the other way, and a lock or a background-grace conceal can land in between.
        // Re-resolving raw would fill a concealed entry from a stale id.
        val item = runCatching {
            repository.visibilitySnapshot().liveItems.firstOrNull { it.id == id }
        }.getOrNull()
        // One dataset carrying every field, rather than `build(...).firstOrNull()`. The platform
        // takes exactly one Dataset back from a dataset authentication, and `build` splits its
        // output into a credentials row and a separate 2FA row — so taking the first of the two
        // dropped the one-time code after the user had already passed the challenge for it.
        val dataset = item?.let {
            // A card resolves here and only here. `buildCardAuthDataset` put a value-free, masked
            // row on the screen and pointed it at this activity; the stored number, holder, expiry
            // and CVV are read on this side of the challenge, from the repository, never having
            // travelled in an Intent extra or a presentation.
            if (it.content is Card) {
                DatasetBuilder.buildCardDataset(context = this, item = it, fields = fields)
            } else {
                DatasetBuilder.buildSingleDataset(context = this, item = it, fields = fields)
            }
        }
        if (dataset == null) {
            cancel()
            return
        }
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset),
        )
        finish()
    }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        const val MODE_UNLOCK = "unlock"
        const val MODE_REPROMPT = "reprompt"

        private const val EXTRA_MODE = "com.localkeep.autofill.MODE"
        private const val EXTRA_USERNAME_ID = "com.localkeep.autofill.USERNAME_ID"
        private const val EXTRA_PASSWORD_IDS = "com.localkeep.autofill.PASSWORD_IDS"
        private const val EXTRA_TOTP_ID = "com.localkeep.autofill.TOTP_ID"
        private const val EXTRA_URL_ID = "com.localkeep.autofill.URL_ID"

        // The card ids. Their absence made every card row a dead tap: FillResponseFactory points a
        // stored Card's authentication row at this activity, but the intent carried no card ids at
        // all, so `fields` came back empty. A card-only checkout cancelled before drawing anything;
        // a mixed form got as far as buildCardDataset, which filled nothing and cancelled there.
        // Character-for-character the bug ConfirmDomainActivityFieldsRoundTripTest describes and
        // fixed for its own activity, which is where the fix stopped.
        private const val EXTRA_CARD_NUMBER_ID = "com.localkeep.autofill.CARD_NUMBER_ID"
        private const val EXTRA_CARD_EXPIRY_ID = "com.localkeep.autofill.CARD_EXPIRY_ID"
        private const val EXTRA_CARD_SECURITY_CODE_ID = "com.localkeep.autofill.CARD_SECURITY_CODE_ID"
        private const val EXTRA_CARD_HOLDER_ID = "com.localkeep.autofill.CARD_HOLDER_ID"
        private const val EXTRA_WEB_DOMAIN = "com.localkeep.autofill.WEB_DOMAIN"
        private const val EXTRA_PACKAGE_NAME = "com.localkeep.autofill.PACKAGE_NAME"
        private const val EXTRA_ITEM_ID = "com.localkeep.autofill.ITEM_ID"

        /**
         * Whether the form this gate was raised over is creating a password rather than recalling
         * one. Carried across the unlock because the response is rebuilt on the other side of it
         * from these extras alone — the `AssistStructure` that answered the question is long gone —
         * and without it, unlocking on a sign-up form lost the generated-password suggestion the
         * same form offered a moment earlier.
         */
        private const val EXTRA_NEW_PASSWORD_FORM = "com.localkeep.autofill.NEW_PASSWORD_FORM"

        /**
         * The keyboard's inline-suggestion spec from the fill request this gate was raised over.
         *
         * Carried for the same reason as the flag above: the response is rebuilt from these extras
         * alone, and built without a spec every row in it is dropdown-only. The platform renders the
         * returned response inline whenever the keyboard is up and asking at that moment, and drops
         * every row without an inline presentation there — so the user unlocked and was shown
         * nothing at all (device pass, API 36, 2026-10-05). The platform does offer the request to
         * this gate, as `AutofillManager.EXTRA_INLINE_SUGGESTIONS_REQUEST`, but only by filling in
         * the launching intent, and this gate's PendingIntent is immutable: the app being filled is
         * the one that launches it.
         */
        private const val EXTRA_INLINE_SPEC = "com.localkeep.autofill.INLINE_SPEC"

        fun unlockIntent(
            context: Context,
            fields: List<FillableField>,
            webDomain: String?,
            packageName: String?,
            isNewPasswordForm: Boolean = false,
            inlineSpec: Any? = null,
        ): Intent = baseIntent(context, MODE_UNLOCK, fields, webDomain, packageName)
            .putExtra(EXTRA_NEW_PASSWORD_FORM, isNewPasswordForm)
            .apply {
                if (inlineSpec != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    InlineSuggestions.putSpec(this, EXTRA_INLINE_SPEC, inlineSpec)
                }
            }

        /** The keyboard's spec [unlockIntent] carried, or null — below API 30 there never is one. */
        internal fun inlineSpecFromExtras(intent: Intent): Any? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InlineSuggestions.specFrom(intent, EXTRA_INLINE_SPEC)
            } else {
                null
            }

        fun repromptIntent(
            context: Context,
            item: VaultItem,
            fields: List<FillableField>,
            webDomain: String?,
            packageName: String?,
        ): Intent = baseIntent(context, MODE_REPROMPT, fields, webDomain, packageName).apply {
            putExtra(EXTRA_ITEM_ID, item.id.toString())
        }

        private fun baseIntent(
            context: Context,
            mode: String,
            fields: List<FillableField>,
            webDomain: String?,
            packageName: String?,
        ): Intent = Intent(context, AuthActivity::class.java).apply {
            data = Uri.parse("localkeep-autofill://$mode/${UUID.randomUUID()}")
            putExtra(EXTRA_MODE, mode)
            putExtra(EXTRA_USERNAME_ID, fields.firstOrNull { it.type == FieldType.Username }?.id)
            // Every password id, not the first: a sign-up form's confirmation box has to survive
            // the unlock too, or the suggestion rebuilt on the other side can only reach half of it.
            putExtra(
                EXTRA_PASSWORD_IDS,
                fields.filter { it.type == FieldType.Password }.map { it.id }.toTypedArray(),
            )
            putCurrentPasswordIds(fields)
            putExtra(EXTRA_TOTP_ID, fields.firstOrNull { it.type == FieldType.Totp }?.id)
            putExtra(EXTRA_URL_ID, fields.firstOrNull { it.type == FieldType.Url }?.id)
            putExtra(EXTRA_CARD_NUMBER_ID, fields.firstOrNull { it.type == FieldType.CardNumber }?.id)
            putExtra(EXTRA_CARD_EXPIRY_ID, fields.firstOrNull { it.type == FieldType.CardExpiry }?.id)
            putExtra(
                EXTRA_CARD_SECURITY_CODE_ID,
                fields.firstOrNull { it.type == FieldType.CardSecurityCode }?.id,
            )
            putExtra(EXTRA_CARD_HOLDER_ID, fields.firstOrNull { it.type == FieldType.CardHolder }?.id)
            putExtra(EXTRA_WEB_DOMAIN, webDomain)
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }

        internal fun fieldsFromExtras(intent: Intent): List<FillableField> = buildList {
            intent.parcelableExtraCompat<AutofillId>(EXTRA_USERNAME_ID)
                ?.let { add(FillableField(it, FieldType.Username)) }
            addAll(intent.passwordFields(EXTRA_PASSWORD_IDS))
            intent.parcelableExtraCompat<AutofillId>(EXTRA_TOTP_ID)
                ?.let { add(FillableField(it, FieldType.Totp)) }
            intent.parcelableExtraCompat<AutofillId>(EXTRA_URL_ID)
                ?.let { add(FillableField(it, FieldType.Url)) }
            intent.parcelableExtraCompat<AutofillId>(EXTRA_CARD_NUMBER_ID)
                ?.let { add(FillableField(it, FieldType.CardNumber)) }
            intent.parcelableExtraCompat<AutofillId>(EXTRA_CARD_EXPIRY_ID)
                ?.let { add(FillableField(it, FieldType.CardExpiry)) }
            intent.parcelableExtraCompat<AutofillId>(EXTRA_CARD_SECURITY_CODE_ID)
                ?.let { add(FillableField(it, FieldType.CardSecurityCode)) }
            intent.parcelableExtraCompat<AutofillId>(EXTRA_CARD_HOLDER_ID)
                ?.let { add(FillableField(it, FieldType.CardHolder)) }
        }
    }
}

/**
 * Whether this attempt has to open the vault rather than re-verify one that is already open.
 *
 * A reprompt row can outlive the session that built it: the auto-lock timer runs on wall clock
 * while the row sits in another app's autofill menu — the race [PickerActivity] documents at its
 * own lock check — and a screen-off locks at once. [VaultLockManager.reauthenticate] throws
 * `VaultSessionEndedException` on a locked vault before it reaches any KDF or throttle, so a
 * reprompt that called it regardless answered every attempt with "the vault re-locked, try again"
 * on a control that could never pass, and nothing on this screen could unlock the vault. This was
 * the only one of the four autofill activities that never read `lockState`.
 *
 * Unlocking here relaxes nothing: it verifies the same master password against the same slot,
 * shares the same knowledge-factor backoff, and re-enters the duress-aware session that keeps a
 * concealed item out of `finishWithDataset`.
 */
internal fun autofillGateMustUnlock(mode: String, unlocked: Boolean): Boolean =
    mode == AuthActivity.MODE_UNLOCK || !unlocked
