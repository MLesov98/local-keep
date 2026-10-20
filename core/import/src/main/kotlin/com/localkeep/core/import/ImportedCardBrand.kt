package com.localkeep.core.import

import com.localkeep.core.model.CardBrand
import com.localkeep.core.model.CardBrands

/**
 * The [CardBrand] for a card arriving from another password manager.
 *
 * The number decides it. [CardBrands.detect] reads the IIN prefix, it is what the card editor
 * computes on every save, and it is therefore what an imported card would be *rewritten* to the
 * first time the user opens and saves it. Trusting the export's own brand text ahead of it would
 * buy a value that lasts until the first edit and then silently changes — and where the two
 * disagree, the digits are right and the label is a typo in someone else's database.
 *
 * The exported text is the fallback, for the one case the digits cannot answer: a card saved with
 * a brand but no number. Every source spells these differently — Bitwarden writes them for a human
 * ("American Express", "Diners Club"), 1Password uses short codes ("mc", "amex") — so the match is
 * on letters alone, case and punctuation dropped.
 *
 * [CardBrand.UNKNOWN] when neither answers, which is what these importers produced before either
 * field was read at all. An unidentified card is cosmetic; a misidentified one puts the wrong logo
 * on the user's card.
 */
internal object ImportedCardBrand {

    fun of(number: String?, exportedName: String?): CardBrand {
        val detected = CardBrands.detect(number.orEmpty())
        return if (detected != CardBrand.UNKNOWN) detected else fromName(exportedName)
    }

    private fun fromName(raw: String?): CardBrand =
        when (raw?.lowercase()?.filter(Char::isLetter).orEmpty()) {
            "visa", "visaelectron" -> CardBrand.VISA
            "mc", "mastercard" -> CardBrand.MASTERCARD
            "amex", "americanexpress" -> CardBrand.AMEX
            "discover" -> CardBrand.DISCOVER
            "jcb" -> CardBrand.JCB
            "diners", "dinersclub" -> CardBrand.DINERS
            "unionpay", "chinaunionpay" -> CardBrand.UNIONPAY
            "maestro" -> CardBrand.MAESTRO
            else -> CardBrand.UNKNOWN
        }
}
