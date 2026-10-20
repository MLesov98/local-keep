package com.localkeep.core.vault

import cash.z.ecc.android.bip39.Mnemonics.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The words copied out of the Recovery Kit PDF must open the vault the typed words open.
 *
 * The PDF's text layer carries typographic ligatures, so a viewer's "copy" returns U+FB00..U+FB04
 * (`ﬀ ﬁ ﬂ ﬃ ﬄ`) where the page visibly reads `ff`/`fi`/`fl`. The bundled BIP-39 wordlist is ASCII and
 * `kotlin-bip39` normalises nothing, so the paste missed the wordlist and the user — who by
 * definition has forgotten their master password, this being the only path left — was told to
 * "check the order and the spelling" about a word that looks correct on screen, and charged unlock
 * backoff for every retry. Roughly one BIP-39 word in twenty-five carries such a pair, so a 24-word
 * kit is better than even money to contain one. Found by the pre-iOS gap sweep 2026-09-11 (G1).
 *
 * The fix is NFKD in `canonicalMnemonic`, which BIP-39 specifies for mnemonic sentences anyway.
 *
 * The entropy is walked deterministically rather than generated, so this test picks the same
 * mnemonic on every run: a random kit would make it pass or fail by luck.
 */
class RecoveryKitLigaturePasteTest {

    /** Longest first, so `ffi` is not matched as `ff` and left with a stray `i`. */
    private val ligatures = listOf(
        "ffi" to '\uFB03',
        "ffl" to '\uFB04',
        "ff" to '\uFB00',
        "fi" to '\uFB01',
        "fl" to '\uFB02',
    )

    /** A valid 24-word mnemonic that contains a ligature-able pair, plus its pasted-from-PDF form. */
    private fun typedAndPasted(): Pair<String, String> {
        for (i in 0..255) {
            val entropy = ByteArray(32).also { it[31] = i.toByte() }
            val code = MnemonicCode(entropy)
            val typed = code.chars.concatToString()
            code.clear()
            val hit = ligatures.firstOrNull { (pair, _) -> typed.contains(pair) } ?: continue
            return typed to typed.replaceFirst(hit.first, hit.second.toString())
        }
        fail("no mnemonic containing a ligature pair found in 256 deterministic candidates")
    }

    @Test
    fun `words pasted from the kit PDF derive the same recovery key as typed words`() {
        val (typed, pasted) = typedAndPasted()
        assertTrue(pasted.any { it.code in 0xFB00..0xFB04 }, "the pasted form must contain a ligature")
        assertContentEquals(
            RecoveryKit.deriveRecoveryKek(typed),
            RecoveryKit.deriveRecoveryKek(pasted),
            "a ligature pasted out of the app's own PDF must not be treated as a spelling mistake",
        )
    }

    /** NFKD must not disturb a mnemonic that was already plain ASCII. */
    @Test
    fun `a typed ascii mnemonic is unaffected`() {
        val (typed, _) = typedAndPasted()
        assertContentEquals(
            RecoveryKit.deriveRecoveryKek(typed),
            RecoveryKit.deriveRecoveryKek(typed.uppercase()),
            "capitals already did not matter, and normalising must not change that",
        )
    }

    /** The QR payload is canonicalised by the same function, so it must agree too. */
    @Test
    fun `the qr payload is normalised the same way`() {
        val (typed, pasted) = typedAndPasted()
        assertTrue(
            RecoveryKit.formatForQrCode(typed) == RecoveryKit.formatForQrCode(pasted),
            "formatForQrCode shares canonicalMnemonic and must normalise identically",
        )
    }
}
