package com.localkeep.core.model

import com.localkeep.core.model.serializers.InstantSerializer
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Vault item payload.
 *
 * FROZEN: subtype serial names and existing field numbers must never change.
 */
@Serializable
sealed interface ItemContent

@Serializable
@SerialName("login")
data class Login(
    @ProtoNumber(1) val username: String = "",
    @ProtoNumber(2) val password: String = "",
    @ProtoNumber(3) val passwordHistory: List<PasswordHistoryEntry> = emptyList(),
    @ProtoNumber(4) val uris: List<UriMatch> = emptyList(),
    @ProtoNumber(5) val androidPackages: List<String> = emptyList(),
    @ProtoNumber(6) val totp: Totp? = null,
    @ProtoNumber(7) val notes: String = "",
    /**
     * When the [password] itself was last set/changed — NOT bumped by metadata-only edits (title,
     * tag, folder), unlike [VaultItem.updatedAt]. Drives health "old password" age and the
     * password-preserving merge (see [ItemMerge]). Null on vaults written before this field existed
     * (protobuf-additive); callers fall back to `VaultItem.updatedAt` in that case.
     */
    @ProtoNumber(8)
    @Serializable(with = InstantSerializer::class)
    val passwordChangedAt: Instant? = null,
) : ItemContent {
    /** Redacted for the reason given on [Coordinate.toString] — this one carries the password. */
    override fun toString(): String = "Login(redacted)"
}

@Serializable
@SerialName("totp")
data class Totp(
    @ProtoNumber(1) val issuer: String = "",
    @ProtoNumber(2) val account: String = "",
    @ProtoNumber(3) val secretBase32: String = "",
    @ProtoNumber(4) val algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    @ProtoNumber(5) val digits: Int = 6,
    @ProtoNumber(6) val periodSec: Int = 30,
    @ProtoNumber(7) val steam: Boolean = false,
) : ItemContent {
    /** Redacted for the reason given on [Coordinate.toString] — [secretBase32] is the whole factor. */
    override fun toString(): String = "Totp(redacted)"
}

@Serializable
@SerialName("note")
data class Note(
    @ProtoNumber(1) val body: String = "",
) : ItemContent {
    /** Redacted for the reason given on [Coordinate.toString] — a secure note IS the secret. */
    override fun toString(): String = "Note(redacted)"
}

@Serializable
@SerialName("card")
data class Card(
    @ProtoNumber(1) val holder: String = "",
    @ProtoNumber(2) val number: String = "",
    @ProtoNumber(3) val brand: CardBrand = CardBrand.UNKNOWN,
    @ProtoNumber(4) val expMonth: Int? = null,
    @ProtoNumber(5) val expYear: Int? = null,
    @ProtoNumber(6) val cvv: String = "",
    @ProtoNumber(7) val pin: String = "",
    @ProtoNumber(8) val notes: String = "",
) : ItemContent {
    /** Redacted for the reason given on [Coordinate.toString] — PAN, CVV and PIN together. */
    override fun toString(): String = "Card(redacted)"
}

@Serializable
@SerialName("identity")
data class Identity(
    @ProtoNumber(1) val firstName: String = "",
    @ProtoNumber(2) val lastName: String = "",
    @ProtoNumber(3) val company: String = "",
    @ProtoNumber(4) val ssn: String = "",
    @ProtoNumber(5) val passport: String = "",
    @ProtoNumber(6) val license: String = "",
    @ProtoNumber(7) val email: String = "",
    @ProtoNumber(8) val phone: String = "",
    @ProtoNumber(9) val address: Address = Address(),
) : ItemContent {
    /** Redacted for the reason given on [Coordinate.toString] — SSN, passport and licence number. */
    override fun toString(): String = "Identity(redacted)"
}

/** Standalone file item. [blobRef] uses the same blob store as [Attachment]. */
@Serializable
@SerialName("file")
data class File(
    @ProtoNumber(1) val filename: String = "",
    @ProtoNumber(2) val mime: String = "",
    @ProtoNumber(3) val sizeBytes: Long = 0L,
    @ProtoNumber(4) val blobRef: String = "",
    @ProtoNumber(5) val thumbnailRef: String? = null,
    @ProtoNumber(6) val capturedInApp: Boolean = false,
    @ProtoNumber(7) val removedFromGallery: Boolean = false,
    /**
     * The user's own note about this file — what the scan is of, which account the statement
     * belongs to, why the photograph was kept. Field 8, added in schema 8.
     *
     * The same field [Login] carries at 7 and [Card] at 8, doing the same job: a File was the one
     * type a person could store but not annotate, so the only writable text it had was its title.
     * Indexed by `VaultRepository.searchableText` on the same terms as those two — it is prose the
     * user typed and expects to find again, and nothing about it is masked anywhere in the UI.
     */
    @ProtoNumber(8) val notes: String = "",
) : ItemContent

