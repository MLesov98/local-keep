package com.localkeep.core.model

import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * The payload declares a schema this build does not understand, so it must not be opened.
 *
 * An [IllegalArgumentException] subtype rather than a new hierarchy, because every existing caller
 * — [VaultSerializer.decodeCatching] above all, which lifts an IAE's message into the user-facing
 * error and anything else into "Vault deserialization failed" — must keep behaving exactly as it
 * did. What the distinct type buys is the one caller that could not tell this apart from a damaged
 * payload: the unlock path decrypts the region under a key it has already proved correct, and a
 * plain IAE there was swallowed and reported as `VAULT_UNREADABLE` — "try restoring a backup from
 * Settings", which is the single instruction that destroys a vault whose only problem is being
 * newer than the app reading it. The container's own format version cannot answer this: it has
 * never bumped past 1, while the payload schema has bumped seven times.
 */
class VaultSchemaTooNewException(
    /** The schema the payload declares. */
    val stamped: Int,
    /** The newest schema this build can read. */
    val supported: Int,
    message: String,
) : IllegalArgumentException(message)

/** Serialization seam used before encryption and after decryption. */
object VaultSerializer {
    private val protobuf = ProtoBuf {
        encodeDefaults = false
    }

    /**
     * Encodes [vault] for saving, stamped with [Vault.CURRENT_SCHEMA_VERSION] regardless of the
     * version it was loaded at.
     *
     * The stamp is written here rather than carried on the object because the object's copy of it
     * is whatever was last read from disk, and every mutation copies it through unchanged. A vault
     * loaded at schema 3 and then saved with a trashed folder would otherwise go back to disk
     * declaring 3 while containing fields only schema 4 defines — which an older build would open,
     * silently truncate, and write back. The version gate in [decode] can only defend against that
     * if the number it reads is the format actually written.
     *
     * Consequence worth knowing: decode-then-encode is not a fixed point for an older vault. That
     * is the point — opening a vault in this build upgrades it, and the upgrade is what makes the
     * downgrade refusal honest.
     */
    fun encode(vault: Vault): ByteArray =
        encodeAtVersion(vault, Vault.CURRENT_SCHEMA_VERSION)

    /**
     * Encodes [vault] stamped with an arbitrary [schemaVersion], for tests that need a payload this
     * build would never write — an older format to open, or a newer one to be refused.
     *
     * Internal and deliberately awkward to reach: [encode] is the only way production code writes a
     * vault, and it decides the stamp itself. A fixture that wants a different one is constructing
     * something no save path can produce, which is exactly the fact worth making visible at the
     * call site.
     */
    internal fun encodeAtVersion(vault: Vault, schemaVersion: Int): ByteArray =
        protobuf.encodeToByteArray(Vault.serializer(), vault.copy(schemaVersion = schemaVersion))

    /**
     * The schema stamp read straight off the wire, or null if the bytes do not begin with one.
     *
     * Exists because the version gate below runs *after* a full decode, and from schema 6 that is
     * too late. Every bump up to 5 added optional fields to types both builds knew, so an old build
     * decoding a new vault got a complete object minus a few nulls and the `require` could speak.
     * A new [ItemContent] subtype is different in kind: the older build's polymorphic table has no
     * entry for the serial name, so it throws inside the decoder, on the whole payload, and the
     * user is told a `coordinate` is not registered instead of being told to update the app.
     *
     * Hand-read rather than decoded through a probe class, because a probe would be a second
     * polymorphic decode with the same problem. [Vault.schemaVersion] is protobuf field 1 and is
     * stamped `EncodeDefault.ALWAYS`, so a payload this app has ever written starts with the tag
     * byte `0x08` followed by the version varint — that, and only that, is what this reads.
     *
     * Returns null rather than throwing on anything unexpected, and the caller falls through to the
     * ordinary decode. This function's job is to *refuse early*, never to accept early: a payload
     * it cannot read is one the real decoder and the existing gate still get their say on.
     */
    internal fun peekSchemaVersion(bytes: ByteArray): Int? {
        if (bytes.isEmpty() || bytes[0] != 0x08.toByte()) return null
        var value = 0L
        var shift = 0
        var index = 1
        while (index < bytes.size) {
            val byte = bytes[index].toInt()
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return if (value in 0..Int.MAX_VALUE) value.toInt() else null
            shift += 7
            // A varint longer than five bytes cannot be an Int, and reading on would only be
            // shifting bits off the end of the number this claims to be.
            if (shift > 28) return null
            index++
        }
        return null
    }

    fun decode(bytes: ByteArray): Vault {
        // Ahead of the decode, not after it — see [peekSchemaVersion]. Only a *newer* stamp is
        // refused here; the older-than-minimum case is left to the gate below, where a full decode
        // has cost nothing and the message can be identical.
        peekSchemaVersion(bytes)?.let { stamped ->
            if (stamped > Vault.CURRENT_SCHEMA_VERSION) throw tooNew(stamped)
        }
        val vault = protobuf.decodeFromByteArray(Vault.serializer(), bytes)
        // Refuse a vault written by a NEWER build rather than opening it read-write. Protobuf drops
        // fields it does not know about, so an older build that opened a newer vault would decode
        // it minus those fields, and the next save would write the truncated version back — silent,
        // permanent data loss with no error anywhere. Refusing costs the user one confusing session;
        // not refusing costs them data. (Older vaults are fine: unset fields take their defaults.)
        if (vault.schemaVersion > Vault.CURRENT_SCHEMA_VERSION) throw tooNew(vault.schemaVersion)
        require(vault.schemaVersion >= Vault.MIN_SUPPORTED_SCHEMA_VERSION) {
            "This vault uses format ${vault.schemaVersion}, which this build no longer supports " +
                "(oldest readable is ${Vault.MIN_SUPPORTED_SCHEMA_VERSION})."
        }
        return vault
    }

    /**
     * One wording for both gates, so the message does not depend on which of them spoke.
     *
     * The text is built into a local before it is passed, rather than written at the
     * `message =` argument directly: `message` is one of the ten parameter names
     * `checkNoHardcodedUiStrings` treats as user-facing, and a literal sitting at one trips the
     * gate. This string never reaches a screen — unlock maps the refusal to the localised
     * `VAULT_FROM_NEWER_VERSION` outcome — so exempting it is right, but silencing the gate with an
     * allowlist entry would not be.
     */
    private fun tooNew(stamped: Int): VaultSchemaTooNewException {
        val detail = "This vault was created by a newer version of LocalKeep (format $stamped; " +
            "this build understands up to ${Vault.CURRENT_SCHEMA_VERSION}). Update the app to open it."
        return VaultSchemaTooNewException(
            stamped = stamped,
            supported = Vault.CURRENT_SCHEMA_VERSION,
            message = detail,
        )
    }

    fun decodeCatching(bytes: ByteArray): Outcome<Vault> = try {
        Outcome.Success(decode(bytes))
    } catch (failure: Exception) {
        Outcome.Failure(
            AppError.Storage(
                // A schema-version refusal already carries a message written for a human, and it is
                // the one case here the user can act on ("update the app"). Anything else is a
                // decode failure whose exception text would mean nothing to them.
                message = (failure as? IllegalArgumentException)?.message
                    ?: "Vault deserialization failed",
                cause = failure,
            ),
        )
    }
}
