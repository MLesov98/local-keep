package com.localkeep.app.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The command drawer has a visible way in from every bottom-bar tab, not only from the vault list.
 *
 * It did not, until commit `0c1c6c45`: the drawer existed on Generator and Password health and the
 * only way to reach it there was a left-edge swipe, which is not an affordance — it is a secret,
 * and on those two screens the edge belongs to the OS back gesture anyway (found 2026-08-29).
 * Nothing pinned the fix, so this is the guard the fix shipped without.
 *
 * Source-scanning, following [ScreenFrameConsistencyTest][com.localkeep.app.ScreenFrameConsistencyTest]:
 * the claim is about which argument three call sites pass and which glyph three headers draw, and
 * a rendering test would need a device to answer what the source answers exactly. The seam it pins
 * is the shell's: `AppShell` publishes the opener, `AppNavGraph` hands it to each tab destination,
 * and each tab screen draws a handle bound to it. Break any one link and the drawer goes quiet
 * again on that tab.
 */
class DrawerReachableFromEveryTabTest {

    private val tabScreens = mapOf(
        AppNavGraph.HOME to
            "feature/vaultlist/src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt",
        AppNavGraph.GENERATOR to
            "feature/generator/src/main/kotlin/com/localkeep/feature/generator/GeneratorScreen.kt",
        AppNavGraph.HEALTH_DASHBOARD to
            "feature/health/src/main/kotlin/com/localkeep/feature/health/HealthDashboardScreen.kt",
    )

    @Test
    fun `the shell publishes one drawer opener`() {
        assertTrue(
            "LocalDrawerOpener provides" in source(APP_SHELL),
            "AppShell must publish LocalDrawerOpener, or no screen can raise the drawer",
        )
    }

    /**
     * Held at exactly the number of tabs rather than "at least one", because the defect this pins
     * is a tab that was *left out*: two of the three call sites existed and the third did not.
     */
    @Test
    fun `every bottom-bar destination is handed the opener`() {
        assertEquals(
            tabScreens.size,
            Regex("""onOpenDrawer = LocalDrawerOpener\.current""")
                .findAll(source(APP_NAV_GRAPH)).count(),
            "each of ${tabScreens.keys} must receive onOpenDrawer = LocalDrawerOpener.current",
        )
    }

    @Test
    fun `every tab screen draws a menu handle bound to it`() {
        tabScreens.forEach { (route, path) ->
            val screen = source(path)
            assertTrue(
                "LkIcons.Menu" in screen,
                "$route must draw the Menu glyph — LkIcons owns what it means",
            )
            assertTrue(
                """lkTag("nav-open-drawer")""" in screen,
                "$route's handle must carry the tag the device sweeps drive it by",
            )
            assertTrue(
                "onClick = onOpenDrawer" in screen,
                "$route's handle must open the drawer rather than merely look like it does",
            )
        }
    }

    /**
     * Keeps the map above honest. A fourth tab would otherwise be added to the bar with no handle
     * and nothing here would notice — the same way the third one was.
     */
    @Test
    fun `the bar still has exactly the tabs this test knows about`() {
        val shell = source(APP_SHELL)
        assertEquals(
            tabScreens.size,
            Regex("""BottomDestination\(""").findAll(shell).count() - 1,
            "bottomDestinations changed: add the new tab's screen to tabScreens",
        )
        assertEquals(
            tabScreens.size,
            shellRoutes.size,
            "shellRoutes changed: add the new route's screen to tabScreens",
        )
    }

    private fun source(relative: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repo root not found above ${File("").absolutePath}")
        val file = File(root, relative)
        assertTrue(file.isFile, "expected a file at $relative")
        return file.readText()
    }

    private companion object {
        const val APP_SHELL = "app/src/main/kotlin/com/localkeep/app/navigation/AppShell.kt"
        const val APP_NAV_GRAPH = "app/src/main/kotlin/com/localkeep/app/navigation/AppNavGraph.kt"
    }
}
