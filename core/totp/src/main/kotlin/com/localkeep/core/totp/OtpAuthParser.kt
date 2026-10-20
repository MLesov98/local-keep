package com.localkeep.core.totp

import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Arrays
import java.util.Locale

/** Parser/serializer for Google Authenticator's `otpauth://totp/` key URI format. */
object OtpAuthParser {
    fun parse(uri: String): Outcome<Totp> = try {
        Outcome.Success(parseOrThrow(uri))
    } catch (failure: ParseFailure) {
        Outcome.Failure(AppError.Validation(failure.message ?: "invalid otpauth URI"))
    } catch (_: Exception) {
        // Never attach the source URI as a cause: it contains the TOTP secret.
        Outcome.Failure(AppError.Validation("invalid otpauth URI"))
    }

    fun toUri(totp: Totp): String {
        require(!totp.steam) { "Steam tokens do not have a standard otpauth representation" }
        val secret = normalizeAndValidateSecret(totp.secretBase32)
        validateFields(
            issuer = totp.issuer,
            account = totp.account,
            digits = totp.digits,
            period = totp.periodSec,
        )
        val label = if (totp.issuer.isEmpty()) {
            totp.account
        } else {
            "${totp.issuer}:${totp.account}"
        }
        val parameters = buildList {
            add("secret=${encode(secret)}")
            if (totp.issuer.isNotEmpty()) add("issuer=${encode(totp.issuer)}")
            if (totp.algorithm != TotpAlgorithm.SHA1) {
                add("algorithm=${totp.algorithm.name}")
            }
            if (totp.digits != DEFAULT_DIGITS) add("digits=${totp.digits}")
            if (totp.periodSec != DEFAULT_PERIOD_SECONDS) add("period=${totp.periodSec}")
        }
        return "otpauth://totp/${encode(label)}?${parameters.joinToString("&")}"
    }

    private fun parseOrThrow(source: String): Totp {
        checkParse(source.length in 1..MAX_URI_LENGTH, "otpauth URI length is invalid")
        val uri = try {
            URI(source)
        } catch (_: Exception) {
            throw ParseFailure("otpauth URI syntax is invalid")
        }
        checkParse(uri.scheme.equals("otpauth", ignoreCase = true), "scheme must be otpauth")
        checkParse(uri.host.equals("totp", ignoreCase = true), "type must be totp")
        checkParse(uri.userInfo == null && uri.port == -1, "authority is invalid")
        checkParse(uri.rawFragment == null, "fragment is not allowed")

        val label = uri.path?.removePrefix("/").orEmpty()
        checkParse(label.isNotBlank() && label.length <= MAX_LABEL_LENGTH, "label is invalid")
        val separator = label.indexOf(':')
        val issuerFromLabel = if (separator >= 0) label.substring(0, separator) else ""
        val account = if (separator >= 0) label.substring(separator + 1) else label
        val parameters = parseQuery(uri.rawQuery.orEmpty())
        val suppliedSecret = parameters["secret"]
            ?: throw ParseFailure("secret parameter is required")
        val secret = try {
            normalizeAndValidateSecret(suppliedSecret)
        } catch (_: IllegalArgumentException) {
            throw ParseFailure("secret is not valid Base32")
        }
        val issuerFromQuery = parameters["issuer"]
        if (issuerFromLabel.isNotEmpty() && issuerFromQuery != null) {
            checkParse(
                sameIssuer(issuerFromLabel, issuerFromQuery),
                "label and query issuers must match",
            )
        }
        val issuer = issuerFromQuery ?: issuerFromLabel
        val algorithm = when (parameters["algorithm"]?.uppercase(Locale.ROOT)) {
            null, "SHA1" -> TotpAlgorithm.SHA1
            "SHA256" -> TotpAlgorithm.SHA256
            "SHA512" -> TotpAlgorithm.SHA512
            else -> throw ParseFailure("algorithm is not supported")
        }
        // `encoder=steam` is the de-facto parameter every authenticator that speaks Steam Guard
        // writes — Aegis, Ente Auth, 2FAS and Bitwarden all import and export it. It was read and
        // discarded until 4B-5 (2026-09-20): the app has a working Steam mode, so a user pasting a
        // Steam link got a plain six-digit token, silently, and codes that Steam will never accept.
        // Refused rather than ignored for any other value, the same way an unknown `algorithm` is:
        // an encoder this build does not know changes what the code *is*, so guessing at 6-digit
        // RFC 6238 would reproduce exactly the defect this line fixes.
        val steam = when (parameters["encoder"]?.lowercase(Locale.ROOT)) {
            null -> false
            "steam" -> true
            else -> throw ParseFailure("encoder is not supported")
        }
        val digits = parseInteger(parameters["digits"], DEFAULT_DIGITS, "digits")
        val period = parseInteger(parameters["period"], DEFAULT_PERIOD_SECONDS, "period")
        validateFields(issuer, account, digits, period)
        return Totp(
            issuer = issuer,
            account = account,
            secretBase32 = secret,
            algorithm = algorithm,
            digits = digits,
            periodSec = period,
            steam = steam,
        )
    }

