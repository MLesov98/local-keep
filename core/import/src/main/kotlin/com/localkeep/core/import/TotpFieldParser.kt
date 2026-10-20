package com.localkeep.core.import

import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import java.net.URI
import java.net.URLDecoder

/**
 * Minimal otpauth:// URI + raw-Base32-secret parser shared by every importer in this module.
 * Deliberately self-contained (no dependency on :core:totp's OtpAuthParser — see design decision
 * in Task 3) so :core:import stays a pure-Kotlin/JVM leaf module. Never throws: anything it
 * can't parse becomes null, so a malformed TOTP field just means "this item has no TOTP",
 * never a failed import.
 */
internal object TotpFieldParser {

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567="

    /** The alphabet without padding — the symbols the decoder actually consumes. */
    private const val BASE32_DATA_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Transcribed from `OtpAuthParser.MAX_PERIOD_SECONDS`, which is private to `:core:totp` — a
     *  module this pure-Kotlin leaf deliberately does not depend on (see the class kdoc). */
    private const val MAX_PERIOD_SECONDS = 86_400

    /** The un-padded data lengths (mod 8) RFC 4648 Base32 permits — transcribed from
     *  `:core:totp`'s `Base32.VALID_REMAINDERS` for the same "don't depend on that module" reason. */
    private val VALID_REMAINDERS = setOf(0, 2, 4, 5, 7)

