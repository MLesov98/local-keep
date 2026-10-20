package com.localkeep.autofill

import android.app.assist.AssistStructure
import android.app.assist.AssistStructure.ViewNode
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId

sealed interface FieldType {
    data object Username : FieldType
    data object Password : FieldType
    data object Totp : FieldType
    data object Url : FieldType

    /**
     * Payment-card fields, captured from a checkout form the user is filling in.
     *
     * This is the only route the platform leaves open for getting a card into the vault. Google
     * Wallet, Samsung Wallet and Apple Wallet all refuse third-party reads of a stored card — the
     * Google Pay API hands back a payment *token*, never the PAN — and Chrome does not offer its
     * saved payment methods to a third-party autofill service, nor export them. So a card cannot be
     * moved across from anywhere; it can only be noticed the once, as the user types it.
     */
    data object CardNumber : FieldType
    data object CardExpiry : FieldType
    data object CardSecurityCode : FieldType
    data object CardHolder : FieldType

    /** True for the four payment-card members above. */
    val isCard: Boolean
        get() = this is CardNumber || this is CardExpiry ||
            this is CardSecurityCode || this is CardHolder
}

data class FillableField(
    val id: AutofillId,
    val type: FieldType,
    /**
     * A [FieldType.Password] box that asks for the password being *replaced* — the Current box of a
     * change-password form. Still a password field for filling a stored login (the stored password
     * is exactly what belongs there); only the generator must leave it alone. 2026-10-02.
     */
    val isCurrentPassword: Boolean = false,
)

/** Detects autofill fields in the fixed priority order required by the product spec. */
object FieldDetector {
    private val usernameTerms = setOf("username", "email", "login", "user", "account")

    // 2026-10-02: "pin" and "pass" were matched as substrings, so "shipping", "typing", "opinion"
    // and "passenger"/"passport" fields were classified as passwords — the stored password went
    // into a checkout's address boxes in plain text. The short ones now match whole words only;
    // the longer ones in passwordTerms are specific enough to match inside a word ("newpwd").
    // "passwort" and the French "passe" (mot de passe) were caught by the old "pass" substring
    // and are kept so the fix does not lose them.
    private val passwordTerms =
        setOf("password", "passwd", "pwd", "passcode", "passphrase", "passwort", "pincode")
    private val passwordWords = setOf("pass", "pin", "passe")

    // The Current box of a change-password form. Whole words for the short qualifiers, joined
    // spellings for ids that run them together ("oldPassword" splits; "oldpassword" does not).
    private val currentPasswordWords = setOf("current", "old", "existing")
    private val currentPasswordTerms =
        setOf("currentpassword", "oldpassword", "existingpassword", "currentpwd", "oldpwd")

    // Until 2026-09-13, bare "token" was checked ahead of
    // passwordTerms/usernameTerms and matched any id/hint merely containing the substring — a
    // password-reset-token or an invite/download-token field was misclassified as Totp, which (per
    // SaveHandler.extract requiring both Username and Password non-blank) silently dropped the save
    // prompt for that login. Dropped rather than qualified: every real OTP field here is already
    // covered by "one-time-code"/"verification"/"totp"/"otp"/"2fa", and "token" alone carried none
    // of the specificity the card terms above deliberately have.
    private val totpTerms = setOf(
        "one-time-code",
        "verification",
        "auth code",
        "2fa",
        "totp",
        "otp",
    )
    private val urlTerms = setOf("website", "site", "uri", "url")

    // Terms that name a password being *set*, not one being recalled. Long and joined-up on
    // purpose: the bare word "password" is on every sign-in form in the world, so only the
    // qualifiers distinguish a sign-up from a sign-in. These are matched against the same
    // separator-stripped form as the card terms, so "new-password", "new_password" and
    // "newPassword" — the WHATWG token, the usual id spelling and Android's
    // AUTOFILL_HINT_NEW_PASSWORD respectively — all hit the one entry.
    private val newPasswordTerms = setOf(
        "newpassword",
        "confirmpassword",
        "passwordconfirm",
        "confirmpwd",
        "repeatpassword",
        "retypepassword",
        "passwordagain",
        "verifypassword",
        "password2",
    )

