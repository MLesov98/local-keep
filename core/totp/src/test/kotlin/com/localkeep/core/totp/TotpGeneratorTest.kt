package com.localkeep.core.totp

import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TotpGeneratorTest {
    private val timestamps = listOf(
        59L,
        1_111_111_109L,
        1_111_111_111L,
        1_234_567_890L,
        2_000_000_000L,
        20_000_000_000L,
    )

    @Test
    fun `RFC 6238 SHA1 vectors pass`() {
        assertVectors(
            secret = "12345678901234567890".toByteArray(),
            algorithm = TotpAlgorithm.SHA1,
            expected = listOf(
                "94287082",
                "07081804",
                "14050471",
                "89005924",
                "69279037",
                "65353130",
            ),
        )
    }

    @Test
    fun `RFC 6238 SHA256 vectors pass`() {
        assertVectors(
            secret = "12345678901234567890123456789012".toByteArray(),
            algorithm = TotpAlgorithm.SHA256,
            expected = listOf(
                "46119246",
                "68084774",
                "67062674",
                "91819424",
                "90698825",
                "77737706",
            ),
        )
    }

    @Test
    fun `RFC 6238 SHA512 vectors pass`() {
        assertVectors(
            secret =
                "1234567890123456789012345678901234567890123456789012345678901234"
                    .toByteArray(),
            algorithm = TotpAlgorithm.SHA512,
            expected = listOf(
                "90693936",
                "25091201",
                "99943326",
                "93441116",
                "38618901",
                "47863826",
            ),
        )
    }

    @Test
    fun `six-digit RFC HOTP truncation is padded correctly`() {
        val secret = "12345678901234567890".toByteArray()

        assertEquals(
            "287082",
            TotpGenerator.generateRaw(
                secret,
                TotpAlgorithm.SHA1,
                6,
                30,
                Instant.ofEpochSecond(59),
            ),
        )
        repeat(100) { counter ->
            val code = TotpGenerator.generateRaw(
                secret,
                TotpAlgorithm.SHA1,
                6,
                30,
                Instant.ofEpochSecond(counter * 30L),
            )
            assertEquals(6, code.length)
            assertTrue(code.all(Char::isDigit))
        }
    }

    @Test
    fun `model generation decodes Base32 and reports remaining seconds`() {
        val totp = Totp(
            issuer = "RFC",
            account = "test",
            secretBase32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
            digits = 8,
        )

        assertEquals(
            "94287082",
            TotpGenerator.generate(totp, Instant.ofEpochSecond(59)),
        )
        assertEquals(1, TotpGenerator.remainingSeconds(totp, Instant.ofEpochSecond(59)))
        assertEquals(30, TotpGenerator.remainingSeconds(totp, Instant.ofEpochSecond(60)))
    }

    @Test
    fun `Steam reference outputs use least-significant character first`() {
        val secret = "12345678901234567890".toByteArray()
        val expected = mapOf(
            0L to "GG5F5",
            59L to "PV9M4",
            1_111_111_109L to "PY4YB",
            1_234_567_890L to "VHHQY",
        )

        expected.forEach { (timestamp, code) ->
            assertEquals(
                code,
                TotpGenerator.generateSteam(secret, Instant.ofEpochSecond(timestamp)),
            )
        }
    }

    @Test
    fun `invalid TOTP parameters are rejected`() {
        val secret = byteArrayOf(1)
        val now = Instant.EPOCH

        assertFailsWith<IllegalArgumentException> {
            TotpGenerator.generateRaw(ByteArray(0), TotpAlgorithm.SHA1, 6, 30, now)
        }
        assertFailsWith<IllegalArgumentException> {
            TotpGenerator.generateRaw(secret, TotpAlgorithm.SHA1, 7, 30, now)
        }
        assertFailsWith<IllegalArgumentException> {
            TotpGenerator.generateRaw(secret, TotpAlgorithm.SHA1, 6, 0, now)
        }
        assertFailsWith<IllegalArgumentException> {
            TotpGenerator.generateSteam(secret, Instant.ofEpochSecond(-1))
        }
    }

    @Test
    fun `Base32 RFC vectors round-trip`() {
        val vectors = mapOf(
            "f" to "MY======",
            "fo" to "MZXQ====",
            "foo" to "MZXW6===",
            "foob" to "MZXW6YQ=",
            "fooba" to "MZXW6YTB",
            "foobar" to "MZXW6YTBOI======",
        )

        vectors.forEach { (plain, encoded) ->
            assertEquals(encoded, Base32.encode(plain.toByteArray()))
            assertContentEquals(plain.toByteArray(), Base32.decode(encoded))
            assertContentEquals(plain.toByteArray(), Base32.decode(encoded.lowercase()))
        }
    }

    @Test
    fun `Base32 rejects malformed length padding characters and trailing bits`() {
        listOf("A", "M=Y=====", "MZX!", "MZ", "MY=====").forEach {
            assertFailsWith<IllegalArgumentException> { Base32.decode(it) }
        }
    }

    private fun assertVectors(
        secret: ByteArray,
        algorithm: TotpAlgorithm,
        expected: List<String>,
    ) {
        timestamps.zip(expected).forEach { (timestamp, code) ->
            assertEquals(
                code,
                TotpGenerator.generateRaw(
                    secret = secret,
                    algorithm = algorithm,
                    digits = 8,
                    periodSec = 30,
                    timestamp = Instant.ofEpochSecond(timestamp),
                ),
            )
        }
    }
}
