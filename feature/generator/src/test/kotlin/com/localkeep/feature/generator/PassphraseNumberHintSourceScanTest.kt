package com.localkeep.feature.generator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * F42, 2026-09-13: the Passphrase-mode "Include a number" row borrowed `generator_hint_digits`
 * ("0–9", the Random-mode single-digit hint) while `PasswordGenerator.generatePassphrase`
 * (core/generator) actually appends `random.nextInt(1_000).toString().padStart(3, '0')` — a
 * zero-padded three-digit string, `000`–`999`. Verified against the generator source (grep below)
 * before writing `generator_hint_number`'s value, per the constraint on this finding.
 *
 * Source scan rather than a render: `GeneratorScreen` needs a real `ItemEditViewModel`-shaped
 * dependency chain to compose, the same reason [HeadingSemanticsSourceScanTest] gives for its
 * module's screens.
 */
class PassphraseNumberHintSourceScanTest {

    private fun generatorScreenSource(): String {
        val file = File("src/main/kotlin/com/localkeep/feature/generator/GeneratorScreen.kt")
        assertTrue(file.exists(), "GeneratorScreen.kt not found at ${file.absolutePath}")
        return file.readText()
    }

    private fun passwordGeneratorSource(): String {
        val file = File("../../core/generator/src/main/kotlin/com/localkeep/core/generator/PasswordGenerator.kt")
        assertTrue(file.exists(), "PasswordGenerator.kt not found at ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun `the generator really does append a zero-padded three-digit number`() {
        val src = passwordGeneratorSource()
        assertTrue(
            "random.nextInt(1_000).toString().padStart(3, '0')" in src,
            "PasswordGenerator.generatePassphrase no longer appends a zero-padded 3-digit number — " +
                "re-derive generator_hint_number's value before trusting this test",
        )
    }

    @Test
    fun `the passphrase include-a-number row hints the real range, not the random-mode digit hint`() {
        val text = generatorScreenSource()
        val rowStart = text.indexOf("stringResource(R.string.generator_include_number)")
        assertTrue(rowStart > 0, "generator_include_number row not found — did the composable move?")
        val rowEnd = text.indexOf(")\n                    }", rowStart).let { if (it > rowStart) it else text.length }
        val row = text.substring(rowStart, rowEnd)
        assertTrue(
            "hint = stringResource(R.string.generator_hint_number)" in row,
            "the Include-a-number row's hint = stringResource(...) call does not reference " +
                "generator_hint_number",
        )
        // Checked against the actual call, not a bare substring: the comment right above the fixed
        // call deliberately names generator_hint_digits in prose (explaining what NOT to use), which
        // a plain "!in row" check would trip on and misreport the fix as reverted.
        assertTrue(
            "hint = stringResource(R.string.generator_hint_digits)" !in row,
            "the Include-a-number row still hints generator_hint_digits (\"0–9\"), which describes a " +
                "single random digit, not the three-digit 000-999 the generator actually appends",
        )
    }

    @Test
    fun `generator_hint_number's value is the real 000-999 range`() {
        val stringsFile = File("src/main/res/values/strings.xml")
        assertTrue(stringsFile.exists(), "strings.xml not found at ${stringsFile.absolutePath}")
        val xml = stringsFile.readText()
        val marker = "name=\"generator_hint_number\""
        val idx = xml.indexOf(marker)
        assertTrue(idx > 0, "generator_hint_number is not declared in strings.xml")
        val valueStart = xml.indexOf('>', idx) + 1
        val valueEnd = xml.indexOf("</string>", valueStart)
        val value = xml.substring(valueStart, valueEnd)
        assertTrue(value == "000–999", "generator_hint_number is \"$value\", expected \"000–999\"")
    }
}
