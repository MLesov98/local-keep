package com.localkeep.autofill

/**
 * What a stored card may say about itself in a suggestion row.
 *
 * A suggestion row is drawn by the platform, outside this app's window, and may be kept and
 * redrawn after the fill — so it is not a place for a value. Nothing here returns a PAN, a CVV or a
 * PIN; the most it will produce is the last four digits, which is what the card's own title already
 * carries (`"<domain> ****<last4>"`, written by the save path) and what the vault list shows.
 */
internal object CardMask {

    /** The bullet used for a masked digit. Not a translatable string — it is a glyph, not copy. */
    private const val BULLET = '•'

    /**
     * `•••• 1234` for a number long enough to have a tail, and a fully masked stub otherwise.
     *
     * The short case matters: a partially-entered or malformed number must not fall through to
     * showing itself. Four digits is the tail every card network exposes on receipts and the one
     * PCI DSS permits displaying, so it is the ceiling here rather than a starting point.
     */
    fun tail(number: String): String {
        val digits = number.filter(Char::isDigit)
        if (digits.length < MIN_DIGITS_FOR_TAIL) return BULLET.toString().repeat(4)
        return "${BULLET.toString().repeat(4)} ${digits.takeLast(4)}"
    }

    /**
     * `MM/YY`, or null when either half is missing.
     *
     * Null rather than a partial string: a checkout field filled with half an expiry is worse than
     * one left alone, because the user has to notice and undo it. [expMonth] is 1-12 and
     * [expYear] is stored as a four-digit year.
     */
    fun expiry(expMonth: Int?, expYear: Int?): String? {
        val month = expMonth ?: return null
        val year = expYear ?: return null
        if (month !in 1..12) return null
        return "%02d/%02d".format(month, year % 100)
    }

    private const val MIN_DIGITS_FOR_TAIL = 8
}
