package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Finding 3-3 (2026-09-07 sweep, verdicts/3-3.json): `remindExport` was seeded once into an
 * unkeyed `remember` and never re-derived, so an export completed via Settings -> Backup (a route
 * that never touches this flag) left the stale-export banner claiming the vault had not been
 * exported a minute after it was — and tapping "Back up" cleared the flag before any export ran,
 * silently suppressing a true reminder for the rest of the process if the user backed out.
 *
 * Source-scanning, following [ScreenFrameConsistencyTest] and [BiometricModalityCopyTest]: this is
 * a claim about what MainActivity's composable does on a route change and on a tap, and the app
 * module ships no Compose UI test dependency to answer that with a rendered screen instead.
 */
class StaleExportReminderRederivesTest {

    @Test
    fun remindExportIsReDerivedWhenTheBackStackReturnsToAShellRoute() {
        // FAILS WITHOUT THE FIX: `remindExport` used to be written only by `remember { ... }`
        // (computed once per process) and by the banner's own onExport/onDismiss lambdas. Without
        // a LaunchedEffect keyed on the current route that re-runs `shouldRemind`, an export made
        // from the Backup screen (reached via Settings, not the banner) never clears the banner —
        // production line MainActivity.kt's `LaunchedEffect(currentRoute) { ... }` block, if
        // removed or if its body stops reassigning `remindExport`, turns this test red.
        assertTrue(
            "LaunchedEffect(currentRoute)" in mainActivitySource,
            "MainActivity must re-derive the stale-export verdict on every route change, not " +
                "cache it for the process lifetime",
        )
        assertTrue(
            Regex(
                """LaunchedEffect\(currentRoute\)\s*\{\s*if\s*\(currentRoute in shellRoutes\)\s*\{\s*""" +
                    """remindExport\s*=\s*StaleExportReminder\.shouldRemind\(context\)""",
            ).containsMatchIn(mainActivitySource),
            "the route-change effect must re-read StaleExportReminder.shouldRemind(context), " +
                "gated to shellRoutes the same way the banner's own visibility is",
        )
    }

    @Test
    fun tappingBackUpDoesNotClearTheReminderBeforeAnExportHasHappened() {
        // FAILS WITHOUT THE FIX: onExport used to set `remindExport = false` immediately, before
        // navigating to Backup and before any export ran, so backing out unexported suppressed a
        // true reminder for the rest of the process. Production line: the `remindExport = false`
        // statement inside StaleExportBanner's `onExport = { ... }` lambda in MainActivity.kt.
        val onExport = Regex("""onExport\s*=\s*\{(.*?)\},\s*onDismiss""", RegexOption.DOT_MATCHES_ALL)
            .find(mainActivitySource)
            ?.groupValues
            ?.get(1)
            ?: error("StaleExportBanner's onExport lambda not found in MainActivity.kt")
        assertFalse(
            "remindExport = false" in onExport,
            "onExport must not clear remindExport — it only navigates, it does not export; " +
                "clearing here is what suppressed a true reminder after backing out unexported",
        )
        assertTrue(
            "navController.navigate(AppNavGraph.BACKUP)" in onExport,
            "onExport must still navigate to Backup",
        )
    }

    private companion object {
        val mainActivitySource: String by lazy {
            source("app/src/main/kotlin/com/localkeep/app/MainActivity.kt")
        }

        fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repo root not found above ${File("").absolutePath}")

        fun source(relative: String): String {
            val file = File(repoRoot(), relative)
            assertTrue(file.isFile, "expected source file at $relative")
            return file.readText()
        }
    }
}
