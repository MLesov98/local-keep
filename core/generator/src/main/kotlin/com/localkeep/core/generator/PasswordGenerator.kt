package com.localkeep.core.generator

import java.security.SecureRandom
import kotlin.math.log2

object PasswordGenerator {
    private const val UPPERCASE_ALL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWERCASE_ALL = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS_ALL = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.<>?/"
    private const val AMBIGUOUS = "Il1O0"
    private const val EFF_WORD_COUNT = 7_776
    private val random = SecureRandom()

    @Volatile
    private var wordlist: List<String>? = null

    data class RandomConfig(
        /**
         * 21, not 20, because 20 lands one bit short of the top strength band and the app then
         * disagrees with itself: the default pool is 83 characters after the ambiguous ones are
         * removed, so 20 draws are 127.5 bits and `LkStrength.ofBits` calls anything under 128
         * "Strong". Four consecutive generates all reported `Strong / 127 bits` while a hand-typed
         * password reached "Excellent" (4A-F1, device 2026-09-20) — one vocabulary is supposed to
         * mean one thing, and the generator's own output was the value that could not reach the top
         * of it. 21 draws are 133 bits. The bands are untouched.
         */
        val length: Int = 21,
        val uppercase: Boolean = true,
        val lowercase: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val minUppercase: Int = 2,
        val minLowercase: Int = 2,
        val minDigits: Int = 2,
        val minSymbols: Int = 2,
        val noAmbiguous: Boolean = true,
    ) {
        init {
            require(length in 4..1_024) { "length must be between 4 and 1024" }
            require(
                listOf(minUppercase, minLowercase, minDigits, minSymbols).all { it >= 0 },
            ) { "minimum counts must be non-negative" }
            require(uppercase || lowercase || digits || symbols) {
                "at least one character class must be enabled"
            }
            require(uppercase || minUppercase == 0) {
                "uppercase minimum requires uppercase characters"
            }
            require(lowercase || minLowercase == 0) {
                "lowercase minimum requires lowercase characters"
            }
            require(digits || minDigits == 0) {
                "digit minimum requires digit characters"
            }
            require(symbols || minSymbols == 0) {
                "symbol minimum requires symbol characters"
            }
            val minimumTotal = minUppercase + minLowercase + minDigits + minSymbols
            require(minimumTotal <= length) {
                "length $length is shorter than minimum-count total $minimumTotal"
            }
        }
    }

    data class PassphraseConfig(
        val wordCount: Int = 6,
        val separator: String = "-",
        val capitalize: Boolean = false,
        val includeNumber: Boolean = false,
    ) {
        init {
            require(wordCount in 3..20) { "word count must be between 3 and 20" }
            require(separator.length <= 2) { "separator must contain at most 2 characters" }
        }
    }

    data class GeneratedPassword(
        val value: String,
        val entropyBits: Double,
    )

    /** Installs an immutable copy of the 7,776-word EFF long list. */
    fun setWordlist(words: List<String>) {
        require(words.size == EFF_WORD_COUNT) {
            "EFF wordlist must contain exactly $EFF_WORD_COUNT words"
        }
        require(words.toSet().size == EFF_WORD_COUNT) { "EFF wordlist contains duplicates" }
        require(words.all { it.matches(EFF_WORD_PATTERN) }) {
            "EFF wordlist must contain lowercase ASCII words"
        }
        wordlist = words.toList()
    }

    fun generateRandom(config: RandomConfig = RandomConfig()): GeneratedPassword {
        val upper = characterClass(UPPERCASE_ALL, config.noAmbiguous)
        val lower = characterClass(LOWERCASE_ALL, config.noAmbiguous)
        val digits = characterClass(DIGITS_ALL, config.noAmbiguous)
        val pool = buildString {
            if (config.uppercase) append(upper)
            if (config.lowercase) append(lower)
            if (config.digits) append(digits)
            if (config.symbols) append(SYMBOLS)
        }
        val positions = IntArray(config.length) { it }
        shuffle(positions)
        val output = CharArray(config.length)
        var positionIndex = 0

        fun allocate(count: Int, source: String) {
            repeat(count) {
                output[positions[positionIndex++]] = source[random.nextInt(source.length)]
            }
        }

        if (config.uppercase) allocate(config.minUppercase, upper)
        if (config.lowercase) allocate(config.minLowercase, lower)
        if (config.digits) allocate(config.minDigits, digits)
        if (config.symbols) allocate(config.minSymbols, SYMBOLS)
        while (positionIndex < positions.size) {
            output[positions[positionIndex++]] = pool[random.nextInt(pool.length)]
        }
        // The String is the value we have to hand back and cannot be wiped; the buffer that built
        // it can be, and is. It costs one line and removes a full copy of the password from the
        // heap immediately rather than whenever the collector next runs.
        val generated = String(output)
        output.fill('\u0000')
        return GeneratedPassword(generated, entropy(config))
    }

    fun generatePassphrase(config: PassphraseConfig = PassphraseConfig()): GeneratedPassword {
        val words = wordlist ?: error("EFF wordlist has not been loaded")
        val chosen = List(config.wordCount) {
            val word = words[random.nextInt(words.size)]
            if (config.capitalize) {
                word.replaceFirstChar(Char::uppercaseChar)
            } else {
                word
            }
        }.toMutableList()
        if (config.includeNumber) {
            chosen += random.nextInt(1_000).toString().padStart(3, '0')
        }
        return GeneratedPassword(chosen.joinToString(config.separator), entropy(config))
    }

    /**
     * Upper-bound estimate based on independent draws from the enabled pool. Mandatory class
     * counts reduce the exact distribution slightly; this value is informational UI guidance.
     */
    fun entropy(config: RandomConfig): Double {
        var poolSize = 0
        if (config.uppercase) {
            poolSize += characterClass(UPPERCASE_ALL, config.noAmbiguous).length
        }
        if (config.lowercase) {
            poolSize += characterClass(LOWERCASE_ALL, config.noAmbiguous).length
        }
        if (config.digits) {
            poolSize += characterClass(DIGITS_ALL, config.noAmbiguous).length
        }
        if (config.symbols) poolSize += SYMBOLS.length
        return config.length * log2(poolSize.toDouble())
    }

    fun entropy(config: PassphraseConfig): Double =
        config.wordCount * log2(EFF_WORD_COUNT.toDouble()) +
            if (config.includeNumber) log2(1_000.0) else 0.0

    private fun characterClass(
        characters: String,
        noAmbiguous: Boolean,
    ): String = if (noAmbiguous) {
        characters.filterNot { it in AMBIGUOUS }
    } else {
        characters
    }

    private fun shuffle(values: IntArray) {
        for (index in values.lastIndex downTo 1) {
            val replacement = random.nextInt(index + 1)
            val current = values[index]
            values[index] = values[replacement]
            values[replacement] = current
        }
    }

    private val EFF_WORD_PATTERN = Regex("[a-z]+(?:-[a-z]+)*")
}