    /** Parses a raw TOTP field value: either a bare Base32 secret or a full otpauth:// URI. */
    fun parse(raw: String): Totp? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("otpauth://", ignoreCase = true)) {
            parseUri(trimmed)
        } else {
            // filterNot(isWhitespace), not replace(" ", ""). Every other place that handles a
            // Base32 secret — the item editor, OtpAuthParser, and TotpGenerator's own decoder —
            // strips all whitespace, so this parser was the strictest link in the chain: a secret
            // separated by tabs or newlines failed the alphabet check below and the item lost its
            // TOTP silently, on an import, where nothing tells the user a field went missing. The
            // same secret typed into the editor was accepted, and would have generated fine here
            // too, because the generator strips what this refused to.
            val secret = trimmed.uppercase().filterNot(Char::isWhitespace)
            // Was an alphabet-membership check only, which let through a secret of an invalid Base32
            // length (e.g. `data.length % 8 !in {0,2,4,5,7}`) or with non-zero trailing bits — a
            // string TotpGenerator's own Base32.decode rejects. Every generator call site wraps that
            // decode in runCatching, so such a secret imported as a Totp that silently never renders
            // a code. Reject it here instead: null is this parser's "not a TOTP", and the CSV/KDBX
            // paths rescue the raw value into a HIDDEN custom field rather than losing it.
            if (!isDecodableBase32(secret)) null else Totp(secretBase32 = secret)
        }
    }

    private fun parseUri(raw: String): Totp? = try {
        val uri = URI(raw)
        // Only period-based TOTP is supported. An otpauth://hotp/... URI is COUNTER-based — parsing
        // it as TOTP (discarding the counter, defaulting period=30) yields an item that can never
        // produce matching codes, so skip it entirely (null = "no TOTP") rather than mis-import it.
        if (!uri.host.equals("totp", ignoreCase = true)) return null
        val query = (uri.rawQuery ?: "").split("&").mapNotNull { pair ->
            val idx = pair.indexOf("=")
            if (idx < 0) null
            else decodeQueryPart(pair.substring(0, idx)) to decodeQueryPart(pair.substring(idx + 1))
        }.toMap()

        val secret = query["secret"]?.uppercase()?.filterNot(Char::isWhitespace) ?: return null
        // The URI branch validated everything about the token (digits/period/algorithm) EXCEPT the
        // one field that actually has to decode — the secret went through with no Base32 check at
        // all. A `secret=` of an invalid length or alphabet produced a Totp the generator can never
        // turn into a code (its Base32.decode throws, wrapped in runCatching everywhere). Reject it
        // to null, this parser's contract for "not a TOTP", so the value is rescued not silently dead.
        if (!isDecodableBase32(secret)) return null
        val label = (uri.path ?: "").removePrefix("/")
        val (labelIssuer, account) = if (label.contains(":")) {
            val parts = label.split(":", limit = 2)
            parts[0] to parts[1]
        } else {
            "" to label
        }

        // Validated to the same rules the editor's OtpAuthParser enforces, and for the same reason
        // it enforces them: a URI carrying `digits=7`, `period=0` or `algorithm=SHA384` describes a
        // token this app cannot produce. Accepting it built an item that looked imported and was
        // dead — every TotpGenerator call site wraps its `require` in runCatching, so the code
        // simply never rendered — or, for an unknown algorithm folded to SHA1, an item that showed
        // six digits that were confidently wrong. Null instead, which is this parser's contract for
        // "this field is not a TOTP": the CSV and KDBX paths already rescue a rejected seed into a
        // named custom field rather than dropping it, so the value still reaches the user.
        val algorithm = when (query["algorithm"]?.uppercase()) {
            null, "SHA1" -> TotpAlgorithm.SHA1
            "SHA256" -> TotpAlgorithm.SHA256
            "SHA512" -> TotpAlgorithm.SHA512
            else -> return null
        }
        val digits = query["digits"]?.let { it.toIntOrNull() ?: return null } ?: 6
        val periodSec = query["period"]?.let { it.toIntOrNull() ?: return null } ?: 30
        if (digits != 6 && digits != 8) return null
        if (periodSec !in 1..MAX_PERIOD_SECONDS) return null

        Totp(
            issuer = query["issuer"] ?: labelIssuer,
            account = account,
            secretBase32 = secret,
            algorithm = algorithm,
            digits = digits,
            periodSec = periodSec,
        )
    } catch (e: Exception) {
        // Exception, not Throwable: "never throws" is a promise about malformed input, and an
        // OutOfMemoryError or a LinkageError is not malformed input. Swallowing those turned a
        // broken JVM into a silently TOTP-less import — the one outcome the caller cannot detect.
        null
    }

    /**
     * Percent-decodes one otpauth query key or value, leaving a literal `+` alone.
     *
     * `URLDecoder` implements HTML *form* encoding, where `+` means a space — but an otpauth URI's
     * query is a URI query, in which `+` is a sub-delim standing for itself and needs no escaping
     * (RFC 3986 §3.4). So `issuer=Google+Workspace` arrived as "Google Workspace", a label the user
     * never wrote, silently and with no way to notice short of re-reading the export. Escaping the
     * literal `+` to `%2B` before decoding keeps `URLDecoder`'s percent handling — including its
     * rejection of malformed escapes, which the caller's catch turns into "no TOTP" — while taking
     * away the one rule that does not belong to this grammar. This app's own encoder never emits a
     * bare `+` for a space either: `OtpAuthParser.encode` percent-encodes it as `%20` for exactly
     * this ambiguity, so nothing LocalKeep wrote depends on the old reading.
     */
    private fun decodeQueryPart(raw: String): String =
        URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")

    /**
     * True iff [secret] is one `:core:totp`'s `Base32.decode` would accept — transcribed rather than
     * depended on, for the pure-Kotlin-leaf reason in the class kdoc. Enforces the same four rules:
     * non-empty, padding trailing-only (and a padded value's total length a multiple of 8), the
     * un-padded data length ∈ [VALID_REMAINDERS] (mod 8), every symbol in the alphabet, and no
     * non-zero trailing bits. A secret this rejects is one the generator throws on, i.e. a token
     * this app can never produce a code for.
     */
    private fun isDecodableBase32(secret: String): Boolean {
        val compact = secret.filterNot(Char::isWhitespace)
        if (compact.isEmpty()) return false
        val paddingStart = compact.indexOf('=').let { if (it < 0) compact.length else it }
        if (!compact.substring(paddingStart).all { it == '=' }) return false
        if (paddingStart < compact.length && compact.length % 8 != 0) return false
        val data = compact.substring(0, paddingStart)
        if (data.length % 8 !in VALID_REMAINDERS) return false
        if (data.any { it.uppercaseChar() !in BASE32_DATA_ALPHABET }) return false
        // The decoder's final `require(buffer == 0)`: a secret whose last symbol carries bits that
        // spill past the byte boundary is rejected, so this mirrors its acceptance set exactly.
        var buffer = 0
        var bitCount = 0
        for (character in data) {
            buffer = (buffer shl 5) or BASE32_DATA_ALPHABET.indexOf(character.uppercaseChar())
            bitCount += 5
            if (bitCount >= 8) {
                bitCount -= 8
                buffer = buffer and ((1 shl bitCount) - 1)
            }
        }
        return buffer == 0
    }
}
