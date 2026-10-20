package com.localkeep.core.totp

import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import java.time.Instant
import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 4226/6238 TOTP plus Steam Guard's five-character rendering. */
object TotpGenerator {
    private const val STEAM_ALPHABET = "23456789BCDFGHJKMNPQRTVWXY"

    fun generate(
        totp: Totp,
        timestamp: Instant = Instant.now(),
    ): String {
        val secret = Base32.decode(totp.secretBase32)
        return try {
            if (totp.steam) {
                generateSteam(secret, timestamp)
            } else {
                generateRaw(
                    secret = secret,
                    algorithm = totp.algorithm,
                    digits = totp.digits,
                    periodSec = totp.periodSec,
                    timestamp = timestamp,
                )
            }
        } finally {
            Arrays.fill(secret, 0)
        }
    }

    fun generateRaw(
        secret: ByteArray,
        algorithm: TotpAlgorithm,
        digits: Int,
        periodSec: Int,
        timestamp: Instant,
    ): String {
        require(secret.isNotEmpty()) { "TOTP secret must not be empty" }
        require(digits == 6 || digits == 8) { "TOTP digits must be 6 or 8" }
        require(periodSec > 0) { "TOTP period must be positive" }
        require(!timestamp.isBefore(Instant.EPOCH)) { "TOTP timestamp must not be negative" }
        val counter = timestamp.epochSecond / periodSec
        val hmac = hmac(secret, counterBytes(counter), algorithm)
        return try {
            val modulus = if (digits == 6) 1_000_000 else 100_000_000
            (dynamicTruncate(hmac) % modulus).toString().padStart(digits, '0')
        } finally {
            Arrays.fill(hmac, 0)
        }
    }

    fun generateSteam(
        secret: ByteArray,
        timestamp: Instant = Instant.now(),
    ): String {
        require(secret.isNotEmpty()) { "Steam secret must not be empty" }
        require(!timestamp.isBefore(Instant.EPOCH)) { "Steam timestamp must not be negative" }
        val hmac = hmac(
            secret,
            counterBytes(timestamp.epochSecond / STEAM_PERIOD_SECONDS),
            TotpAlgorithm.SHA1,
        )
        return try {
            var code = dynamicTruncate(hmac)
            buildString(STEAM_CODE_LENGTH) {
                repeat(STEAM_CODE_LENGTH) {
                    append(STEAM_ALPHABET[code % STEAM_ALPHABET.length])
                    code /= STEAM_ALPHABET.length
                }
            }
        } finally {
            Arrays.fill(hmac, 0)
        }
    }

    /**
     * The window the code actually rolls on — [STEAM_PERIOD_SECONDS] for a Steam entry, whatever the
     * stored [Totp.periodSec] says.
     *
     * [generateSteam] hard-codes its counter at 30 seconds, and nothing stops a stored entry from
     * carrying a different period: the editor merely *hides* the algorithm/digits/period controls
     * once Steam is switched on, so an entry imported or built with `period=60` keeps that value
     * beside `steam=true` forever. Every caller that counts down had been reading the stored field.
     */
    fun effectivePeriodSec(totp: Totp): Int =
        if (totp.steam) STEAM_PERIOD_SECONDS else totp.periodSec

    /**
     * Whole seconds until the current code is replaced.
     *
     * Counts on [effectivePeriodSec], not on [Totp.periodSec]. A Steam entry stored with a 60-second
     * period used to report up to 60 seconds remaining on a code that had already changed at 30 —
     * which reached the detail screen's countdown ring, its "refreshes every N seconds" caption and
     * the autofill row's "expires in N s", all three of them stating a number the generator does not
     * honour.
     */
    fun remainingSeconds(
        totp: Totp,
        timestamp: Instant = Instant.now(),
    ): Int {
        require(totp.periodSec > 0) { "TOTP period must be positive" }
        require(!timestamp.isBefore(Instant.EPOCH)) { "TOTP timestamp must not be negative" }
        val period = effectivePeriodSec(totp)
        return (period - timestamp.epochSecond % period).toInt()
    }

    private fun hmac(
        secret: ByteArray,
        counter: ByteArray,
        algorithm: TotpAlgorithm,
    ): ByteArray {
        val name = when (algorithm) {
            TotpAlgorithm.SHA1 -> "HmacSHA1"
            TotpAlgorithm.SHA256 -> "HmacSHA256"
            TotpAlgorithm.SHA512 -> "HmacSHA512"
        }
        return Mac.getInstance(name).run {
            init(SecretKeySpec(secret, name))
            doFinal(counter)
        }
    }

    private fun counterBytes(counter: Long): ByteArray =
        ByteArray(Long.SIZE_BYTES) { index ->
            (counter ushr (Byte.SIZE_BITS * (Long.SIZE_BYTES - 1 - index))).toByte()
        }

    private fun dynamicTruncate(hmac: ByteArray): Int {
        val offset = hmac.last().toInt() and 0x0f
        return ((hmac[offset].toInt() and 0x7f) shl 24) or
            ((hmac[offset + 1].toInt() and 0xff) shl 16) or
            ((hmac[offset + 2].toInt() and 0xff) shl 8) or
            (hmac[offset + 3].toInt() and 0xff)
    }

    /**
     * Steam Guard's fixed window. Public because the UI has to count down on the same number the
     * generator rolls on, and the alternative was a literal 30 typed into the detail screen.
     */
    const val STEAM_PERIOD_SECONDS = 30
    private const val STEAM_CODE_LENGTH = 5
}

/** Strict RFC 4648 Base32 codec with optional canonical padding and ASCII whitespace. */
internal object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val decodeTable = IntArray(128) { -1 }.apply {
        ALPHABET.forEachIndexed { index, character ->
            this[character.code] = index
            this[character.lowercaseChar().code] = index
        }
    }

    fun decode(encoded: String): ByteArray {
        val compact = encoded.filterNot(Char::isWhitespace)
        require(compact.isNotEmpty()) { "Base32 value must not be empty" }
        val paddingStart = compact.indexOf('=').let { if (it < 0) compact.length else it }
        require(compact.substring(paddingStart).all { it == '=' }) {
            "Base32 padding must be trailing"
        }
        if (paddingStart < compact.length) {
            require(compact.length % 8 == 0) { "padded Base32 length is invalid" }
        }
        val data = compact.substring(0, paddingStart)
        require(data.length % 8 in VALID_REMAINDERS) { "Base32 length is invalid" }
        require(
            data.all { it.code < decodeTable.size && decodeTable[it.code] >= 0 },
        ) { "Base32 contains an invalid character" }

        val output = ByteArray(data.length * 5 / 8)
        var outputIndex = 0
        var buffer = 0
        var bitCount = 0
        for (character in data) {
            buffer = (buffer shl 5) or decodeTable[character.code]
            bitCount += 5
            if (bitCount >= 8) {
                bitCount -= 8
                output[outputIndex++] = (buffer ushr bitCount).toByte()
                buffer = buffer and ((1 shl bitCount) - 1)
            }
        }
        require(buffer == 0) { "Base32 has non-zero trailing bits" }
        return output
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val output = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitCount = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bitCount += 8
            while (bitCount >= 5) {
                bitCount -= 5
                output.append(ALPHABET[(buffer ushr bitCount) and 0x1f])
                buffer = buffer and ((1 shl bitCount) - 1)
            }
        }
        if (bitCount > 0) {
            output.append(ALPHABET[(buffer shl (5 - bitCount)) and 0x1f])
        }
        while (output.length % 8 != 0) output.append('=')
        return output.toString()
    }

    private val VALID_REMAINDERS = setOf(0, 2, 4, 5, 7)
}
