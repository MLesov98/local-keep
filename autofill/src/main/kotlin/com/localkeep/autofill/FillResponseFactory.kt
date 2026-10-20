package com.localkeep.autofill

import com.localkeep.core.vault.AppSignatureCache

import android.app.PendingIntent
import android.content.Context
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.SaveInfo
import com.localkeep.core.model.Card
import com.localkeep.core.model.Login
import com.localkeep.core.vault.VaultRepository

/** Single unlocked-vault dataset assembly path for service and auth round-trips. */
internal object FillResponseFactory {
    suspend fun buildUnlockedResponse(
        context: Context,
        repository: VaultRepository,
        appSignatureCache: AppSignatureCache,
        fields: List<FillableField>,
        webDomain: String?,
        packageName: String?,
        inlineSpec: Any?,
        isNewPasswordForm: Boolean = false,
    ): FillResponse? {
        if (fields.isEmpty()) return null

        val usernameField = fields.firstOrNull { it.type == FieldType.Username }
        val passwordField = fields.firstOrNull { it.type == FieldType.Password }
        // Declaring SaveInfo is what makes the platform offer "Save to LocalKeep?" at all, so the
        // OS's own credential screens have to be excluded here rather than later — filtering only
        // in onSaveRequest would still show the offer and then quietly do nothing.
        val saveInfo = if (SystemCredentialUi.shouldSkipSave(packageName, webDomain)) {
            null
        } else {
            DatasetBuilder.buildSaveInfo(
                usernameField,
                passwordField,
                fields.firstOrNull { it.type == FieldType.Url },
                cardFields = fields.filter { it.type.isCard },
                otherPasswordFields = fields.filter { it.type == FieldType.Password }.drop(1),
            )
        }
        // A WebView/browser package hosts many unrelated sites. Once a verified web domain is
        // present, match only URI-scoped credentials; package-scoped credentials are native-only.
        val matches = repository.findMatching(
            webDomain = webDomain,
            packageName = packageName.takeIf { webDomain == null },
        )
        // A browser whose package we cannot name is a caller we cannot attest, so nothing from
        // the vault is offered and neither is a suggestion row that would reach it. The save offer
        // survives, because capturing what the user types is not a claim about who they are typing
        // it into — and dropping it here used to mean a login typed in a browser was silently never
        // offered for saving at all.
        if (webDomain != null && packageName == null) return saveOnly(saveInfo)

        // A checkout form usually has no stored login for the site, so returning here on an empty
        // login match is what kept cards from ever being offered — the vault stored them and the
        // one code path that could hand them back gave up before reaching them. Cards still fall
        // through to the same caller attestation below: `verifyOrPin` and the browser
        // domain-confirmation gate apply to a card row exactly as they do to a credential row.
        val hasCardFields = fields.any { it.type.isCard }
        if (matches.isEmpty() && !hasCardFields) {
            // Nothing stored for this site or app — the case the platform manager answers with
            // "Use suggested password" and its own entry point, and the case this service used to
            // answer with an empty menu. `verifyOrPin` is deliberately not reached here: it pins a
            // signature on first sight, and running it for every app the user ever taps a text
            // field in would pin apps the vault holds nothing for. With nothing on offer there is
            // nothing to protect yet; if the user goes on to pick an entry by hand, the picker runs
            // the check before it fills, and that is where such an app gets pinned.
            val suggestions = suggestionDatasets(
                context = context,
                fields = fields,
                webDomain = webDomain,
                packageName = packageName,
                hasStoredMatch = false,
                isNewPasswordForm = isNewPasswordForm,
                inlineSpec = inlineSpec,
            )
            if (suggestions.isEmpty()) return saveOnly(saveInfo)
            return FillResponse.Builder().apply {
                suggestions.forEach(::addDataset)
                saveInfo?.let(::setSaveInfo)
            }.build()
        }
        // Pinned on offer, not on fill, and on purpose. The non-reprompt rows built below carry
        // the username and password values inside this response; once it is returned, the
        // platform hands them to the app on a tap and LocalKeep is never called again. A pin
        // deferred to "when the user fills" would therefore land after the first delivery, if it
        // could land at all. Pinning here fixes the signer before any credential can reach it, so
        // a later signer under this package name is refused. The cost is that an app shows up in
        // Settings -> Trusted apps as soon as it is offered a saved login (or a card, on a
        // checkout form), whether or not the user then fills; the screen says "signature
        // remembered" for that reason rather than calling the app trusted, and Forget is undone
        // by the next such offer.
        if (packageName != null && !appSignatureCache.verifyOrPin(packageName)) {
            return saveOnly(saveInfo)
        }
        if (
            webDomain != null &&
            packageName != null &&
            !appSignatureCache.isDomainConfirmed(packageName, webDomain)
        ) {
            return confirmationGate(
                context,
                fields,
                webDomain,
                packageName,
                inlineSpec,
                saveInfo,
                isNewPasswordForm,
            )
        }

        val candidates = if (usernameField == null && passwordField != null) {
            val pending = AutofillSessionCache.recall(packageName, webDomain)
            pending
                ?.let { entry -> matches.filter { it.id == entry.itemId } }
                ?.takeIf(List<*>::isNotEmpty)
                ?: matches
        } else {
            matches
        }

        val datasets = candidates.flatMap { item ->
            val repromptPendingIntent = item.takeIf { it.reprompt }?.let {
                PendingIntent.getActivity(
                    context,
                    item.id.hashCode(),
                    AuthActivity.repromptIntent(context, item, fields, webDomain, packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            DatasetBuilder.build(context, item, fields, repromptPendingIntent, inlineSpec)
        }.plus(cardDatasets(context, repository, fields, webDomain, packageName, inlineSpec))
            .cappedForOneResponse()
        // Save-only rather than null: the vault holds a match we could not turn into a dataset
        // (a non-login item type, say), which is no reason to withdraw the offer to save what the
        // user is typing right now.
        if (datasets.isEmpty()) return saveOnly(saveInfo)

        if (usernameField != null && candidates.size == 1) {
            (candidates.single().content as? Login)?.let {
                AutofillSessionCache.remember(
                    packageName = packageName,
                    webDomain = webDomain,
                    itemId = candidates.single().id,
                )
            }
        }

        // Appended after the stored credentials, never in front of them: on a sign-in form the
        // row the user came for is the one they already have, and a generator offer above it would
        // be the platform manager's worst habit rather than its best one.
        val suggestions = suggestionDatasets(
            context = context,
            fields = fields,
            webDomain = webDomain,
            packageName = packageName,
            hasStoredMatch = true,
            isNewPasswordForm = isNewPasswordForm,
            inlineSpec = inlineSpec,
        )

        return FillResponse.Builder().apply {
            datasets.forEach(::addDataset)
            suggestions.forEach(::addDataset)
            saveInfo?.let(::setSaveInfo)
        }.build()
    }

    /**
     * A response that offers nothing to fill but still declares the save.
     *
     * Every path that declines to suggest a credential ends here rather than at `null`. The two are
     * not the same answer: `null` withdraws the platform's "Save to LocalKeep?" prompt as well, so
     * a user who could not be offered a stored login also silently lost the offer to store the one
     * they were in the middle of typing.
     */
    private fun saveOnly(saveInfo: SaveInfo?): FillResponse? =
        saveInfo?.let { FillResponse.Builder().setSaveInfo(it).build() }

    /**
     * Every stored card, as authentication rows, when the form has card fields.
     *
     * Not matched against the site, deliberately — unlike a login, a payment card is not scoped to
     * one domain. A card works at every checkout, so filtering by URI would hide the user's own
     * card from the shop they are standing in, and the card items a vault holds are few enough to
     * offer whole. What narrows the list is the *form*: no card fields, no card rows.
     *
     * Every row is authenticated, with no exception for `reprompt = false`. A `Card` carries a CVV
     * and a PIN, both in `ALWAYS_REAUTH_FIELDS`, and that set is a statement about the value rather
     * than about the item's settings — the per-item flag can lower a login's bar and must not be
     * able to lower this one. Read through `visibilitySnapshot()` so a concealed card is absent
     * here as it is everywhere else.
     */
    private fun cardDatasets(
        context: Context,
        repository: VaultRepository,
        fields: List<FillableField>,
        webDomain: String?,
        packageName: String?,
        inlineSpec: Any?,
    ): List<Dataset> {
        if (fields.none { it.type.isCard }) return emptyList()
        // A caller we cannot name is a caller we cannot attest, and the rule three lines above the
        // login path says nothing from the vault is offered to one. Card rows are from the vault:
        // each draws the item's title, which `SaveConfirmActivity.saveCard()` builds as
        // "<domain> ****<last4>" — so an unattested caller would be handed the last four digits of
        // every stored card, even though no value crosses (every row authenticates in AuthActivity).
        //
        // The login guard above returns early on `webDomain != null && packageName == null`; the
        // both-null caller falls past it because `hasCardFields` keeps it out of the empty-match
        // branch, and then past `verifyOrPin` and the domain gate, both of which are conditional on
        // the very things that are null. Reaching here with a null package therefore means exactly
        // the both-null case, and it is the one the chain does not otherwise cover. Found by the
        // F15 QA persona, 2026-08-30, as an invariant/comment mismatch — it could not construct the
        // request on an API 35 image, so this closes a stated rule rather than a proven exposure.
        if (packageName == null) return emptyList()
        val cards = runCatching {
            repository.visibilitySnapshot().liveItems.filter { it.content is Card }
        }.getOrDefault(emptyList())
        return cards.mapNotNull { item ->
            val pendingIntent = PendingIntent.getActivity(
                context,
                item.id.hashCode(),
                AuthActivity.repromptIntent(context, item, fields, webDomain, packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            DatasetBuilder.buildCardAuthDataset(context, item, fields, pendingIntent, inlineSpec)
        }
    }

    /**
     * LocalKeep's own entry points on a form: generate a password, or search the vault by hand.
     *
     * These are what make tapping a field produce something on a site the vault has never seen —
     * the behaviour a user expects after choosing this app over the platform's manager. Both are
     * authenticated datasets, so no vault content crosses into the response; the value only exists
     * once [PickerActivity] hands one back.
     */
    private fun suggestionDatasets(
        context: Context,
        fields: List<FillableField>,
        webDomain: String?,
        packageName: String?,
        hasStoredMatch: Boolean,
        isNewPasswordForm: Boolean,
        inlineSpec: Any?,
    ): List<Dataset> = buildList {
        val passwordFields = fields.filter { it.type == FieldType.Password }
        val usernameField = fields.firstOrNull { it.type == FieldType.Username }
        val totpField = fields.firstOrNull { it.type == FieldType.Totp }

        if (
            SuggestionPolicy.offerGeneratedPassword(
                hasPasswordField = passwordFields.isNotEmpty(),
                hasStoredMatch = hasStoredMatch,
                isNewPasswordForm = isNewPasswordForm,
            )
        ) {
            // Password boxes only. A generator row attached to the username field would appear when
            // the user taps the box they are about to type their email into. The same boxes
            // PickerActivity fills, or the platform drops the values it hands back.
            actionRow(
                context = context,
                mode = PickerActivity.MODE_GENERATE,
                title = context.getString(R.string.autofill_suggest_generate_title),
                subtitle = context.getString(R.string.autofill_suggest_generate_subtitle),
                targetIds = SuggestionPolicy.generatedPasswordTargets(fields).map { it.id },
                fields = fields,
                webDomain = webDomain,
                packageName = packageName,
                inlineSpec = inlineSpec,
            )?.let(::add)
        }

        if (
            SuggestionPolicy.offerVaultSearch(
                hasUsernameField = usernameField != null,
                hasPasswordField = passwordFields.isNotEmpty(),
            )
        ) {
            actionRow(
                context = context,
                mode = PickerActivity.MODE_SEARCH,
                title = context.getString(R.string.autofill_suggest_search_title),
                subtitle = context.getString(R.string.autofill_suggest_search_subtitle),
                // The one-time-code box is included even though it is not what decides the offer:
                // the dataset the picker hands back carries the item's 2FA code, and the platform
                // drops a value for an id the authenticated dataset never declared.
                targetIds = listOfNotNull(usernameField?.id) +
                    passwordFields.map { it.id } +
                    listOfNotNull(totpField?.id),
                fields = fields,
                webDomain = webDomain,
                packageName = packageName,
                inlineSpec = inlineSpec,
            )?.let(::add)
        }
    }

    /**
     * One authenticated row: no value of its own, a [PickerActivity] behind it.
     *
     * Internal, not private, so a test can read the row it builds.
     */
    internal fun actionRow(
        context: Context,
        mode: String,
        title: String,
        subtitle: String,
        targetIds: List<android.view.autofill.AutofillId>,
        fields: List<FillableField>,
        webDomain: String?,
        packageName: String?,
        inlineSpec: Any?,
    ): Dataset? {
        if (targetIds.isEmpty()) return null
        val pendingIntent = PendingIntent.getActivity(
            context,
            "picker|$mode|$packageName|$webDomain".hashCode(),
            PickerActivity.intent(context, mode, fields, webDomain, packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val presentation = DatasetBuilder.rowPresentation(context, title, subtitle)
        return Dataset.Builder().apply {
            targetIds.forEach { setValue(it, null, presentation) }
            setAuthentication(pendingIntent.intentSender)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                inlineSpec != null
            ) {
                InlineSuggestions.applyTo(
                    builder = this,
                    title = title,
                    subtitle = subtitle,
                    // Never the picker itself: a long-press opened it outside the fill flow.
                    attributionIntent = DatasetBuilder.attributionIntent(context, "picker/$mode"),
                    spec = inlineSpec,
                )
            }
        }.build()
    }

    /**
     * A response whose only action is "Unlock LocalKeep".
     *
     * For when the vault locks *between* a gate being offered and the user acting on it: the row
     * sits in a browser while the auto-lock timer runs on wall clock, and the ACTION_SCREEN_OFF
     * receiver locks immediately. Reading the vault then throws, and the alternative to this is
     * either an uncaught exception on top of a third-party app or a tap that visibly does nothing.
     */
    fun unlockGate(
        context: Context,
        fields: List<FillableField>,
        webDomain: String?,
        packageName: String?,
    ): FillResponse {
        val intent = PendingIntent.getActivity(
            context,
            "unlock|$packageName|$webDomain".hashCode(),
            AuthActivity.unlockIntent(context, fields, webDomain, packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return FillResponse.Builder()
            .setAuthentication(
                fields.map { it.id }.toTypedArray(),
                intent.intentSender,
                DatasetBuilder.rowPresentation(
                    context,
                    context.getString(R.string.autofill_unlock_title),
                    null,
                ),
            )
            .build()
    }

    private fun confirmationGate(
        context: Context,
        fields: List<FillableField>,
        webDomain: String,
        packageName: String,
        inlineSpec: Any?,
        saveInfo: SaveInfo?,
        isNewPasswordForm: Boolean,
    ): FillResponse {
        val intent = PendingIntent.getActivity(
            context,
            "$packageName|$webDomain".hashCode(),
            ConfirmDomainActivity.intent(context, fields, webDomain, packageName, isNewPasswordForm, inlineSpec),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return FillResponse.Builder().apply {
            // The pairing is unconfirmed, so nothing is offered to fill — but the user may still be
            // signing in, and the save offer has no bearing on whether this browser may be trusted
            // to fill for this site.
            saveInfo?.let(::setSaveInfo)
            val ids = fields.map { it.id }.toTypedArray()
            val confirmAppSiteTitle = context.getString(R.string.autofill_confirm_app_site_title)
            val presentation = DatasetBuilder.rowPresentation(
                context,
                confirmAppSiteTitle,
                webDomain,
            )
            val inlined = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                inlineSpec != null &&
                InlineSuggestions.applyAuthentication(
                    builder = this,
                    ids = ids,
                    intentSender = intent.intentSender,
                    remoteViews = presentation,
                    title = confirmAppSiteTitle,
                    subtitle = webDomain,
                    attributionIntent = DatasetBuilder.attributionIntent(context, "confirm-domain"),
                    spec = inlineSpec,
                )
            if (!inlined) setAuthentication(ids, intent.intentSender, presentation)
        }.build()
    }
}

/**
 * The most stored-credential rows one fill response carries (2026-10-02).
 *
 * A response crosses a single binder transaction, and every row carries two RemoteViews plus an
 * inline slice, so a host with a hundred-odd matching logins could build a response the platform
 * cannot deliver — `FillCallback.onSuccess` then throws in this process. The ceiling is an estimate
 * with a wide margin, not a measurement. Nothing becomes unreachable: the "Search vault" row is
 * appended after the cap on every form with a username or password box.
 */
internal const val MAX_FILL_DATASETS = 20

internal fun <T> List<T>.cappedForOneResponse(): List<T> = take(MAX_FILL_DATASETS)
