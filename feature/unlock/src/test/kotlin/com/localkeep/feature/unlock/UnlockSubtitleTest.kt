package com.localkeep.feature.unlock

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The unlock screen must not claim the vault is only on this device.
 *
 * It is the claim this project keeps having to remove. It went from the Recovery Kit screen, the
 * drawer badge and this screen's own footer in earlier waves, and each time the fix stopped at the
 * strings someone happened to be looking at. It survived in the header of the screen every user
 * sees at every unlock, one Column above a footer that had already been rewritten to stop saying
 * it.
 *
 * Why it is false: `vault.enc` reaches Android Auto Backup unless the user turned cloud backup
 * off. The backup agent is declared in the shared manifest, so this holds for both flavours, and
 * `foss` having no INTERNET permission does not save it — the OS transport performs the upload,
 * not the app.
 *
 * The subtitle moved from a Kotlin `const val` (i18n extraction, 2026-07-30) to
 * `res/values/strings.xml` as `unlock_subtitle`, so `stringResource` can resolve it per locale.
 * This is a pure JVM `kotlin.test`, with no Compose/Robolectric runtime to call `stringResource`
 * from, so it reads the base (English) resource file directly.
 *
 * **It guards English only, and cannot be made to guard the translations.** The check is a
 * substring match on an English phrase; the same false claim in French would use French words and
 * pass silently. A language-agnostic version would need to understand meaning, which a unit test
 * does not. The translated variants are therefore covered the only way they can be — by the human
 * native review that the security-copy review protocol requires, where `unlock_subtitle` is
 * listed under Tier G as a claim about where vault data lives.
 */
class UnlockSubtitleTest {

    private val xml: String = File("src/main/res/values/strings.xml").readText()

    private fun string(name: String): String =
        Regex("""<string name="$name">(.*?)</string>""").find(xml)?.groupValues?.get(1)
            ?: error("$name not found in strings.xml")

    private val subtitle: String = string("unlock_subtitle")

    /**
     * The Recovery Kit variant of the same line, added 2026-08-30.
     *
     * It has to be held to the same standard for the reason this whole test exists: the false
     * "only on this device" claim survived an earlier sweep precisely because the fix stopped at
     * the strings someone happened to be looking at. A second subtitle in the same hero Column is
     * exactly where it would come back unnoticed.
     */
    private val recoverySubtitle: String = string("unlock_recovery_subtitle")

    @Test
    fun doesNotClaimTheVaultIsOnThisDeviceOnly() {
        assertTrue("this device only" !in subtitle, "said: $subtitle")
        assertTrue("device only" !in subtitle, "said: $subtitle")
        assertTrue("never leaves" !in subtitle, "said: $subtitle")
    }

    /** Removing the false clause must not have removed the instruction the screen exists to give. */
    @Test
    fun stillTellsTheUserWhatToDo() {
        assertTrue("master password" in subtitle, "said: $subtitle")
    }

    @Test
    fun recoverySubtitleDoesNotClaimTheVaultIsOnThisDeviceOnly() {
        assertTrue("this device only" !in recoverySubtitle, "said: $recoverySubtitle")
        assertTrue("device only" !in recoverySubtitle, "said: $recoverySubtitle")
        assertTrue("never leaves" !in recoverySubtitle, "said: $recoverySubtitle")
    }

    /**
     * The whole point of the second subtitle: it must name the control that is actually on screen.
     *
     * Fails without the fix, because before it there was no `unlock_recovery_subtitle` at all and
     * the screen showed the master-password line over the 24-word grid. Asserting the absence of
     * "master password" is what makes this a regression test rather than a spelling check — the
     * defect was the wrong instruction, not a missing one.
     */
    @Test
    fun recoverySubtitleNamesTheRecoveryKitAndNotThePassword() {
        assertTrue("Recovery Kit" in recoverySubtitle, "said: $recoverySubtitle")
        assertTrue("master password" !in recoverySubtitle, "said: $recoverySubtitle")
    }
}
