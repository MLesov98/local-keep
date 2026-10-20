package com.localkeep.core.model

import kotlinx.serialization.Serializable

// FROZEN: protobuf encodes enums by ordinal. Append only; never reorder or delete.

@Serializable
enum class MatchStrategy {
    DOMAIN,
    HOST,
    STARTS_WITH,
    EXACT,
    REGEX,
    NEVER,
}

@Serializable
enum class TotpAlgorithm {
    SHA1,
    SHA256,
    SHA512,
}

@Serializable
enum class CardBrand {
    UNKNOWN,
    VISA,
    MASTERCARD,
    AMEX,
    DISCOVER,
    JCB,
    DINERS,
    UNIONPAY,
    MAESTRO,
}

/**
 * Names the card network from its issuer identification number.
 *
 * Strictly a **display hint**, and deliberately not a validity check. It exists so a user filling in
 * a card sees "Visa" appear and knows they are editing the card they meant to — the editor stored
 * the number and left [CardBrand] at `UNKNOWN` forever, so nothing ever confirmed anything.
 *
 * Nothing is rejected on the strength of it. This field also holds loyalty, membership and gift
 * cards, whose numbers follow none of these ranges and are not wrong for that; anything unmatched is
 * simply [CardBrand.UNKNOWN] and shows no badge. A Luhn check is deliberately absent for the same
 * reason: it would fail exactly those cards while a mistyped Visa can pass it.
 *
 * Prefixes are the published IIN ranges. Order matters where they overlap — Maestro's 6xxx ranges
 * are tested before Discover's broader `65`.
 */
object CardBrands {
    fun detect(number: String): CardBrand {
        val digits = number.filter(Char::isDigit)
        if (digits.length < 2) return CardBrand.UNKNOWN
        val two = digits.take(2).toInt()
        val three = digits.take(3).toIntOrNull() ?: 0
        val four = digits.take(4).toIntOrNull() ?: 0
        return when {
            digits[0] == '4' -> CardBrand.VISA
            two == 34 || two == 37 -> CardBrand.AMEX
            two in 51..55 || four in 2221..2720 -> CardBrand.MASTERCARD
            four in 3528..3589 -> CardBrand.JCB
            three in 300..305 || four == 3095 || two == 36 || two == 38 || two == 39 ->
                CardBrand.DINERS
            two == 50 || two in 56..58 || four == 6304 || four == 6759 || four in 6761..6763 ->
                CardBrand.MAESTRO
            four == 6011 || three in 644..649 || two == 65 -> CardBrand.DISCOVER
            two == 62 -> CardBrand.UNIONPAY
            else -> CardBrand.UNKNOWN
        }
    }
}

@Serializable
enum class PasskeyAlgorithm {
    ES256,
}

@Serializable
enum class CustomFieldType {
    TEXT,
    HIDDEN,
    BOOLEAN,
    LINKED,
}
