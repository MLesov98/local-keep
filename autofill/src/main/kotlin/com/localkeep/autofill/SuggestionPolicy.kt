package com.localkeep.autofill

/**
 * When LocalKeep offers something of its own on a form, rather than a credential it already holds.
 *
 * Split out of [FillResponseFactory] because the decisions are the whole feature and the factory
 * around them needs an `AssistStructure` to exercise — which is an instrumented environment. These
 * are pure, so the rules that decide whether the user sees anything at all when they tap a field
 * are covered by ordinary unit tests.
 *
 * The behaviour being matched is the platform manager's: tapping a field always produces *some*
 * LocalKeep entry point once the vault is unlocked, and a sign-up form offers a generated password
 * before the account exists. Returning nothing — which is what a strict domain match does on the
 * first visit to any site — reads to the user as "the password manager I chose is not running".
 */
internal object SuggestionPolicy {
    /**
     * Offer to generate a password.
     *
     * Two cases, and the second is why this is not simply "no match": on a change-password or
     * second-account form the user already *has* a credential for the site, and a generator offer
     * is exactly what they came for. On an ordinary sign-in form with a credential stored, it would
     * be noise sitting above the row they want.
     */
    fun offerGeneratedPassword(
        hasPasswordField: Boolean,
        hasStoredMatch: Boolean,
        isNewPasswordForm: Boolean,
    ): Boolean = hasPasswordField && (!hasStoredMatch || isNewPasswordForm)

    /**
     * Offer to search the vault by hand.
     *
     * The catch-all: domain matching is exact by design, so a login stored under `example.com` is
     * not offered on `login.example.co.uk`, and an app whose package changed hands matches nothing.
     * Without this row the correct answer is in the vault and unreachable from the form.
     *
     * Gated on a credential field rather than any fillable field: a lone URL box, or a checkout
     * form with only card fields, is not somewhere a login belongs.
     */
    fun offerVaultSearch(
        hasUsernameField: Boolean,
        hasPasswordField: Boolean,
    ): Boolean = hasUsernameField || hasPasswordField

    /**
     * The password boxes a generated password goes into (2026-10-02).
     *
     * Every one of them, except a change-password form's Current box: filling that with the new
     * password made the site reject the change as "current password incorrect", on the one form
     * the generator is offered for even when a login is stored. When every box reads as current —
     * a sign-in form declaring `autocomplete="current-password"` — there is no new box to tell
     * apart, and the old behaviour stands.
     */
    fun generatedPasswordTargets(fields: List<FillableField>): List<FillableField> {
        val passwords = fields.filter { it.type == FieldType.Password }
        return passwords.filterNot(FillableField::isCurrentPassword).ifEmpty { passwords }
    }

    /**
     * Offer "Unlock LocalKeep" on a locked vault (2026-10-02).
     *
     * Only where unlocking could fill something. A lone website box — or a "Security question"
     * field, whose "uri" substring reads as one — got the row, cost the user an Argon2 unlock, and
     * then closed with nothing filled and nothing said, because the unlocked path offers nothing on
     * that field either.
     */
    fun offerUnlockGate(fieldTypes: Collection<FieldType>): Boolean =
        fieldTypes.any { it != FieldType.Url }
}