    // Card terms are deliberately long and specific where every other set here is short and loose.
    // A loose card term is a login-form regression waiting to happen: "number" alone would claim
    // every phone field on every sign-up form, and the cost of a false positive is a card item
    // built out of somebody's telephone number. These match the lowercased forms of Android's
    // AUTOFILL_HINT_CREDIT_CARD_* constants and the WHATWG `cc-*` autocomplete tokens, and little
    // else. Nothing here matches on a bare word.
    private val cardNumberTerms = setOf("creditcardnumber", "cardnumber", "card-number", "cc-number", "ccnumber")
    private val cardExpiryTerms = setOf(
        "creditcardexpiration", "cardexpiration", "card-expiry", "cardexpiry",
        "cc-exp", "ccexp", "expirationdate",
    )
    // No bare "securitycode" and no "cid"/"cvn". Half the web calls a one-time 2FA code a "security
    // code", so that term would steal TOTP fields from the detector above it; "cid" is three letters
    // that appear inside ordinary ids like "accid" and "docid".
    private val cardCodeTerms = setOf(
        "creditcardsecuritycode", "cardsecuritycode", "cc-csc", "cccsc", "cvv", "cvc",
    )
    private val cardHolderTerms = setOf("cardholder", "card-holder", "cc-name", "ccname", "nameoncard")

    fun detect(structure: AssistStructure): List<FillableField> = buildList {
        repeat(structure.windowNodeCount) { index ->
            collectFields(structure.getWindowNodeAt(index).rootViewNode, this)
        }
    }

    fun detectWebDomain(structure: AssistStructure): String? {
        repeat(structure.windowNodeCount) { index ->
            findWebDomain(structure.getWindowNodeAt(index).rootViewNode)?.let { return it }
        }
        return null
    }

    internal fun detectType(node: ViewNode): FieldType? {
        // 1. App-declared Android autofill hints.
        node.autofillHints?.forEach { hint ->
            classifyTerms(hint)?.let { return it }
        }

        // 1b. Card fields declared in HTML, read BEFORE the input-type heuristics below rather than
        // with the rest of the autocomplete handling in step 3.
        //
        // A `cc-csc` input is very often `type="password"`, and a `cc-number` one is frequently
        // `inputmode="numeric"` on a password-variation field — so leaving these to step 3 meant
        // step 2 classified a card's security code as a login password and offered to save it as
        // one. Card tokens are unambiguous, so answering them early costs nothing.
        node.htmlInfo?.attributes
            ?.firstOrNull { it.first.equals("autocomplete", ignoreCase = true) }
            ?.second
            ?.split(' ')
            ?.forEach { token ->
                classifyCardTerms(token)?.let { return it }
            }

        // 2. Input-type heuristics.
        when (node.inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            -> return FieldType.Password

            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            -> return FieldType.Username

            InputType.TYPE_TEXT_VARIATION_URI -> return FieldType.Url
        }

        // 3. Browser/WebView HTML autocomplete.
        node.htmlInfo?.attributes
            ?.firstOrNull { it.first.equals("autocomplete", ignoreCase = true) }
            ?.second
            ?.split(' ')
            ?.forEach { token ->
                when (token.lowercase()) {
                    "current-password", "new-password" -> return FieldType.Password
                    "username", "email" -> return FieldType.Username
                    "one-time-code" -> return FieldType.Totp
                    "url" -> return FieldType.Url
                }
            }

        // 4. Last-resort id/hint matching.
        return classifyTerms("${node.idEntry.orEmpty()} ${node.hint.orEmpty()}")
    }

    /**
     * Card fields only, so the caller can ask this question on its own.
     *
     * Split out of [classifyTerms] because card terms have to be answered before the input-type
     * heuristics while everything else is answered after them — see the note at step 1b.
     */
    private fun classifyCardTerms(raw: String): FieldType? {
        // Separators stripped so one term matches "cc-number", "cc_number" and "ccNumber" alike;
        // the hyphenated spellings are kept in the sets for readability of the WHATWG tokens.
        val value = raw.lowercase()
        val bare = value.filter(Char::isLetterOrDigit)
        fun hit(terms: Set<String>) =
            terms.any { value.contains(it) || bare.contains(it.filter(Char::isLetterOrDigit)) }
        return when {
            hit(cardNumberTerms) -> FieldType.CardNumber
            hit(cardExpiryTerms) -> FieldType.CardExpiry
            hit(cardCodeTerms) -> FieldType.CardSecurityCode
            hit(cardHolderTerms) -> FieldType.CardHolder
            else -> null
        }
    }

    // internal, not private, so FieldClassificationTest can exercise it directly — mirrors
    // isNewPasswordTerm below (2026-09-13).
    internal fun classifyTerms(raw: String): FieldType? {
        val value = raw.lowercase()
        return when {
            // First. A card term is long and specific enough that matching one is close to
            // conclusive, whereas the sets below match single loose words — so anything else
            // winning here would be an accident of ordering rather than a judgement.
            classifyCardTerms(raw) != null -> classifyCardTerms(raw)
            totpTerms.any(value::contains) -> FieldType.Totp
            passwordTerms.any(value::contains) || words(raw).any(passwordWords::contains) ->
                FieldType.Password
            usernameTerms.any(value::contains) -> FieldType.Username
            urlTerms.any(value::contains) -> FieldType.Url
            else -> null
        }
    }

