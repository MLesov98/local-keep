package com.localkeep.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure.ViewNode
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.service.autofill.SaveRequest
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.VaultRepository
import java.net.URI

/** Extracts, deduplicates, then opens an explicit save/update confirmation. */
object SaveHandler {
    /**
     * Returns the [IntentSender] the platform must launch to show our own save confirmation, or
     * null when there is nothing to confirm (unparseable request, or an exact duplicate already
     * stored).
     *
     * The caller MUST hand this to `SaveCallback.onSuccess(intentSender)` rather than starting the
     * activity itself: an [android.service.autofill.AutofillService] is a background context, and
     * since Android 10 a background `startActivity` is silently blocked — which previously made
     * every autofill save vanish while the platform still reported success to the user.
     *
     * @param vaultUnlocked when false the vault cannot be read, so dedup/update detection is
     *        skipped and the credential is proposed as a new login; [SaveConfirmActivity] is still
     *        shown so the save is never dropped without telling the user.
     */
    fun handle(
        context: Context,
        repository: VaultRepository,
        request: SaveRequest,
        vaultUnlocked: Boolean,
    ): IntentSender? {
        val extracted = extract(request)
        if (extracted == null) {
            // No login in this form. It may still be a checkout — the only route any platform
            // leaves open for getting a card into the vault, since every wallet refuses a
            // third-party read and Chrome will not hand its saved cards to an autofill service.
            //
            // Second, never first: a sign-in form is what this service is mainly for, and a card
            // offer must never be able to displace a password offer on a page that has both.
            return handleCard(context, request, vaultUnlocked)
        }
        val matches = if (vaultUnlocked) {
            repository.findMatching(
                webDomain = extracted.webDomain,
                packageName = extracted.packageName.takeIf { extracted.webDomain == null },
            )
                .mapNotNull { item -> (item.content as? Login)?.let { item to it } }
        } else {
            emptyList()
        }

        if (isAlreadyStored(matches, extracted.username, extracted.password)) return null
        val updateCandidate = updateCandidate(
            matches = matches,
            webDomain = extracted.webDomain,
            username = extracted.username,
            currentPassword = extracted.currentPassword,
        )
        val token = PendingSaveStore.put(
            PendingSaveStore.Entry(
                // A password-only form matched through its Current box takes the username of the
                // login it replaces, so the confirm screen names whose password is changing.
                username = extracted.username.ifEmpty { updateCandidate?.second?.username.orEmpty() },
                password = extracted.password.toCharArray(),
                webDomain = extracted.webDomain,
                packageName = extracted.packageName,
                existingItemId = updateCandidate?.first?.id,
                // A locked vault means the confirmation cannot complete without an unlock, and it
                // is the moment Android may refuse to show the confirmation at all — see
                // PendingSaveStore.Entry.awaitingUnlock. Queued so the next unlock can offer it.
                awaitingUnlock = !vaultUnlocked,
            ),
        )
        // Distinct `data` URIs per token keep these PendingIntents from colliding under
        // FLAG_UPDATE_CURRENT (Intent.filterEquals differs), so concurrent saves stay separate.
        // NEW_TASK | NEW_DOCUMENT alongside the manifest's taskAffinity="": the confirm screen
        // stands in its own task, so finishing it returns the user to the app they were logging
        // into rather than uncovering LocalKeep's vault list behind it (G18).
        return PendingIntent.getActivity(
            context,
            0,
            SaveConfirmActivity.intent(context, token).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            // Without this the platform's own launch of this PendingIntent is refused as a
            // background activity start whenever the vault locked in between and this app has no
            // visible window — silently, since onSuccess is the last the service hears. API 34+
            // only; see CreatorBackgroundActivityStart, and PendingSaveQueue for the fallback that
            // covers 28-33 and any refusal this does not lift.
            CreatorBackgroundActivityStart.optionsBundle(),
        ).intentSender
    }

