package com.localkeep.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The licence list names what is actually in the build the reader is holding.
 *
 * Red before the fix. `LicensesDialog` walked `ThirdPartyLicenses.groups` unconditionally, so a
 * `foss` install — which ships no billing code at all, as `FossEntitlement`'s own KDoc says — showed
 * the reader a group named "Android Software Development Kit License (play build only)" listing
 * `com.android.billingclient`, and a second one listing Play Core.
 *
 * That is not a cosmetic slip on this particular screen. A third-party licence notice exists to
 * state truthfully what is in the binary; one that over-declares is wrong in the same way one that
 * under-declares is, and this is the screen a reader checks precisely because they do not trust a
 * summary. The "(play build only)" text in the licence name was a note to whoever maintains the
 * list, not an instruction the renderer was following.
 *
 * `canPurchase` is the flavour signal, and it is the one the domain already carries — its KDoc in
 * `Entitlement` says it is "true only in `play`". No new plumbing, no `BuildConfig` reach-around.
 */
class LicencesMatchTheBinaryTest {

    @Test
    fun `the foss list declares no play-only dependency`() {
        val fossVisible = ThirdPartyLicenses.groups.filterNot { it.playOnly }
        val coordinates = fossVisible.flatMap { it.libraries }.map { it.coordinates }

        val leaked = coordinates.filter {
            it.startsWith("com.android.billingclient") || it.startsWith("com.google.android.play")
        }
        assertEquals(
            emptyList(), leaked,
            "a foss build ships none of these, so its licence notice must not declare them: $leaked",
        )
    }

    @Test
    fun `the play-only groups are still declared for the play build`() {
        // The control, and it must stay green while the test above goes red. The lazy way to make
        // the foss list honest is to delete the two groups outright — which would leave the *play*
        // build under-declaring two non-open-source licences it genuinely does ship, and that is
        // the worse direction of the same error.
        val playOnly = ThirdPartyLicenses.groups.filter { it.playOnly }
        assertEquals(2, playOnly.size, "expected exactly the billing and Play Core groups")
        val coordinates = playOnly.flatMap { it.libraries }.map { it.coordinates }
        assertTrue(
            coordinates.any { it.startsWith("com.android.billingclient") },
            "the billing licence must still be declared in the play build; found $coordinates",
        )
        assertTrue(
            coordinates.any { it.startsWith("com.google.android.play") },
            "the Play Core licence must still be declared in the play build; found $coordinates",
        )
    }

    @Test
    fun `the dialog filters on the flavour signal rather than rendering everything`() {
        val screen = source("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt")
        assertTrue(
            "includePlayOnly || !it.playOnly" in screen,
            "LicensesDialog must filter the groups; flagging them and then rendering all of them " +
                "anyway changes nothing the reader can see",
        )
        assertTrue(
            "includePlayOnly = state.canPurchase" in screen,
            "the flavour signal must be `canPurchase` — the one the domain already defines as " +
                "true only in play",
        )
    }

    private companion object {
        fun source(relative: String): String {
            val direct = File(relative)
            val file = if (direct.isFile) direct else File(repoRoot(), "feature/settings/$relative")
            assertTrue(file.isFile, "expected source file at $relative")
            return file.readText()
        }

        fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repo root not found above ${File("").absolutePath}")
    }
}