    private fun normalizeAndValidateSecret(value: String): String {
        val normalized = value
            .filterNot(Char::isWhitespace)
            .uppercase(Locale.ROOT)
            .trimEnd('=')
        require(normalized.length <= MAX_SECRET_LENGTH) { "Base32 secret is too long" }
        val decoded = Base32.decode(normalized)
        try {
            require(decoded.isNotEmpty()) { "Base32 secret is empty" }
        } finally {
            Arrays.fill(decoded, 0)
        }
        return normalized
    }

    private fun validateFields(
        issuer: String,
        account: String,
        digits: Int,
        period: Int,
    ) {
        require(issuer.length <= MAX_FIELD_LENGTH) { "issuer is too long" }
        require(':' !in issuer) { "issuer must not contain a colon" }
        require(account.isNotBlank() && account.length <= MAX_FIELD_LENGTH) {
            "account is invalid"
        }
        require(digits == 6 || digits == 8) { "digits must be 6 or 8" }
        require(period in 1..MAX_PERIOD_SECONDS) { "period is out of range" }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val parameters = LinkedHashMap<String, String>()
        query.split('&').forEach { pair ->
            checkParse(pair.isNotEmpty(), "query parameter is empty")
            val separator = pair.indexOf('=')
            checkParse(separator > 0, "query parameter is malformed")
            val key = decode(pair.substring(0, separator))
            val value = decode(pair.substring(separator + 1))
            checkParse(parameters.put(key, value) == null, "query parameter is duplicated")
        }
        return parameters
    }

    private fun parseInteger(
        value: String?,
        default: Int,
        name: String,
    ): Int = value?.toIntOrNull() ?: if (value == null) {
        default
    } else {
        throw ParseFailure("$name must be an integer")
    }

    /**
     * Whether the label's issuer and the query's issuer name the same thing.
     *
     * The two are decoded by different rules on purpose: the query is form-decoded ('+' is a
     * space, which also keeps a secret grouped as `JBSW+Y3DP` readable), while the label is a URI
     * path where '+' is literal — `alice+work@example.com` must stay as typed. A generator that
     * spells a space as '+' in *both* (Java's `URLEncoder`, Python's `quote_plus`) therefore
     * disagrees with itself, and `Example+Corp:alice?issuer=Example+Corp` was refused as a
     * mismatch (2026-10-02). Either spelling of the label is accepted; the query's is what is kept.
     */
    private fun sameIssuer(label: String, query: String): Boolean =
        label == query || label.replace('+', ' ') == query

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, UTF_8)
    } catch (_: IllegalArgumentException) {
        throw ParseFailure("percent encoding is invalid")
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, UTF_8).replace("+", "%20")

    private fun checkParse(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) throw ParseFailure(message)
    }

    private class ParseFailure(message: String) : IllegalArgumentException(message)

    private const val DEFAULT_DIGITS = 6
    private const val DEFAULT_PERIOD_SECONDS = 30
    private const val MAX_PERIOD_SECONDS = 86_400
    private const val MAX_URI_LENGTH = 8_192
    private const val MAX_LABEL_LENGTH = 512
    private const val MAX_FIELD_LENGTH = 256
    private const val MAX_SECRET_LENGTH = 1_024
    private const val UTF_8 = "UTF-8"
}