@Serializable
@SerialName("passkey")
data class Passkey(
    @ProtoNumber(1) val rpId: String = "",
    @ProtoNumber(2) val credentialId: ByteArray = ByteArray(0),
    @ProtoNumber(3) val userHandle: ByteArray = ByteArray(0),
    // DEK-wrapped private key bytes live inline, not in the streaming blob store.
    @ProtoNumber(4) val privateKeyRef: ByteArray = ByteArray(0),
    @ProtoNumber(5) val algorithm: PasskeyAlgorithm = PasskeyAlgorithm.ES256,
    @ProtoNumber(6) val signCounter: Long = 0L,
    @ProtoNumber(7)
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @ProtoNumber(8) val userName: String = "",
    @ProtoNumber(9) val userDisplayName: String = "",
) : ItemContent {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Passkey) return false

        return rpId == other.rpId &&
            credentialId.contentEquals(other.credentialId) &&
            userHandle.contentEquals(other.userHandle) &&
            privateKeyRef.contentEquals(other.privateKeyRef) &&
            algorithm == other.algorithm &&
            signCounter == other.signCounter &&
            createdAt == other.createdAt &&
            userName == other.userName &&
            userDisplayName == other.userDisplayName
    }

    override fun hashCode(): Int {
        var result = rpId.hashCode()
        result = 31 * result + credentialId.contentHashCode()
        result = 31 * result + userHandle.contentHashCode()
        result = 31 * result + privateKeyRef.contentHashCode()
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + signCounter.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + userName.hashCode()
        result = 31 * result + userDisplayName.hashCode()
        return result
    }

    /**
     * Redacted for the reason given on [Coordinate.toString], and here the generated form was worse
     * than usual: a data class prints a `ByteArray` as its identity hash, so [privateKeyRef] leaked
     * nothing legible — but [rpId], [userName] and [userDisplayName] together name the site and the
     * account, which is the linkage this app does not put in a log line.
     */
    override fun toString(): String = "Passkey(redacted)"
}

/**
 * One named snapshot of where the device was when the user asked, and nothing else.
 *
 * A snapshot, not a tracker: [capturedAt] is part of the data because "current" is a claim with a
 * time attached, and an item that showed a position without saying when it was taken would be
 * making a claim the vault cannot support. Nothing here updates itself — replacing the fix is an
 * explicit edit, the same as retyping a password.
 *
 * Deliberately four fields. Altitude, bearing, speed, provider names, geocoded addresses and map
 * URIs were all considered and left out: each is either a second thing to validate on the way back
 * out of an untrusted vault, or a value that only means something to a feature this item type does
 * not have. [accuracyMeters] is nullable only so a decoded vault written by some future build (or a
 * hand-built fixture) does not fail to open over a missing optional; every fix this app captures
 * carries one — see `LocationFixPolicy`.
 */
@Serializable
@SerialName("coordinate")
data class Coordinate(
    @ProtoNumber(1) val latitude: Double = 0.0,
    @ProtoNumber(2) val longitude: Double = 0.0,
    @ProtoNumber(3)
    @Serializable(with = InstantSerializer::class)
    val capturedAt: Instant,
    @ProtoNumber(4) val accuracyMeters: Float? = null,
) : ItemContent {
    /**
     * Redacted, deliberately, and this is the whole reason this type is not left with the generated
     * one.
     *
     * A data class prints its fields, and `toString()` is what an exception message, a `Log.d`, a
     * crash report and Compose's own recomposition tooling all reach for when handed an object.
     * Every one of those is a place a precise position must never appear — and none of them is a
     * call site anyone would think to audit, because none of them names the field. The only way to
     * make "coordinates are never logged" a property of the type rather than a rule about its
     * callers is to have nothing to print.
     *
     * `CoordinateRedactionTest` fails if this ever goes back to the generated form.
     */
    override fun toString(): String = "Coordinate(redacted)"

    companion object {
        val LATITUDE_RANGE: ClosedFloatingPointRange<Double> = -90.0..90.0
        val LONGITUDE_RANGE: ClosedFloatingPointRange<Double> = -180.0..180.0
    }
}

/**
 * Whether this coordinate is one the app is willing to show.
 *
 * Applied at both ends — before a write, and after a decode — because a decrypted vault is
 * untrusted input at the model boundary: the bytes are authenticated as *ours*, which says nothing
 * about a value a past build, a merge or a hand-edited fixture could have put there. A latitude of
 * NaN formats as "NaN°" on a detail screen and compares false against every range test written to
 * catch it, so the check has to be explicit rather than a `coerceIn`.
 */
fun Coordinate.isValid(): Boolean =
    latitude.isFinite() &&
        longitude.isFinite() &&
        latitude in Coordinate.LATITUDE_RANGE &&
        longitude in Coordinate.LONGITUDE_RANGE &&
        (accuracyMeters == null || (accuracyMeters.isFinite() && accuracyMeters >= 0f))

/**
 * A BIP-39 recovery phrase somebody else's — a hardware wallet's, an exchange's — kept in numbered
 * order so restore is not the first place a dropped or misspelled word is found.
 *
 * Not to be confused with the vault's *own* Recovery Kit ([RecoveryKit] in core/vault): that unlocks
 * this vault and never touches the device; this is a *stored secret like any other item*, and the
 * add flow refuses outright if the two are ever the same words. Only [words] and [passphrase] are
 * stored — the word count is [words].size, one source of truth, so a saved phrase cannot disagree
 * with itself. Both are the secret; see [toString].
 */
@Serializable
@SerialName("seedPhrase")
data class SeedPhrase(
    @ProtoNumber(1) val words: List<String> = emptyList(),
    /** The optional BIP-39 "25th word". Stored beside the words, never checked against them. */
    @ProtoNumber(2) val passphrase: String = "",
) : ItemContent {
    val wordCount: Int get() = words.size

    /**
     * Redacted for the same reason [Coordinate.toString] is: the words and the passphrase are the
     * entire secret, and `toString()` is exactly what a log line, an exception message or a
     * recomposition trace reaches for. There must be nothing to print. `SeedPhraseRedactionTest`
     * fails if this returns to the generated form.
     */
    override fun toString(): String = "SeedPhrase(redacted)"
}
