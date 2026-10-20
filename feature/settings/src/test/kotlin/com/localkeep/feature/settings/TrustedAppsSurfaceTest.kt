package com.localkeep.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Four things the trusted-apps screen owes the one review surface for autofill trust.
 *
 * All four measured on a device, 2026-09-20 (`results-3B.md`): an app identified only by the label
 * it declares about itself, so two apps both calling themselves "Chrome" are indistinguishable
 * (F2); removing an app's last site forgetting the whole app — pinned signing identity and all —
 * on one tap with no prompt, bypassing the confirmation the trash button on the same row raises
 * (F3); a one-shot snapshot that kept reading "Nothing trusted yet" for as long as the screen
 * stayed open after a grant was made (F4); and the forget confirmation silently dismissed by a
 * rotation, alone among the app's confirmations (F8).
 *
 * Asserted on the source, the same idiom as [DuressRemovalSurvivesEntitlementLossTest]:
 * `:feature:settings` declares Robolectric but no Compose UI test dependency.
 */
class TrustedAppsSurfaceTest {

    private val screen: String =
        File("src/main/kotlin/com/localkeep/feature/settings/TrustedAppsScreen.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    @Test
    fun `the source was read`() {
        assertTrue(
            "fun TrustedAppsScreen(" in screen,
            "the screen file is not what this test is reading; length=${screen.length}",
        )
    }

    @Test
    fun `an app is identified by its package, not only by the name it gives itself`() {
        // The exact rendered node, not a bare mention: `app.packageName` also appears as an
        // argument to `forgetDomain`, so a looser match passes on a card that shows only the label.
        assertTrue(
            "Text(\n                    app.packageName,\n                    style = LkText.caption," in screen,
            "the card renders only app.label: on the one screen built to review autofill trust, " +
                "two apps both claiming the label \"Chrome\" are indistinguishable",
        )
    }

    @Test
    fun `removing the last site asks the same question the trash button asks`() {
        assertTrue(
            "if (app.domains.size == 1)" in screen,
            "onRemoveDomain still goes straight to forgetDomain: removing the last site forgets " +
                "the app and its pinned signing identity with no prompt, bypassing the " +
                "confirmation the trash button on the same row raises",
        )
    }

    @Test
    fun `the list is re-read when the user comes back from the browser that made the grant`() {
        assertTrue(
            "viewModel.refreshOnResume()" in screen,
            "the trusted list is read once and never again, so a grant made in a browser while " +
                "this screen was open leaves it showing \"Nothing trusted yet\"",
        )
    }

    @Test
    fun `the forget confirmation survives a rotation`() {
        assertTrue(
            "var pendingForgetPackage by rememberSaveable" in screen,
            "the pending-forget flag is plain `remember`, so a rotation silently dismisses the " +
                "dialog — the odd one out among this app's confirmations",
        )
    }
}