    /**
     * Proposes a Card item built from a checkout form, or null when there is nothing usable.
     *
     * No tier test here, and the absence is the point. This refused every non-Pro user outright,
     * which was right while a free vault could hold no card at all and wrong from 2026-09-03, when
     * `ProTypeQuota.FREE_LIMIT` made the first one free. It was also silent: nothing gates the
     * `SaveInfo` — `DatasetBuilder.buildSaveInfo` declares `SAVE_DATA_TYPE_CREDIT_CARD` on the
     * locked and unlocked paths alike — so the platform had already asked "Save card?", the user
     * had already said yes, and a null here is the answer `onSaveRequest` maps to
     * `callback.onSuccess()`, i.e. "nothing to confirm". The card vanished with no error shown, and
     * in `foss`, where `isPro` is permanently false, that was every user.
     *
     * The allowance is applied in [SaveConfirmActivity] instead, which is the only place that has
     * both an unlocked vault to count against — it unlocks in place — and a screen to refuse on.
     */
    private fun handleCard(
        context: Context,
        request: SaveRequest,
        vaultUnlocked: Boolean,
    ): IntentSender? {
        val card = extractCard(request) ?: return null
        val token = PendingSaveStore.put(
            PendingSaveStore.Entry(
                username = "",
                password = CharArray(0),
                webDomain = card.webDomain,
                packageName = card.packageName,
                existingItemId = null,
                card = card.card,
                awaitingUnlock = !vaultUnlocked,
            ),
        )
        return PendingIntent.getActivity(
            context,
            0,
            SaveConfirmActivity.intent(context, token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            // A captured card reaches this screen the same way a login does, and is refused the
            // same way without it — see the login path above.
            CreatorBackgroundActivityStart.optionsBundle(),
        ).intentSender
    }

    private fun extractCard(request: SaveRequest): ExtractedCard? {
        if (request.fillContexts.isEmpty()) return null
        val values = mutableMapOf<FieldType, String>()
        request.fillContexts.forEach { context ->
            repeat(context.structure.windowNodeCount) { index ->
                collectValues(context.structure.getWindowNodeAt(index).rootViewNode, values)
            }
        }
        // The number is the one field that makes this a card. Everything else is optional because a
        // multi-step checkout routinely puts expiry and CVV on a later screen, and refusing to
        // capture anything until all four arrive means capturing nothing on most real forms.
        val rawNumber = values[FieldType.CardNumber]?.takeIf(String::isNotBlank) ?: return null
        val digits = rawNumber.filter(Char::isDigit)
        // Length-checked rather than Luhn-checked. Luhn would reject a card the user mistyped into
        // the merchant's form, and the merchant is about to reject that anyway — this is only here
        // to keep an order number or a phone number from becoming a Card item.
        if (digits.length !in MIN_PAN_DIGITS..MAX_PAN_DIGITS) return null

        val structures = request.fillContexts.map { it.structure }
        val webDomain = structures.asReversed().firstNotNullOfOrNull(FieldDetector::detectWebDomain)
        val packageName = structures.asReversed()
            .firstNotNullOfOrNull { it.activityComponent?.packageName }
        if (webDomain == null && packageName == null) return null
        if (SystemCredentialUi.shouldSkipSave(packageName, webDomain)) return null

        return ExtractedCard(
            card = PendingSaveStore.Card(
                number = digits.toCharArray(),
                securityCode = values[FieldType.CardSecurityCode]
                    ?.filter(Char::isDigit)
                    .orEmpty()
                    .toCharArray(),
                holder = values[FieldType.CardHolder].orEmpty().trim(),
                expiry = values[FieldType.CardExpiry].orEmpty().trim(),
            ),
            webDomain = webDomain,
            packageName = packageName,
        )
    }

    private fun extract(request: SaveRequest): ExtractedSave? {
        if (request.fillContexts.isEmpty()) return null

        val values = mutableMapOf<FieldType, String>()
        val currentPasswords = mutableListOf<String>()
        request.fillContexts.forEach { context ->
            repeat(context.structure.windowNodeCount) { index ->
                collectValues(
                    context.structure.getWindowNodeAt(index).rootViewNode,
                    values,
                    currentPasswords,
                )
            }
        }

        val structures = request.fillContexts.map { it.structure }
        val webDomain = structures.asReversed()
            .firstNotNullOfOrNull(FieldDetector::detectWebDomain)
        val packageName = structures.asReversed()
            .firstNotNullOfOrNull { it.activityComponent?.packageName }
        return extractedFrom(values, currentPasswords.lastOrNull(), webDomain, packageName)
    }

    /**
     * The login a save request carries, from the values already read off its fields.
     *
     * A password is required; a username is not (2026-10-02). Requiring one returned null for every
     * form whose identifier box was not recognised — a phone-number sign-up, a password reset, a
     * change-password page — and null is the answer `onSaveRequest` reports as "nothing to
     * confirm": the platform had asked "Save to LocalKeep?", the user had said yes, and the
     * password (often the one LocalKeep had just generated) was stored nowhere. It is saved with
     * an empty username now, which the user can fill in later.
     *
     * @param currentPassword a change-password form's Current box, kept apart from [values] so the
     *   new password is what gets saved whatever order the boxes come in.
     */
    internal fun extractedFrom(
        values: Map<FieldType, String>,
        currentPassword: String?,
        webDomain: String?,
        packageName: String?,
    ): ExtractedSave? {
        val newPassword = values[FieldType.Password]?.takeIf(String::isNotBlank)
        val password = newPassword ?: currentPassword?.takeIf(String::isNotBlank) ?: return null
        val username = values[FieldType.Username]?.takeIf(String::isNotBlank)
        // A checkout with no username is a card form that happens to carry a password-looking box;
        // the card path keeps it, as it did while a username was required here.
        if (username == null && values[FieldType.CardNumber]?.isNotBlank() == true) return null
        if (webDomain == null && packageName == null) return null
        // The OS's own credential screens (lock-screen PIN, keyguard) are not accounts to store.
        if (SystemCredentialUi.shouldSkipSave(packageName, webDomain)) return null

        return ExtractedSave(
            username = username.orEmpty(),
            password = password,
            webDomain = webDomain,
            packageName = packageName,
            currentPassword = currentPassword.takeIf { newPassword != null },
        )
    }

    /**
     * True when the vault already holds this credential, so there is nothing to confirm.
     *
     * With no username — a password-only form — the password alone decides: a second sign-in on a
     * password-only screen would otherwise be offered for saving as a new, nameless login every time.
     */
    internal fun isAlreadyStored(
        matches: List<Pair<VaultItem, Login>>,
        username: String,
        password: String,
    ): Boolean = matches.any { (_, login) ->
        login.password == password && (username.isEmpty() || login.username == username)
    }

    /**
     * The stored login a captured credential replaces, or null to save it as a new one.
     *
     * Same username, on the same site (2026-10-02). `findMatching` also returns a login saved for a
     * parent domain — `example.com` matches a visit to `community.example.com` — and updating that
     * one replaced the main account's password with a separately-registered subdomain's, under a
     * title that named only the subdomain. Such a match is now a new login instead.
     *
     * With no username, a Current box whose value is a stored login's password names that login:
     * it is the account whose password is being changed.
     */
    internal fun updateCandidate(
        matches: List<Pair<VaultItem, Login>>,
        webDomain: String?,
        username: String,
        currentPassword: String? = null,
    ): Pair<VaultItem, Login>? {
        val sameSite = matches.filterNot { (_, login) -> matchedOnlyThroughParentDomain(webDomain, login) }
        if (username.isEmpty() && currentPassword != null) {
            sameSite.firstOrNull { (_, login) -> login.password == currentPassword }?.let { return it }
        }
        return sameSite.firstOrNull { (_, login) -> login.username == username }
    }

    /**
     * True when every URI of [login] that names a host names a parent of [webDomain] rather than
     * [webDomain] itself. A leading `www.` or `accounts.` is ignored on both sides — the same two
     * prefixes `matchesUri` treats as naming no separate site.
     */
    internal fun matchedOnlyThroughParentDomain(webDomain: String?, login: Login): Boolean {
        val visited = siteHost(webDomain ?: return false) ?: return false
        val hosts = login.uris.mapNotNull { siteHost(it.uri) }
        return visited !in hosts && hosts.any { visited.endsWith(".$it") }
    }

    private fun siteHost(value: String): String? = runCatching {
        val trimmed = value.trim()
        val host = URI(if ("://" in trimmed) trimmed else "https://$trimmed").host ?: return null
        val normalized = host.trimEnd('.').lowercase()
        GENERIC_HOST_PREFIXES.firstOrNull(normalized::startsWith)
            ?.let { normalized.removePrefix(it) }
            ?: normalized
    }.getOrNull()

    private val GENERIC_HOST_PREFIXES = listOf("www.", "accounts.")

    private fun collectValues(
        node: ViewNode,
        values: MutableMap<FieldType, String>,
        currentPasswords: MutableList<String>? = null,
    ) {
        FieldDetector.detectType(node)?.let { type ->
            val value = node.autofillValue
                ?.takeIf { it.isText }
                ?.textValue
                ?.toString()
                ?: node.text?.toString()
            value?.takeIf(String::isNotBlank)?.let {
                if (
                    currentPasswords != null &&
                    type == FieldType.Password &&
                    FieldDetector.isCurrentPassword(node)
                ) {
                    currentPasswords += it
                } else {
                    values[type] = it
                }
            }
        }
        repeat(node.childCount) { collectValues(node.getChildAt(it), values, currentPasswords) }
    }

    internal data class ExtractedSave(
        val username: String,
        val password: String,
        val webDomain: String?,
        val packageName: String?,
        /** The Current box of a change-password form, when it was not also the one being saved. */
        val currentPassword: String? = null,
    ) {
        // Until 2026-09-13 the default data-class toString()
        // rendered the just-captured plaintext password verbatim, unlike PendingSaveStore.Entry
        // (CharArray) and every core/model secret type. Redacts, matching Login/Totp's shape.
        override fun toString() = "ExtractedSave(redacted)"
    }

    private data class ExtractedCard(
        val card: PendingSaveStore.Card,
        val webDomain: String?,
        val packageName: String?,
    )

    /** Shortest real PAN is a 12-digit Maestro; longest is 19. */
    private const val MIN_PAN_DIGITS = 12
    private const val MAX_PAN_DIGITS = 19
}
