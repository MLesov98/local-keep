package com.localkeep.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.localkeep.core.model.Card
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.totp.TotpGenerator
import java.time.Instant

object DatasetBuilder {
    fun build(
        context: Context,
        item: VaultItem,
        fields: List<FillableField>,
        repromptAuthPendingIntent: PendingIntent?,
        inlineSpec: Any? = null,
    ): List<Dataset> {
        val login = item.content as? Login ?: return emptyList()
        val usernameField = fields.firstOrNull { it.type == FieldType.Username }
        val passwordField = fields.firstOrNull { it.type == FieldType.Password }
        val totpField = fields.firstOrNull { it.type == FieldType.Totp }
        val requiresReprompt = item.reprompt && repromptAuthPendingIntent != null

        return buildList {
            if (usernameField != null || passwordField != null) {
                add(
                    Dataset.Builder().apply {
                        if (requiresReprompt) {
                            val confirmToFill =
                                context.getString(R.string.autofill_dataset_confirm_to_fill)
                            val presentation = rowPresentation(context, item.title, confirmToFill)
                            usernameField?.let { setValue(it.id, null, presentation) }
                            passwordField?.let { setValue(it.id, null, presentation) }
                            // The one-time-code field belongs *behind* the challenge, not outside
                            // it. Leaving its id off this dataset meant the platform had no
                            // LocalKeep dataset for it at all — the branch below is skipped when a
                            // reprompt intent exists — so marking a login "reprompt" silently
                            // withdrew its 2FA suggestion everywhere, and the user had to leave the
                            // form and route the code through the clipboard this app works to keep
                            // empty. Reprompt is a challenge before a secret is shown, never the
                            // removal of the feature on one surface: in-app, the same flag gates
                            // the code and then reveals it.
                            if (login.totp != null) {
                                totpField?.let { setValue(it.id, null, presentation) }
                            }
                            setAuthentication(repromptAuthPendingIntent.intentSender)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inlineSpec != null) {
                                InlineSuggestions.applyTo(
                                    builder = this,
                                    title = item.title,
                                    subtitle = confirmToFill,
                                    // Not the reprompt gate (2026-10-02): this is the long-press
                                    // intent, and the gate launched from it has no one to hand
                                    // its dataset to. The gate is setAuthentication's, above.
                                    attributionIntent = attributionIntent(context, item.id.toString()),
                                    spec = inlineSpec,
                                )
                            }
                        } else {
                            usernameField?.let {
                                setValue(
                                    it.id,
                                    AutofillValue.forText(login.username),
                                    rowPresentation(context, item.title, login.username),
                                )
                            }
                            passwordField?.let {
                                setValue(
                                    it.id,
                                    AutofillValue.forText(login.password),
                                    rowPresentation(
                                        context,
                                        item.title,
                                        context.getString(R.string.autofill_dataset_password_mask),
                                    ),
                                )
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inlineSpec != null) {
                                val inlineText = inlineLoginPresentationText(item.title, login.username)
                                InlineSuggestions.applyTo(
                                    builder = this,
                                    title = inlineText.first,
                                    subtitle = inlineText.second,
                                    attributionIntent = attributionIntent(context, item.id.toString()),
                                    spec = inlineSpec,
                                )
                            }
                        }
                    }.build(),
                )
            }

            val totp = login.totp
            if (!requiresReprompt && totp != null && totpField != null) {
                runCatching {
                    val now = Instant.now()
                    val code = TotpGenerator.generate(totp, now)
                    val remaining = TotpGenerator.remainingSeconds(totp, now)
                    val title = context.getString(R.string.autofill_dataset_totp_row_title, item.title)
                    val subtitle = context.resources.getQuantityString(
                        R.plurals.autofill_dataset_totp_remaining,
                        remaining,
                        remaining,
                    )
                    Dataset.Builder().apply {
                        setValue(
                            totpField.id,
                            AutofillValue.forText(code),
                            rowPresentation(context, title, subtitle),
                        )
                        // 2026-10-02: the one dataset here that had no inline presentation, so an
                        // inline-suggestion keyboard (Gboard, API 30+) dropped the 2FA row and the
                        // code page looked as if LocalKeep offered nothing.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inlineSpec != null) {
                            InlineSuggestions.applyTo(
                                builder = this,
                                title = title,
                                subtitle = subtitle,
                                attributionIntent = attributionIntent(context, "${item.id}/totp"),
                                spec = inlineSpec,
                            )
                        }
                    }.build()
                }.getOrNull()?.let(::add)
            }
        }
    }

    /**
     * The single dataset handed back by an activity: once a reprompt challenge passes, and when
     * the user picks a login by hand in [PickerActivity].
     *
     * `EXTRA_AUTHENTICATION_RESULT` for a *dataset* authentication must be one `Dataset` — the
     * platform accepts a `FillResponse` only when the response itself was the thing authenticated —
     * so everything [build] would spread across a credentials row and a separate 2FA row has to
     * arrive together here. Rebuilding through [build] and taking `.firstOrNull()` returned the
     * credentials and dropped the code, which is how the reprompt path lost 2FA even once the
     * challenge was passed.
     *
     * Returns null rather than an empty dataset: `Dataset.Builder.build()` throws when no value was
     * ever set, and the caller cancels cleanly on null.
     */
    fun buildSingleDataset(
        context: Context,
        item: VaultItem,
        fields: List<FillableField>,
    ): Dataset? {
        val login = item.content as? Login ?: return null
        val builder = Dataset.Builder()
        var values = 0

        fields.firstOrNull { it.type == FieldType.Username }?.let {
            builder.setValue(
                it.id,
                AutofillValue.forText(login.username),
                rowPresentation(context, item.title, login.username),
            )
            values++
        }
        fields.firstOrNull { it.type == FieldType.Password }?.let {
            builder.setValue(
                it.id,
                AutofillValue.forText(login.password),
                rowPresentation(
                    context,
                    item.title,
                    context.getString(R.string.autofill_dataset_password_mask),
                ),
            )
            values++
        }
        val totp = login.totp
        val totpField = fields.firstOrNull { it.type == FieldType.Totp }
        if (totp != null && totpField != null) {
            runCatching { TotpGenerator.generate(totp, Instant.now()) }.getOrNull()?.let { code ->
                builder.setValue(
                    totpField.id,
                    AutofillValue.forText(code),
                    rowPresentation(
                        context,
                        context.getString(R.string.autofill_dataset_totp_row_title, item.title),
                        null,
                    ),
                )
                values++
            }
        }

        return if (values == 0) null else builder.build()
    }

    /**
     * The masked, value-free row offered for a stored [Card] before authentication.
     *
     * Every card row is an authentication row — there is no unauthenticated variant of this, and
     * that is the point rather than an accident of the caller. A `Card` carries `cardCvv` and
     * `cardPin`, both in [ALWAYS_REAUTH_FIELDS], and filling a CVV inline into another app's
     * checkout form is a worse exposure than revealing one in-app, not a lesser one. So the dataset
     * carries `null` for every field and defers to [pendingIntent]; the real values are read inside
     * [AuthActivity] after the challenge, exactly as the reprompt path already does, and never
     * cross an `Intent` extra.
     *
     * What draws before the challenge is the item's title and a masked tail — `brandLabel` plus the
     * last four digits, which is what the item's own title already contains and what the vault list
     * shows. Never the PAN and never the CVV: a security code has no readable use before it is
     * filled, so there is no row it belongs on.
     */
    fun buildCardAuthDataset(
        context: Context,
        item: VaultItem,
        fields: List<FillableField>,
        pendingIntent: PendingIntent,
        inlineSpec: Any? = null,
    ): Dataset? {
        if (item.content !is Card) return null
        val cardFields = fields.filter { it.type.isCard }
        if (cardFields.isEmpty()) return null
        val presentation = rowPresentation(
            context,
            item.title,
            context.getString(R.string.autofill_dataset_confirm_to_fill),
        )
        val confirmToFill = context.getString(R.string.autofill_dataset_confirm_to_fill)
        return Dataset.Builder().apply {
            cardFields.forEach { setValue(it.id, null, presentation) }
            // Guarded exactly as the two call sites above are. `InlineSuggestions` is API 30, and
            // `minSdk` is 28 — the last time this file reached an inline-suggestion API without a
            // version check, autofill answered a form the vault had a credential for with nothing
            // at all on API 28 and 29. `checkNoApi30Signatures` exists because of that.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inlineSpec != null) {
                InlineSuggestions.applyTo(
                    builder = this,
                    title = item.title,
                    subtitle = confirmToFill,
                    // The long-press intent, never the card gate — see [attributionIntent].
                    attributionIntent = attributionIntent(context, item.id.toString()),
                    spec = inlineSpec,
                )
            }
            setAuthentication(pendingIntent.intentSender)
        }.build()
    }

    /**
     * Fills a card's stored values. Only ever reached from inside [AuthActivity], after the user
     * has answered the challenge that [buildCardAuthDataset] set up.
     */
    fun buildCardDataset(
        context: Context,
        item: VaultItem,
        fields: List<FillableField>,
    ): Dataset? {
        val card = item.content as? Card ?: return null
        val builder = Dataset.Builder()
        var values = 0

        fun fill(type: FieldType, value: String, subtitle: String?) {
            if (value.isBlank()) return
            fields.firstOrNull { it.type == type }?.let {
                builder.setValue(it.id, AutofillValue.forText(value), rowPresentation(context, item.title, subtitle))
                values++
            }
        }

        fill(FieldType.CardNumber, card.number, CardMask.tail(card.number))
        fill(FieldType.CardHolder, card.holder, card.holder)
        // Masked in the row even here. The presentation is what the platform may keep and redraw,
        // and a security code is never something to draw — the value goes to the field, the row
        // says only that a card was filled.
        fill(FieldType.CardSecurityCode, card.cvv, context.getString(R.string.autofill_dataset_password_mask))
        val expiry = CardMask.expiry(card.expMonth, card.expYear)
        if (expiry != null) fill(FieldType.CardExpiry, expiry, expiry)

        return if (values == 0) null else builder.build()
    }

    /**
     * @param cardFields payment-card fields found in the same form. Declaring
     *   `SAVE_DATA_TYPE_CREDIT_CARD` is what makes the platform send an `onSaveRequest` for a
     *   checkout form at all — without it the card is simply never offered, whatever the detector
     *   found. The card number is required and the rest optional, because expiry and CVV are often
     *   on a later step of the same flow and demanding all four means the offer never fires.
     *
     * A two-step sign-in — username on one screen, password on the next — is the case
     * [isFirstStepOfSplitSignIn] handles; see its KDoc for why neither step could be saved before.
     *
     * @param otherPasswordFields every password box after [passwordField], declared optional
     *   (2026-10-02). On a change-password form [passwordField] is the Current box, which the
     *   stored login filled; with New and Confirm untracked the platform saw nothing change and
     *   never offered the rotated password for saving, so the vault kept the dead one.
     */
    fun buildSaveInfo(
        usernameField: FillableField?,
        passwordField: FillableField?,
        urlField: FillableField?,
        cardFields: List<FillableField> = emptyList(),
        otherPasswordFields: List<FillableField> = emptyList(),
    ): SaveInfo? {
        val cardNumber = cardFields.firstOrNull { it.type == FieldType.CardNumber }
        val requiredIds = listOfNotNull(usernameField?.id, passwordField?.id, cardNumber?.id)
        if (requiredIds.isEmpty()) return null
        var dataTypes = 0
        if (usernameField != null) dataTypes = dataTypes or SaveInfo.SAVE_DATA_TYPE_USERNAME
        if (passwordField != null) dataTypes = dataTypes or SaveInfo.SAVE_DATA_TYPE_PASSWORD
        if (cardNumber != null) dataTypes = dataTypes or SaveInfo.SAVE_DATA_TYPE_CREDIT_CARD
        // A form with neither a username nor a password used to declare both anyway. Harmless while
        // the required ids were only ever those two; not harmless once a checkout form can be the
        // only thing here, because the platform words its own prompt from these flags.
        if (dataTypes == 0) {
            dataTypes = SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD
        }
        var flags = SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE
        // FLAG_DELAY_SAVE is API 29 and minSdk is 28. It is a compile-time int constant, so the
        // reference inlines and nothing resolves at runtime — the guard is here because on API 28
        // the platform ignores the bit entirely, and a service that thinks it postponed a save it
        // did not postpone is worse than one that never tried. API 28 keeps the behaviour it has
        // today: a split sign-in is not offered for saving there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            isFirstStepOfSplitSignIn(usernameField, passwordField, cardNumber)
        ) {
            flags = flags or SaveInfo.FLAG_DELAY_SAVE
        }
        return SaveInfo.Builder(dataTypes, requiredIds.toTypedArray()).apply {
            val optional = listOfNotNull(urlField?.id) +
                cardFields.filter { it.type != FieldType.CardNumber }.map { it.id } +
                otherPasswordFields.map { it.id }.filter { it != passwordField?.id }
            optional.takeIf(List<*>::isNotEmpty)?.let { setOptionalIds(it.toTypedArray()) }
            setFlags(flags)
        }.build()
    }

    /**
     * True for the username page of a two-step sign-in: a username, no password, no card.
     *
     * Such a page used to be offered for saving the instant it went away, and the offer was a dead
     * end — `SaveHandler.extract` needs a username *and* a password, so it returned null and the
     * platform's "nothing to confirm" answer was all the user got. The password page that followed
     * opened a fresh autofill session carrying only a password, and failed the same test from the
     * other side. A login split across two screens was therefore never savable, on any app, which
     * is most of the sign-in flows shipped in the last few years.
     *
     * `SaveInfo.FLAG_DELAY_SAVE` is the platform's own answer, and its javadoc names this exact
     * case: "This flag should be used when the service detects that the application uses multiple
     * screens to implement an autofillable workflow (for example, one screen for the username
     * field, another for password)." With it set, the save UI is not raised when this screen's
     * context is committed; instead the `FillContext` is delivered again in the next fill and save
     * requests of an activity in the same task. The password page's own `SaveInfo` carries no delay
     * and fires normally, and `SaveHandler.extract` — which already walks every fill context and
     * merges what it finds — then sees the username from step one beside the password from step
     * two and proposes them as one login.
     *
     * Nothing is remembered on our side to make that work, which is the point: no captured username
     * sits in a process-static map waiting for a second screen that may never come.
     *
     * A card number present means a checkout form rather than a sign-in, and those already save in
     * one pass — delaying one would postpone a save the user just asked for.
     */
    internal fun isFirstStepOfSplitSignIn(
        usernameField: FillableField?,
        passwordField: FillableField?,
        cardNumberField: FillableField?,
    ): Boolean = usernameField != null && passwordField == null && cardNumberField == null

    /**
     * The *attribution* intent for an inline suggestion: what the platform launches when the user
     * long-presses a chip to ask where it came from.
     *
     * It pointed at AuthActivity with no mode extra, and AuthActivity cancels any intent whose mode
     * is neither unlock nor reprompt — so if the framework ever ran it, the answer to "what is
     * filling this in?" was a window that closed itself. The unlock, reprompt, card, confirm-domain
     * and picker chips went on passing their real gate here until 2026-10-02, which opened that
     * gate outside the fill flow, where its result had no receiver and nothing was ever filled.
     *
     * Opening LocalKeep is the honest answer, and it is the one thing this intent can safely do: it
     * is handed to another process's UI, so it must not carry an item id or unlock anything. The
     * unique request code and the data URI stay, to keep one PendingIntent per suggestion rather
     * than one shared instance that FLAG_UPDATE_CURRENT rewrites underneath the others.
     */
    internal fun attributionIntent(context: Context, key: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            key.hashCode(),
            (
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent(context, AuthActivity::class.java)
                ).apply { data = Uri.parse("localkeep-autofill://attribution/$key") },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    internal fun rowPresentation(
        context: Context,
        title: String,
        subtitle: String?,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.inline_suggestion).apply {
        setTextViewText(R.id.title, title)
        setTextViewText(R.id.subtitle, subtitle.orEmpty())
    }

    /**
     * The (title, subtitle) pair for a direct-value login row's *inline* IME suggestion.
     *
     * Username first, unlike [rowPresentation]'s dropdown row: several vault entries can match one
     * form and share the inline strip's width, and the platform truncates whichever text it draws
     * second long before it truncates the first. [itemTitle] is what every matching row for one
     * form tends to share (the site or caller's own label), so the username is the only thing that
     * actually tells two rows apart — and it is exactly what a truncated second chip dropped,
     * leaving it indistinguishable from the first (F-46). Falls back to the title alone when there
     * is no username to lead with, rather than leading a chip with an empty line.
     */
    internal fun inlineLoginPresentationText(itemTitle: String, username: String): Pair<String, String> =
        if (username.isBlank()) itemTitle to "" else username to itemTitle

    /**
     * The (title, subtitle) pair for the locked vault's "Unlock LocalKeep" entry.
     *
     * The subtitle is not decoration. The platform's inline-suggestion builder draws a chip's background pill from its
     * *content*, and the locked entry was the one call site passing `subtitle = null` — which
     * `InlineSuggestions.presentation` turns into `setSubtitle("")`. Measured off the IME strip on
     * 2026-09-20 (3B-F1): the unlock chip came out at **2.29:1** — `#A1A1A7` on `#F1F0F7`, below
     * every contrast floor there is — while an ordinary suggestion chip in the same strip minutes
     * earlier was `#1C1D22` on an `#E0DFE7` pill at roughly 14:1, and so was the browser-gate chip.
     * It reads as an empty strip, and it is the only route back into filling from a locked vault:
     * the one affordance that explains why nothing was offered was the one nobody could see.
     *
     * A pair rather than two `getString` calls at the call site, so the dropdown row and the inline
     * chip cannot drift apart again — and so the pairing is testable without a service.
     */
    internal fun unlockPresentationText(context: Context): Pair<String, String> =
        context.getString(R.string.autofill_unlock_title) to
            context.getString(R.string.autofill_unlock_subtitle)
}
