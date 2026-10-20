package com.localkeep.credential

import java.security.MessageDigest

/**
 * A short, opaque, collision-free key for a string that has to appear in a PendingIntent's data URI.
 *
 * Two requirements pull in opposite directions there. A PendingIntent is matched on its Intent's
 * `filterEquals` — action, data, type, component, categories, and *not* extras — so two requests
 * that share a data URI share a PendingIntent, and with `FLAG_UPDATE_CURRENT` the second silently
 * replaces the first. That argues for putting the rpId (or the item identity) in the URI verbatim.
 * But the URI is visible to the system and to anything reading intents, and which sites a user holds
 * credentials for is exactly the kind of thing this app does not hand out.
 *
 * A truncated SHA-256 satisfies both: opaque, and wide enough that a collision is not something an
 * attacker can construct. `hashCode()` — what this replaced — is 32 bits and trivially collidable.
 *
 * Not a security boundary. It stops accidental *and* deliberate PendingIntent aliasing; it is not
 * secrecy for the input, which a short domain would not survive against a dictionary anyway.
 */
internal object OpaqueKey {

    /** 128 bits, hex-encoded — half of SHA-256, the usual truncation for a collision-resistant id. */
    private const val BYTES = 16

    fun of(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(BYTES * 2) {
            for (index in 0 until BYTES) append("%02x".format(digest[index]))
        }
    }
}
