package com.localkeep.core.model

/**
 * How many digits each network prints on the plastic.
 *
 * A **hint source, never a gate**, for the same reason [CardBrands] is: the card fields also hold
 * loyalty, membership and gift numbers, which follow no network's scheme and are not wrong for that.
 * Nothing here may be used to disable a control or reject a value — callers show what it returns and
 * let the user carry on either way. The counterpart decision, written up at [CardBrands], is that a
 * Luhn check stays out entirely; length is the one signal that can be surfaced without failing the
 * cards that legitimately do not carry a network scheme.
 *
 * Every entry is a **set**, not a single number, and that is the whole point of the type. Visa is
 * 13, 16 or 19 digits depending on the issuer, Maestro spans 12 to 19, and UnionPay 16 to 19. Code
 * that reduced a brand to one expected length reported a legitimate 19-digit Visa as wrong and
 * auto-advanced a wizard off a half-typed one at 13.
 */
object CardNumberValidator {

    /** Longest number any network issues, and the cap the editor applies to the field. */
    const val MAX_DIGITS = 19

    /**
     * Shortest number any network in [lengths] issues — Maestro's twelve.
     *
     * Below this, no network scheme can explain the number, so [isImplausible] flags it even for
     * [CardBrand.UNKNOWN]. That is the one place this file makes a claim about an unrecognised
     * number, and it is deliberately the weakest claim available: "no network prints one this
     * short", not "this is wrong".
     */
    const val MIN_DIGITS = 12

    private val lengths: Map<CardBrand, Set<Int>> = mapOf(
        CardBrand.VISA to setOf(13, 16, 19),
        CardBrand.MASTERCARD to setOf(16),
        CardBrand.AMEX to setOf(15),
        CardBrand.DISCOVER to setOf(16, 19),
        CardBrand.JCB to setOf(16, 17, 18, 19),
        CardBrand.DINERS to setOf(14, 16, 19),
        CardBrand.UNIONPAY to setOf(16, 17, 18, 19),
        CardBrand.MAESTRO to (12..19).toSet(),
    )

    /**
     * Digit counts [brand] is known to issue, or an empty set when nothing is known.
     *
     * [CardBrand.UNKNOWN] returns empty rather than some default, because "we did not recognise this
     * number" and "this number is the wrong length" are different statements and only the second one
     * should ever reach the user.
     */
    fun acceptedLengths(brand: CardBrand): Set<Int> = lengths[brand].orEmpty()

    /**
     * True when [digitCount] is a length [brand] never issues, so a hint is worth showing.
     *
     * False for an unknown brand and false for an empty field: a user who has typed nothing is not
     * being told they typed it wrong.
     */
    fun isUnexpectedLength(brand: CardBrand, digitCount: Int): Boolean {
        if (digitCount == 0) return false
        val accepted = acceptedLengths(brand)
        return accepted.isNotEmpty() && digitCount !in accepted
    }

    /**
     * True when [digitCount] cannot be a payment card of [brand], so a step should say so once.
     *
     * Wider than [isUnexpectedLength] in exactly one respect: it also covers [CardBrand.UNKNOWN]
     * below [MIN_DIGITS]. Six digits is not a Visa the user mistyped — no network at all prints a
     * number that short — and until this existed the wizard let it through in silence, because the
     * only signal was [isUnexpectedLength] and that is false for every unrecognised number by
     * construction. Reported from hands-on use, 2026-08-11: "entered 6 digits, proceed → still
     * lets me proceed instead of telling me invalid card."
     *
     * **Still not a rejection.** The doctrine at the top of this file holds: the caller's job is to
     * say this once and let the user through on a second, deliberate action. A loyalty or gift
     * number that is genuinely eight digits long stays storable — it costs one extra tap and gains
     * an explanation, which is a better trade than a silent pass. The line above [MIN_DIGITS] is
     * the *only* thing asserted about an unrecognised number; nothing here runs Luhn, and the
     * counterpart decision at [CardBrands] keeping it out is unchanged.
     */
    fun isImplausible(brand: CardBrand, digitCount: Int): Boolean = when {
        digitCount == 0 -> false
        isUnexpectedLength(brand, digitCount) -> true
        // Only for UNKNOWN: a recognised brand has already been judged against its own set above,
        // and Maestro legitimately issues twelve.
        else -> brand == CardBrand.UNKNOWN && digitCount < MIN_DIGITS
    }

    /**
     * The length at which a wizard may move the user on by itself, or null if it never should.
     *
     * The **longest** length the brand issues, so the field has finished growing before the wizard
     * leaves it. Firing at the common sixteen moved a nineteen-digit Discover (and Visa, UnionPay,
     * Diners, JCB) on to Expiry with the last three digits still in flight, and they landed in the
     * expiry field. Luhn cannot rescue an earlier length: a sixteen-digit prefix of a valid
     * nineteen-digit number passes one time in ten, which is a wrong advance, only rarer. The cost
     * is that a sixteen-digit Visa now needs one tap on Continue; fixed-length brands (Mastercard
     * sixteen, Amex fifteen) stay hands-free.
     *
     * Null for [CardBrand.UNKNOWN]: unrecognised means loyalty, membership or gift, which follow no
     * scheme, so there is no length at which we can claim they are finished.
     */
    fun autoAdvanceLength(brand: CardBrand): Int? = acceptedLengths(brand).maxOrNull()

    /** CVV digit count printed for [brand]: four for Amex, three everywhere else. */
    fun cvvLength(brand: CardBrand): Int = if (brand == CardBrand.AMEX) 4 else 3

    /**
     * True when a CVV has been started but is shorter than [brand] prints.
     *
     * A blank CVV is never unexpected — a card stored without one is a legitimate thing to keep, and
     * the wizard says so. Nor is any length unexpected on [CardBrand.UNKNOWN]: with no network
     * identified there is no printed code to compare against, and the hint has no name to use.
     */
    fun isUnexpectedCvvLength(brand: CardBrand, digitCount: Int): Boolean =
        brand != CardBrand.UNKNOWN && digitCount > 0 && digitCount != cvvLength(brand)
}
