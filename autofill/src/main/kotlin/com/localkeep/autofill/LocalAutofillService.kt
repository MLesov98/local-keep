package com.localkeep.autofill

import com.localkeep.core.vault.AppSignatureCache

import android.app.PendingIntent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val LOG_TAG = "LocalAutofill"

@AndroidEntryPoint
class LocalAutofillService : AutofillService() {
    @Inject
    lateinit var lockManager: VaultLockManager

    @Inject
    lateinit var repository: VaultRepository

    @Inject
    lateinit var appSignatureCache: AppSignatureCache

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }
        // Resolved across every fill context, most recent first — not just the last one.
        // `activityComponent` is nullable, and a partitioned or multi-step form can end with a
        // context that carries no component while an earlier one names the package perfectly well.
        // Reading only the last one then produced a null packageName, which the attestation guard
        // in FillResponseFactory correctly refuses to serve — so the user was silently offered
        // nothing for credentials that exist, on a caller we could in fact identify.
        //
        // SaveHandler.extract already resolved it this way; only the fill path did not, so the
        // same session could save a credential and then decline to fill it.
        val packageName = request.fillContexts.resolvePackageName()

        // Never autofill LocalKeep itself. This also prevents recursive auth rows over the
        // autofill gate's own master-password field on platform builds that still expose it.
        // Uses the same resolution, so a context without a component cannot smuggle us past it.
        if (packageName == this.packageName) {
            callback.onSuccess(null)
            return
        }
        val fields = FieldDetector.detect(structure)
        if (fields.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        val webDomain = FieldDetector.detectWebDomain(structure)
        // Answered here, while the AssistStructure is in hand: everything downstream works from
        // the flattened field list, which cannot tell a sign-up form from a sign-in one.
        val isNewPasswordForm = FieldDetector.isNewPasswordForm(structure, fields)
        val inlineSpec: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            InlineSuggestions.firstSpec(request)
        } else {
            null
        }

        when (lockManager.lockState.value) {
            // Nothing to fill once unlocked means nothing to unlock for (2026-10-02). The save offer
            // goes with it, which loses nothing: a field list with no credential or card box
            // declares no SaveInfo either.
            LockState.Locked -> callback.onSuccess(
                if (SuggestionPolicy.offerUnlockGate(fields.map { it.type })) {
                    lockedResponse(fields, webDomain, packageName, inlineSpec, isNewPasswordForm)
                } else {
                    null
                },
            )

            is LockState.Unlocked -> {
                val job: Job = serviceScope.launch {
                    val result = runCatching {
                        FillResponseFactory.buildUnlockedResponse(
                            context = this@LocalAutofillService,
                            repository = repository,
                            appSignatureCache = appSignatureCache,
                            fields = fields,
                            webDomain = webDomain,
                            packageName = packageName,
                            inlineSpec = inlineSpec,
                            isNewPasswordForm = isNewPasswordForm,
                        )
                    }
                    if (cancellationSignal.isCanceled) return@launch
                    // A null response is a real answer — no matches, or an attestation refusal —
                    // and onSuccess(null) is the honest way to say it. A *thrown* response build is
                    // not that, and `getOrNull()` used to flatten the two together: the user was
                    // shown no suggestions and given no reason, for credentials that do exist.
                    //
                    // Delivery is guarded too (2026-10-02). It ran outside the runCatching above, on
                    // a scope with no exception handler, and FillCallback rethrows a failed binder
                    // call — an oversized response — as a RuntimeException: uncaught there, it killed
                    // this process on top of the app being filled.
                    deliverGuarded(onError = { logFailure("fill response delivery failed", it) }) {
                        result.fold(
                            onSuccess = callback::onSuccess,
                            onFailure = {
                                logFailure("fill response build failed", it)
                                callback.onFailure(
                                    this@LocalAutofillService.getString(R.string.autofill_service_fill_failed),
                                )
                            },
                        )
                    }
                }
                cancellationSignal.setOnCancelListener(job::cancel)
            }
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // The confirmation MUST be launched by the platform via onSuccess(intentSender): a service
        // is a background context, so calling startActivity here is silently blocked on Android 10+
        // and the credential is lost while the user is told it was saved.
        //
        // A locked vault no longer skips the save either — we can't dedup without reading the
        // vault, but SaveConfirmActivity still runs and reports honestly instead of dropping it.
        val unlocked = lockManager.lockState.value is LockState.Unlocked
        runCatching {
            SaveHandler.handle(
                this,
                repository,
                request,
                vaultUnlocked = unlocked,
            )
        }.fold(
            // handle() returns null for "nothing to confirm" — an unparseable request, or a
            // credential already stored verbatim. That is a completed save request, so onSuccess()
            // is right. A throw is the opposite of that, and `getOrNull()` made the two identical:
            // the platform told the user the login was saved while it had been dropped.
            onSuccess = { intentSender ->
                if (intentSender != null) callback.onSuccess(intentSender) else callback.onSuccess()
            },
            onFailure = {
                logFailure("save request failed", it)
                callback.onFailure(getString(R.string.autofill_service_save_failed))
            },
        )
    }

    /**
     * Class name only, never the message or the throwable. An exception raised while building a
     * response or reading the vault can carry item titles, usernames, or domains in its message,
     * and logcat is readable by anyone with adb — so the one thing worth logging is which failure
     * class fired, which is enough to find it in the source.
     */
    private fun logFailure(what: String, cause: Throwable) {
        Log.w(LOG_TAG, "$what: ${cause.javaClass.name}")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun lockedResponse(
        fields: List<FillableField>,
        webDomain: String?,
        packageName: String?,
        inlineSpec: Any?,
        isNewPasswordForm: Boolean,
    ): FillResponse {
        val unlockIntent = PendingIntent.getActivity(
            this,
            0,
            AuthActivity.unlockIntent(this, fields, webDomain, packageName, isNewPasswordForm, inlineSpec),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // A locked vault still has to declare SaveInfo. Without it the platform never sends an
        // onSaveRequest at all, so signing up for an account while LocalKeep happened to be locked
        // silently offered nothing — the credential was simply lost. SaveConfirmActivity unlocks
        // in place, so the lock state is no reason to decline the offer.
        val saveInfo = if (SystemCredentialUi.shouldSkipSave(packageName, webDomain)) {
            null
        } else {
            DatasetBuilder.buildSaveInfo(
                usernameField = fields.firstOrNull { it.type == FieldType.Username },
                passwordField = fields.firstOrNull { it.type == FieldType.Password },
                urlField = fields.firstOrNull { it.type == FieldType.Url },
                cardFields = fields.filter { it.type.isCard },
                otherPasswordFields = fields.filter { it.type == FieldType.Password }.drop(1),
            )
        }
        return FillResponse.Builder().apply {
            saveInfo?.let(::setSaveInfo)
            val ids = fields.map { it.id }.toTypedArray()
            val (unlockTitle, unlockSubtitle) = DatasetBuilder.unlockPresentationText(this@LocalAutofillService)
            val presentation = DatasetBuilder.rowPresentation(this@LocalAutofillService, unlockTitle, unlockSubtitle)
            val inlined = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                inlineSpec != null &&
                InlineSuggestions.applyAuthentication(
                    builder = this,
                    ids = ids,
                    intentSender = unlockIntent.intentSender,
                    remoteViews = presentation,
                    title = unlockTitle,
                    subtitle = unlockSubtitle,
                    // Never the unlock gate: a long-press ran it outside the fill flow (2026-10-02).
                    attributionIntent = DatasetBuilder.attributionIntent(this@LocalAutofillService, "unlock"),
                    spec = inlineSpec,
                )
            if (!inlined) setAuthentication(ids, unlockIntent.intentSender, presentation)
        }.build()
    }
}

/**
 * The package behind this request, taken from the most recent fill context that names one.
 *
 * Most-recent-first because a multi-step form's later contexts describe where the user actually is;
 * an earlier one is a fallback for identity, not a preference.
 */
internal fun List<android.service.autofill.FillContext>.resolvePackageName(): String? =
    asReversed().firstNotNullOfOrNull { it.structure.activityComponent?.packageName }

/**
 * Runs [deliver] — a `FillCallback` answer — and hands a [RuntimeException] it throws to [onError]
 * instead of letting it reach the thread. Nothing is retried: the callback marks itself answered
 * before the binder call that failed, so a second answer would only throw again.
 */
internal inline fun deliverGuarded(onError: (RuntimeException) -> Unit, deliver: () -> Unit) {
    try {
        deliver()
    } catch (e: RuntimeException) {
        onError(e)
    }
}
