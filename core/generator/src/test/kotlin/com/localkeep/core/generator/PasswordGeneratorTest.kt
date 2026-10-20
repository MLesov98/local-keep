package com.localkeep.core.generator

import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordGeneratorTest {
    private lateinit var words: List<String>

    @BeforeTest
    fun loadWordlist() {
        words = File("src/main/assets/eff-large-wordlist.txt").readLines()
        PasswordGenerator.setWordlist(words)
    }

    @Test
    fun `bundled EFF list is complete unique and normalized`() {
        assertEquals(7_776, words.size)
        assertEquals(7_776, words.distinct().size)
        assertEquals("abacus", words.first())
        assertEquals("zoom", words.last())
        assertTrue(words.all { it.matches(Regex("[a-z]+(?:-[a-z]+)*")) })
    }

    @Test
    fun `random generation honors length and every minimum`() {
        val config = PasswordGenerator.RandomConfig(
            length = 24,
            minUppercase = 4,
            minLowercase = 5,
            minDigits = 3,
            minSymbols = 4,
        )

        repeat(100) {
            val password = PasswordGenerator.generateRandom(config).value
            assertEquals(24, password.length)
            assertTrue(password.count(Char::isUpperCase) >= 4)
            assertTrue(password.count(Char::isLowerCase) >= 5)
            assertTrue(password.count(Char::isDigit) >= 3)
            assertTrue(password.count { !it.isLetterOrDigit() } >= 4)
        }
    }

    @Test
    fun `ambiguous characters are excluded when requested`() {
        val config = PasswordGenerator.RandomConfig(length = 256, noAmbiguous = true)

        repeat(20) {
            assertFalse(
                PasswordGenerator.generateRandom(config).value.any { it in "Il1O0" },
            )
        }
    }

    @Test
    fun `single enabled class fills entire output`() {
        val lower = PasswordGenerator.RandomConfig(
            length = 32,
            uppercase = false,
            lowercase = true,
            digits = false,
            symbols = false,
            minUppercase = 0,
            minLowercase = 8,
            minDigits = 0,
            minSymbols = 0,
        )
        val symbols = lower.copy(
            lowercase = false,
            symbols = true,
            minLowercase = 0,
            minSymbols = 8,
        )

        assertTrue(PasswordGenerator.generateRandom(lower).value.all(Char::isLowerCase))
        assertTrue(
            PasswordGenerator.generateRandom(symbols).value.all { !it.isLetterOrDigit() },
        )
    }

    @Test
    fun `invalid random constraints fail before generation`() {
        assertFailsWith<IllegalArgumentException> {
            PasswordGenerator.RandomConfig(length = 4, minUppercase = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            PasswordGenerator.RandomConfig(
                uppercase = false,
                minUppercase = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PasswordGenerator.RandomConfig(
                uppercase = false,
                lowercase = false,
                digits = false,
                symbols = false,
                minUppercase = 0,
                minLowercase = 0,
                minDigits = 0,
                minSymbols = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PasswordGenerator.RandomConfig(
                length = 4,
                minUppercase = 2,
                minLowercase = 2,
                minDigits = 1,
                minSymbols = 0,
            )
        }
    }

    @Test
    fun `passphrase uses requested words separator capitalization and number`() {
        val result = PasswordGenerator.generatePassphrase(
            PasswordGenerator.PassphraseConfig(
                wordCount = 7,
                separator = ".",
                capitalize = true,
                includeNumber = true,
            ),
        )
        val parts = result.value.split(".")

        assertEquals(8, parts.size)
        assertTrue(parts.take(7).all { it.first().isUpperCase() })
        assertTrue(parts.last().length == 3 && parts.last().all(Char::isDigit))
    }

    @Test
    fun `passphrase entropy reflects word and number choice spaces`() {
        val sixWords = PasswordGenerator.PassphraseConfig(wordCount = 6)
        val withNumber = sixWords.copy(includeNumber = true)

        assertTrue(PasswordGenerator.entropy(sixWords) > 77.0)
        assertTrue(
            PasswordGenerator.entropy(withNumber) >
                PasswordGenerator.entropy(sixWords) + 9.9,
        )
        assertEquals(
            PasswordGenerator.entropy(withNumber),
            PasswordGenerator.generatePassphrase(withNumber).entropyBits,
        )
    }

    @Test
    fun `random result reports config entropy estimate`() {
        val config = PasswordGenerator.RandomConfig(length = 32)

        assertEquals(
            PasswordGenerator.entropy(config),
            PasswordGenerator.generateRandom(config).entropyBits,
        )
        assertTrue(PasswordGenerator.entropy(config) > 190.0)
    }

    @Test
    fun `invalid wordlists and passphrase constraints are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PasswordGenerator.setWordlist(List(7_776) { "duplicate" })
        }
        assertFailsWith<IllegalArgumentException> {
            PasswordGenerator.PassphraseConfig(wordCount = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            PasswordGenerator.PassphraseConfig(separator = "long")
        }
    }
}