    /**
     * True when this form is setting a password rather than recalling one — a sign-up, a password
     * change, or a reset.
     *
     * Drives the generated-password offer, which is the one suggestion that has to appear even when
     * the vault already holds a credential for the site: changing a password and adding a second
     * account are both forms where the stored login is the wrong answer.
     *
     * Two independent signals, either sufficient. A declared token (`autocomplete="new-password"`,
     * `AUTOFILL_HINT_NEW_PASSWORD`, or an id/hint naming a confirmation box) is the reliable one.
     * The count is the fallback for the many forms that declare nothing: a page asking for the same
     * secret twice is not asking the user to sign in.
     *
     * @param fields the detected fields, so the count agrees with what will actually be filled —
     *   [collectFields] already dropped invisible and disabled nodes, and a hidden second password
     *   box must not be allowed to turn a sign-in form into a sign-up one.
     */
    fun isNewPasswordForm(structure: AssistStructure, fields: List<FillableField>): Boolean {
        if (fields.count { it.type == FieldType.Password } >= 2) return true
        repeat(structure.windowNodeCount) { index ->
            if (declaresNewPassword(structure.getWindowNodeAt(index).rootViewNode)) return true
        }
        return false
    }

    /** One hint, token, id or label, tested for a new-password name. Pure, so it is unit-tested. */
    internal fun isNewPasswordTerm(raw: String): Boolean {
        val bare = raw.lowercase().filter(Char::isLetterOrDigit)
        return newPasswordTerms.any(bare::contains)
    }

    /** One hint, token, id or label, tested for a current-password name. Pure, so it is unit-tested. */
    internal fun isCurrentPasswordTerm(raw: String): Boolean {
        val bare = raw.lowercase().filter(Char::isLetterOrDigit)
        return currentPasswordTerms.any(bare::contains) || words(raw).any(currentPasswordWords::contains)
    }

    /**
     * True for a password box that asks for the password being replaced (2026-10-02).
     *
     * Each source is tested on its own, as [declaresNewPassword] does. A node that also names a new
     * or confirmation password is not a current one, whatever else it says.
     */
    internal fun isCurrentPassword(node: ViewNode): Boolean {
        val sources = buildList {
            node.autofillHints?.let(::addAll)
            node.htmlInfo?.attributes
                ?.firstOrNull { it.first.equals("autocomplete", ignoreCase = true) }
                ?.second
                ?.split(' ')
                ?.let(::addAll)
            add(node.idEntry.orEmpty())
            add(node.hint.orEmpty())
        }
        return sources.any(::isCurrentPasswordTerm) && sources.none(::isNewPasswordTerm)
    }

    /**
     * [raw] as lowercase words: split on anything that is not a letter and at camelCase humps, so
     * "et_shipping_name", "shippingName" and "Shipping name" all give the same words.
     */
    private fun words(raw: String): List<String> =
        raw.replace(CAMEL_HUMP, "$1 $2")
            .lowercase()
            .split(NON_LETTERS)
            .filter(String::isNotEmpty)

    private val CAMEL_HUMP = Regex("(\\p{Ll})(\\p{Lu})")
    private val NON_LETTERS = Regex("[^\\p{L}]+")

    private fun declaresNewPassword(node: ViewNode): Boolean {
        node.autofillHints?.forEach { if (isNewPasswordTerm(it)) return true }
        node.htmlInfo?.attributes
            ?.firstOrNull { it.first.equals("autocomplete", ignoreCase = true) }
            ?.second
            ?.split(' ')
            ?.forEach { if (isNewPasswordTerm(it)) return true }
        // Separately, never joined. Stripping separators across a concatenation manufactures terms
        // that neither half contains: an id of "confirm" beside a hint of "Password" would read as
        // "confirmpassword" whether or not the two belong to the same idea.
        if (isNewPasswordTerm(node.idEntry.orEmpty())) return true
        if (isNewPasswordTerm(node.hint.orEmpty())) return true
        repeat(node.childCount) { if (declaresNewPassword(node.getChildAt(it))) return true }
        return false
    }

    private fun collectFields(
        node: ViewNode,
        fields: MutableList<FillableField>,
    ) {
        val id = node.autofillId
        if (
            id != null &&
            node.isEnabled &&
            node.visibility == View.VISIBLE &&
            node.autofillType != View.AUTOFILL_TYPE_NONE
        ) {
            detectType(node)?.let { type ->
                fields += FillableField(
                    id = id,
                    type = type,
                    isCurrentPassword = type == FieldType.Password && isCurrentPassword(node),
                )
            }
        }
        repeat(node.childCount) { collectFields(node.getChildAt(it), fields) }
    }

    private fun findWebDomain(node: ViewNode): String? {
        node.webDomain?.takeIf(String::isNotBlank)?.let { return it }
        repeat(node.childCount) {
            findWebDomain(node.getChildAt(it))?.let { domain -> return domain }
        }
        return null
    }
}
