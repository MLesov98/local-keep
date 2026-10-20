package com.localkeep.autofill

import com.localkeep.core.vault.AppSignatureCache

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.localkeep.core.ui.SecureActivity
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.VaultRepository
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.launch

/** One-time explicit browser-package/domain trust gate. */
@AndroidEntryPoint
class ConfirmDomainActivity : SecureActivity() {
    @Inject
    lateinit var repository: VaultRepository

    @Inject
    lateinit var appSignatureCache: AppSignatureCache

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)
        val fields = fieldsFromExtras(intent)
        val isNewPasswordForm = intent.getBooleanExtra(EXTRA_NEW_PASSWORD_FORM, false)
        if (packageName.isNullOrBlank() || webDomain.isNullOrBlank() || fields.isEmpty()) {
            cancel()
            return
        }

        // The app's own dialog, not a platform LinearLayout — see [ConfirmDomainDialog]. Outside
        // `setContent` so the coroutine behind Allow can dim the button without the composition
        // owning state that has to survive it.
        var working by mutableStateOf(false)
        setContent {
            LocalKeepTheme {
                ConfirmDomainDialog(
                    packageName = packageName,
                    webDomain = webDomain,
                    working = working,
                    onAllow = {
                        working = true
                        allow(packageName, webDomain, fields, isNewPasswordForm)
                    },
                    onDeny = ::cancel,
                )
            }
        }
        // Kept from the platform layout this replaced: an autofill consent must never itself be a
        // fill target, or the service is offering to fill its own gate.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    private fun allow(
        packageName: String,
        webDomain: String,
        fields: List<FillableField>,
        isNewPasswordForm: Boolean,
    ) {
        lifecycleScope.launch {
            val trusted =
                appSignatureCache.verifyOrPin(packageName) &&
                    appSignatureCache.confirmDomain(packageName, webDomain)
            if (!trusted) {
                cancel()
                return@launch
            }
            // The vault can lock between this row being placed in the FillResponse and the user
            // tapping Allow: the prompt sits in a browser while the auto-lock timer runs on wall
            // clock, and the ACTION_SCREEN_OFF receiver locks immediately. `findMatching` then
            // fails its `check(state is LockState.Unlocked)`, and an uncaught exception in a
            // lifecycleScope coroutine on the main dispatcher takes the process down on top of a
            // third-party browser. AuthActivity wraps the identical call already; this one did not.
            //
            // Distinguished from a null return on purpose: null is the legitimate "nothing to
            // offer" answer and still ends the gate quietly, while a throw means the vault could
            // not be read, and re-gating on unlock is what turns that into something the user can
            // finish rather than a tap that does nothing.
            val response = runCatching {
                FillResponseFactory.buildUnlockedResponse(
                    context = this@ConfirmDomainActivity,
                    repository = repository,
                    appSignatureCache = appSignatureCache,
                    fields = fields,
                    webDomain = webDomain,
                    packageName = packageName,
                    inlineSpec = inlineSpecFromExtras(intent),
                    isNewPasswordForm = isNewPasswordForm,
                )
            }.getOrElse {
                FillResponseFactory.unlockGate(
                    context = this@ConfirmDomainActivity,
                    fields = fields,
                    webDomain = webDomain,
                    packageName = packageName,
                )
            }
            if (response == null) {
                cancel()
                return@launch
            }
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response),
            )
            finish()
        }
    }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "com.localkeep.autofill.CONFIRM_PACKAGE_NAME"
        private const val EXTRA_WEB_DOMAIN = "com.localkeep.autofill.CONFIRM_WEB_DOMAIN"
        private const val EXTRA_USERNAME_ID = "com.localkeep.autofill.CONFIRM_USERNAME_ID"
        private const val EXTRA_PASSWORD_IDS = "com.localkeep.autofill.CONFIRM_PASSWORD_IDS"
        private const val EXTRA_TOTP_ID = "com.localkeep.autofill.CONFIRM_TOTP_ID"
        private const val EXTRA_URL_ID = "com.localkeep.autofill.CONFIRM_URL_ID"
        private const val EXTRA_CARD_NUMBER_ID = "com.localkeep.autofill.CONFIRM_CARD_NUMBER_ID"
        private const val EXTRA_CARD_EXPIRY_ID = "com.localkeep.autofill.CONFIRM_CARD_EXPIRY_ID"
        private const val EXTRA_CARD_SECURITY_CODE_ID =
            "com.localkeep.autofill.CONFIRM_CARD_SECURITY_CODE_ID"
        private const val EXTRA_CARD_HOLDER_ID = "com.localkeep.autofill.CONFIRM_CARD_HOLDER_ID"

        /**
         * Whether the form this gate was raised over is creating a password rather than recalling
         * one. Carried across the round trip for the same reason AuthActivity carries it: the
         * response is rebuilt on the other side from these extras alone, and without it the
         * generator offer defaults to a recalled-password answer even when hasStoredMatch is true.
         */
        private const val EXTRA_NEW_PASSWORD_FORM = "com.localkeep.autofill.CONFIRM_NEW_PASSWORD_FORM"

        /**
         * The keyboard's inline-suggestion spec from the fill request this gate was raised over,
         * carried for the reason AuthActivity carries its own: the response is rebuilt after Allow
         * from these extras alone, and without a spec every row in it is dropdown-only — dropped
         * from the strip whenever the platform renders it inline. The platform's
         * `EXTRA_INLINE_SUGGESTIONS_REQUEST` arrives only as a fill-in, which this gate's immutable
         * PendingIntent ignores.
         */
        private const val EXTRA_INLINE_SPEC = "com.localkeep.autofill.CONFIRM_INLINE_SPEC"

        fun intent(
            context: Context,
            fields: List<FillableField>,
            webDomain: String,
            packageName: String,
            isNewPasswordForm: Boolean = false,
            inlineSpec: Any? = null,
        ): Intent = Intent(context, ConfirmDomainActivity::class.java).apply {
            data = Uri.parse("localkeep-autofill://confirm-domain/${UUID.randomUUID()}")
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_WEB_DOMAIN, webDomain)
            putExtra(EXTRA_USERNAME_ID, fields.firstOrNull { it.type == FieldType.Username }?.id)
            // Every password id, not the first: mirrors AuthActivity — a sign-up form's confirmation
            // box has to survive this round trip too, or the response rebuilt after Allow can only
            // reach half of it.
            putExtra(
                EXTRA_PASSWORD_IDS,
                fields.filter { it.type == FieldType.Password }.map { it.id }.toTypedArray(),
            )
            putCurrentPasswordIds(fields)
            putExtra(EXTRA_TOTP_ID, fields.firstOrNull { it.type == FieldType.Totp }?.id)
            putExtra(EXTRA_URL_ID, fields.firstOrNull { it.type == FieldType.Url }?.id)
            // Card fields carry no vault value across this gate — AutofillIds are opaque view
            // handles, and the card content is resolved later, past a challenge, straight from the
            // repository — but without their ids surviving, a card-only checkout form has no fields
            // at all on the other side of Allow, and fieldsFromExtras returning empty cancels the
            // gate outright.
            putExtra(EXTRA_CARD_NUMBER_ID, fields.firstOrNull { it.type == FieldType.CardNumber }?.id)
            putExtra(EXTRA_CARD_EXPIRY_ID, fields.firstOrNull { it.type == FieldType.CardExpiry }?.id)
            putExtra(
                EXTRA_CARD_SECURITY_CODE_ID,
                fields.firstOrNull { it.type == FieldType.CardSecurityCode }?.id,
            )
            putExtra(EXTRA_CARD_HOLDER_ID, fields.firstOrNull { it.type == FieldType.CardHolder }?.id)
            putExtra(EXTRA_NEW_PASSWORD_FORM, isNewPasswordForm)
            if (inlineSpec != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InlineSuggestions.putSpec(this, EXTRA_INLINE_SPEC, inlineSpec)
            }
        }

        /** The keyboard's spec [intent] carried, or null — below API 30 there never is one. */
        internal fun inlineSpecFromExtras(intent: Intent): Any? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InlineSuggestions.specFrom(intent, EXTRA_INLINE_SPEC)
            } else {
                null
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
