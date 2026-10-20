package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Four layout invariants found broken by a visual sweep and pinned against the source: three by
 * the sweep of 2026-09-01, the fourth by the UI gate of 2026-09-09.
 *
 * Source-scanning rather than Compose tests, following [BiometricModalityCopyTest] and
 * [TierGClaimsTest]: each of these is a claim about *which* argument a call site passes, and a
 * rendering test would need a device to answer a question the source already answers exactly.
 *
 * Every test here is red before its fix — that is the point of the file, and each says how.
 */
class ScreenFrameConsistencyTest {

    @Test
    fun theUnlockScreensActionColumnSharesTheContentColumnsMargin() {
        // Red before the fix: UnlockScreen called LkActionScaffold with no horizontalPadding, so
        // the pinned actions took the 20dp LkFrame.screenHorizontal default while the content
        // column used 30dp. Unlock and "Use Recovery Kit" overhung the password field they submit
        // by 4dp on each side, on the screen the app is met on most often. Measured from
        // unlock-screen.xml: content at x=79, actions at x=53.
        val unlock = source("feature/unlock/src/main/kotlin/com/localkeep/feature/unlock/UnlockScreen.kt")
        assertTrue(
            "horizontalPadding = LkFrame.heroHorizontal" in unlock,
            "UnlockScreen must pass horizontalPadding to LkActionScaffold explicitly, or its " +
                "pinned actions silently fall back to the ordinary-screen margin",
        )
        assertTrue(
            ".padding(horizontal = LkFrame.heroHorizontal)" in unlock,
            "UnlockScreen's content column must use the same token as its action column",
        )
    }

    @Test
    fun theHeroMarginHasOneNameAndNoCallSiteRetypesIt() {
        // Red before the fix: 30.dp appeared as a bare literal in four places (onboarding twice,
        // unlock, and the full-size empty state) with no token, which is how the unlock screen's
        // two halves ended up on different values without anyone noticing.
        val offenders = kotlinSources()
            .filter { "horizontal = 30.dp" in it.readText() }
            .map { it.name }
        assertEquals(
            emptyList(), offenders,
            "the hero screen margin is LkFrame.heroHorizontal; these files retype it: $offenders",
        )
    }

    @Test
    fun everyScreenHeaderPutsItsBackChevronOnTheSameSide() {
        // Red before the fix: FolderScreen, TrustedAppsScreen and HiddenItemsScreen put the back
        // chevron in LkScreenHeader's `leading` slot while eight other screens used `trailing`, so
        // Back physically swapped sides depending on which screen you were leaving.
        //
        // Scoped to LkIcons.ChevronLeft inside a `leading = {` block deliberately. GeneratorScreen
        // and HealthDashboardScreen carry a *Lock* button in their trailing slot and have no back
        // chevron at all (they are bottom-nav tabs with no back stack), and ItemDetailScreen builds
        // its own header rather than using LkScreenHeader. A naive "standardise the slot" sweep
        // would have moved a Lock button; the sweep's own auditor made exactly that mistake and
        // counted 12 sites where there are 11.
        val offenders = kotlinSources()
            .filter { file ->
                val text = file.readText()
                LEADING_CHEVRON.containsMatchIn(text) && "LkScreenHeader(" in text
            }
            .map { it.name }
        assertEquals(
            emptyList(), offenders,
            "LkScreenHeader back chevrons belong in the trailing slot; these use leading: $offenders",
        )
    }

    @Test
    fun everyBottomNavTabRootOffersAVisibleWayIntoTheDrawer() {
        // Red before the fix: only VaultListScreen carried the menu button. Generator and Health
        // are the other two bottom-nav roots and rendered no drawer affordance at all, so from two
        // of the app's three home screens the command drawer was reachable only by an edge swipe -
        // which is a gesture, not an affordance, and nothing on screen advertised it.
        //
        // Deliberately asserts the `leading` slot: these three screens carry a Lock button in
        // `trailing` (see everyScreenHeaderPutsItsBackChevronOnTheSameSide), and a test that only
        // looked for LkIcons.Menu anywhere in the file would pass on a menu button parked opposite
        // the one it has to line up with.
        val roots = listOf(
            "feature/vaultlist/src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt",
            "feature/generator/src/main/kotlin/com/localkeep/feature/generator/GeneratorScreen.kt",
            "feature/health/src/main/kotlin/com/localkeep/feature/health/HealthDashboardScreen.kt",
        )
        val offenders = roots.filterNot { LEADING_MENU.containsMatchIn(source(it)) }
        assertEquals(
            emptyList(), offenders,
            "every bottom-nav tab root needs the drawer button in LkScreenHeader's leading slot; " +
                "these have none: $offenders",
        )
    }

    private companion object {
        /** `leading = {` followed, within the same argument block, by a ChevronLeft icon. */
        val LEADING_CHEVRON = Regex("""leading\s*=\s*\{[^}]*?LkIcons\.ChevronLeft""", RegexOption.DOT_MATCHES_ALL)

        /** `leading = {` followed, within the same argument block, by the drawer's Menu icon. */
        val LEADING_MENU = Regex("""leading\s*=\s*\{[^}]*?LkIcons\.Menu""", RegexOption.DOT_MATCHES_ALL)

        fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repo root not found above ${File("").absolutePath}")

        fun source(relative: String): String {
            val file = File(repoRoot(), relative)
            assertTrue(file.isFile, "expected source file at $relative")
            return file.readText()
        }

        /** Every production Kotlin source in the app, excluding build output and test sources. */
        fun kotlinSources(): List<File> = repoRoot().walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") && !it.name.endsWith("-out") }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path.replace(File.separatorChar, '/') }
            .toList()
    }
}
