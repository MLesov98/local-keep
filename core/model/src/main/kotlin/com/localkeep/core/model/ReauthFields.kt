package com.localkeep.core.model

/**
 * The reveal key for a saved position.
 *
 * In [ALWAYS_REAUTH_FIELDS] beside a CVV and a TOTP seed, and the reasoning is the same one: this
 * is a value that is dangerous to expose and that nobody needs to glance at in passing. A precise
 * position is a statement about where its owner was, and unlike a password it cannot be changed
 * after it leaks. Masked by default, re-auth to reveal, re-auth to copy.
 */
const val COORDINATE_FIELD = "coordinate"

/**
 * The reveal-gate key for a stored recovery phrase — the words and the passphrase together.
 *
 * In [ALWAYS_REAUTH_FIELDS] beside the TOTP seed and the CVV, and the reasoning is theirs exactly: a
 * recovery phrase is the whole key to whatever it opens, it cannot be changed after it leaks, and
 * nobody needs to glance at one in passing. Masked by default, re-auth to reveal, re-auth to copy —
 * on every phrase item, whatever its own reprompt flag says (though a new one defaults that flag on).
 */
const val PHRASE_FIELD = "seedPhrase"

/**
 * The reveal-gate key for a stored phrase's optional 25th-word passphrase — its own key so revealing
 * or copying it is a separate act from the words, gated on every phrase item like [PHRASE_FIELD].
 */
const val PHRASE_PASSPHRASE_FIELD = "seedPhrasePassphrase"

/**
 * The reveal-gate key for a secure note's body.
 *
 * **Deliberately NOT in [ALWAYS_REAUTH_FIELDS].** A note is gated only when the user has ticked the
 * item's own reprompt flag, which `requiresReauthFor` reaches through its `|| reprompt` arm. Putting
 * it in the always-set would gate every note ever written, including the ones whose owner never
 * asked for it, and a shopping list behind a fingerprint prompt is how a gate teaches people to
 * dismiss gates.
 *
 * It exists because until 2026-09-10 the body had no key at all, so `reprompt` did nothing on a
 * NOTE while the detail screen still drew the "Reprompt" badge and `itemedit_reprompt_caption_on`
 * still promised that "every secret in this item asks for your screen lock before it is shown or
 * copied". The UI asserted a protection that was not implemented. `ItemContent.kt` says why this
 * counts as a secret at all: a secure note IS the secret.
 */
const val NOTE_BODY_FIELD = "noteBody"

/** A card's security code. In [ALWAYS_REAUTH_FIELDS]; never rendered before authentication. */
const val CARD_CVV_FIELD = "cardCvv"

/** A card's PIN. In [ALWAYS_REAUTH_FIELDS]; never rendered before authentication. */
const val CARD_PIN_FIELD = "cardPin"

/**
 * Fields that always demand re-authentication before they are exposed, per UI/UX spec §5.2 —
 * regardless of the item's own reprompt setting. A card's CVV/PIN and a TOTP seed are each enough
 * on their own to use or clone the credential.
 *
 * **In `:core:model` so that every surface that can release one of these sees the same list.** It
 * lived in `:feature:itemedit` as an `internal` value, which is module-scoped in Kotlin — so the
 * autofill service and the credential provider, the two surfaces that hand secrets to *another
 * app*, could not read it and gated on the per-item `reprompt` flag alone. That flag defaults to
 * false, and filling a CVV inline into a foreign app's form at a checkout counter is a worse
 * exposure than revealing one in-app, not a lesser one. Both modules already depend on this one, so
 * this is the natural home; the alternative — a second hand-maintained copy — is exactly how the
 * two would come to disagree about which secrets are high-value.
 *
 * Never copy this set. Add to it here.
 */
val ALWAYS_REAUTH_FIELDS =
    setOf(
        "totpSecret",
        CARD_CVV_FIELD,
        CARD_PIN_FIELD,
        COORDINATE_FIELD,
        PHRASE_FIELD,
        PHRASE_PASSPHRASE_FIELD,
        // The three government ID numbers, on the test this set already uses everywhere else:
        // is the value still dangerous after it leaks? A card can be cancelled in an afternoon and
        // a password can be retyped; a national ID, a passport number and a licence number cannot
        // be reissued to undo anything, which is the same reason a saved position is in this set.
        // They were reprompt-gated only — masked like a card *number*, which is the weaker tier —
        // so on an item with reprompt off they revealed and copied on one tap.
        "ssn",
        "passport",
        "license",
    )
