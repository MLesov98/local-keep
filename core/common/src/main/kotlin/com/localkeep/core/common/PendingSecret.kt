package com.localkeep.core.common

/**
 * A generated password in flight from the generator to the item editor.
 *
 * **Why this exists rather than a navigation argument.** The obvious way to carry a value between
 * two destinations is to put it in the route — `item/new?password=…`. That must never happen here.
 * Navigation arguments live in the back-stack entry, which the framework serialises into the saved
 * instance state; the system writes that to disk to survive process death. A password routed that
 * way ends up in plaintext in the app's data directory, outside the vault and outside its
 * encryption, and stays there until the OS decides otherwise. It would also appear in the route
 * string in any navigation logging.
 *
 * So the value is handed over in memory instead, and [take] consumes it — the editor reads it once,
 * on arrival, and nothing is left behind if the user backs out. [clear] is called when the vault
 * locks, which covers a handover that was offered and then never collected.
 *
 * This holds no more than the screens either side of it already do: the same string is in the
 * generator's UI state before the handover and in the editor's after it.
 */
object PendingSecret {

    /**
     * What is in flight: the password, and — when the app drew it rather than the user typing it —
     * how many bits of entropy the draw was worth.
     *
     * **The bits are here because entropy is a property of the draw, not of the string.** The
     * generator names a value from its bit count; the item form scores a *typed* password with
     * zxcvbn, which reads the characters. Both are right for their own input, and the handover used
     * to carry only the string — so the editor scored a drawn value as a typed one and the two
     * screens gave the same password two different verdicts, one tap apart: "Reasonable · 77 bits"
     * on the Generator against "Excellent" in the editor (F-29, 2026-09-15 visual pass). Nothing
     * downstream can recover the figure, because the string does not say which pool it came from.
     *
     * Null for a handover with no measure behind it — there is no such call site today, and the
     * default is there so a future one has to state that rather than invent a number.
     *
     * This adds nothing to what is held in memory that was not already held: the bit count is a
     * Double the generator's own UI state carries on screen at the time of the tap.
     */
    data class Offer(val secret: String, val entropyBits: Double? = null)

    @Volatile
    private var value: Offer? = null

    /** Offers [secret] to the next editor to open. Replaces anything already waiting. */
    fun offer(secret: String, entropyBits: Double? = null) {
        value = Offer(secret, entropyBits)
    }

    /** Returns the waiting offer and forgets it, or null if there is none. */
    fun take(): Offer? {
        val taken = value
        value = null
        return taken
    }

    fun clear() {
        value = null
    }
}
