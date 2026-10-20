package com.localkeep.app.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Finding 6-1 (2026-09-07 sweep, verdicts/6-1.json): `AppNavGraph.HOME_FILTERED` ("vault/{type}")
 * was registered in the NavHost but nothing navigated to it any more — its former caller, the
 * drawer's per-type rows, was deleted when type filtering moved into the vault list's filter sheet
 * (2026-09-02). Four other files carried
 * live-looking special-case logic that existed only to compare against this unreachable
 * destination: `TopLevelNavDecision.kt`'s filled-route comparison, `AppShell.kt`'s drawer-highlight
 * substitution, its bottom-tab `isSelected` branch, and its `shellRoutes` membership.
 *
 * Source-scanning, following [ScreenFrameConsistencyTest]: the claim is "this identifier and this
 * route string are gone from production Kotlin", which the source answers exactly.
 */
class OrphanedFilteredVaultRouteTest {

    @Test
    fun theOrphanedRouteConstantIsGone() {
        // FAILS WITHOUT THE FIX: AppNavGraph.kt declared `const val HOME_FILTERED = "vault/{type}"`
        // and registered a `composable(HOME_FILTERED) { ... }` destination for it.
        val offenders = kotlinMainSources()
            .filter { "HOME_FILTERED" in it.readText() }
            .map { it.name }
        assertEquals(emptyList(), offenders, "HOME_FILTERED must not be declared or referenced: $offenders")
    }

    @Test
    fun theUnreachableRouteTemplateIsGone() {
        // FAILS WITHOUT THE FIX: the literal route pattern the constant held.
        val offenders = kotlinMainSources()
            .filter { "\"vault/{type}\"" in it.readText() }
            .map { it.name }
        assertEquals(emptyList(), offenders, "the unreachable \"vault/{type}\" pattern must not appear: $offenders")
    }

    @Test
    fun theBottomTabNoLongerSpecialCasesTheRemovedDestination() {
        // FAILS WITHOUT THE FIX: BottomDestination.isSelected() carried
        // `route == AppNavGraph.HOME -> current == AppNavGraph.HOME_FILTERED`, comparing against a
        // destination the NavHost no longer registers.
        val appShell = source("app/src/main/kotlin/com/localkeep/app/navigation/AppShell.kt")
        assertFalse(
            "current == AppNavGraph.HOME_FILTERED" in appShell,
            "BottomDestination.isSelected must not reference the removed HOME_FILTERED destination",
        )
    }

    private companion object {
        fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repo root not found above ${File("").absolutePath}")

        fun source(relative: String): String {
            val file = File(repoRoot(), relative)
            assert(file.isFile) { "expected source file at $relative" }
            return file.readText()
        }

        /** Every production Kotlin source under app/ and feature/, excluding build output. */
        fun kotlinMainSources(): List<File> = repoRoot().walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") && !it.name.endsWith("-out") }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path.replace(File.separatorChar, '/') }
            .toList()
    }
}
