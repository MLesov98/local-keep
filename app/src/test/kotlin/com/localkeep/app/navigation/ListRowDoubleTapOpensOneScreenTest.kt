package com.localkeep.app.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A row or button that opens a screen opens it once, and an in-app back arrow pops one screen,
 * however many taps reach them.
 *
 * Device pass 2026-10-04 (foss release, API 35): two taps on a vault-list row ~150 ms apart stacked
 * two item details, so Back had to be pressed twice. The row's `onItemClick` was a bare
 * `navigate("item/$itemId")`, and so was every other opener in the graph — Health's rows, the
 * row menu's Edit, the detail's Edit, the add sheet, Settings' rows. Drawer rows were already safe:
 * `navigateTopLevel` refuses a route that is already current.
 *
 * The behaviour of [navigateFrom] and [popFrom] is asserted against a real `NavHostController`; the
 * wiring is asserted on the source, as [DoubleTapNavigatesOnceTest] does, because `AppNavGraph`
 * cannot be composed without Hilt and `:app` has no Hilt test runner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ListRowDoubleTapOpensOneScreenTest {

    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private val nav = NavHostController(activity).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        setLifecycleOwner(activity)
        setViewModelStore(activity.viewModelStore)
        graph = createGraph(startDestination = AppNavGraph.HOME) {
            composable(AppNavGraph.HOME) {}
            composable(DETAIL) {}
        }
    }

    private fun routes(): List<String?> =
        nav.currentBackStack.value.map { it.destination.route }.filter { it != null && it != nav.graph.route }

    @Test
    fun `a double tap on a row opens one detail, and one Back leaves it`() {
        val list = requireNotNull(nav.currentBackStackEntry)

        // Both taps arrive from the list's own composition, holding its entry.
        repeat(2) { nav.navigateFrom(list, "item/zulu") }
        assertEquals(listOf(AppNavGraph.HOME, DETAIL), routes(), "the second tap stacked a second detail")

        nav.popBackStack()
        assertEquals(listOf(AppNavGraph.HOME), routes())
    }

    @Test
    fun `a double tap on a back arrow pops one screen, and never the list`() {
        val list = requireNotNull(nav.currentBackStackEntry)
        nav.navigateFrom(list, "item/zulu")
        val detail = requireNotNull(nav.currentBackStackEntry)

        repeat(2) { nav.popFrom(detail) }
        assertEquals(listOf(AppNavGraph.HOME), routes(), "the second tap popped the list under the detail")
    }

    // --- wiring -------------------------------------------------------------------------------

    private val graph: String by lazy {
        File("src/main/kotlin/com/localkeep/app/navigation/AppNavGraph.kt")
            .also { if (!it.isFile) fail("cannot find ${it.absolutePath} — this test reads the source it guards") }
            .readText()
    }

    /** The destination block starting at [marker], up to the next `composable(`. */
    private fun destination(marker: String): String {
        val start = graph.indexOf(marker)
        if (start < 0) fail("no `$marker` in AppNavGraph")
        val end = graph.indexOf("composable(", start + marker.length).takeIf { it > 0 } ?: graph.length
        return graph.substring(start, end)
    }

    /** The `name = { … }` lambda inside [scope], brace-matched. */
    private fun handler(scope: String, name: String): String {
        val start = scope.indexOf("$name = {")
        if (start < 0) fail("no `$name = {` handler in:\n$scope")
        var depth = 0
        for (i in start until scope.length) {
            when (scope[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return scope.substring(start, i + 1)
            }
        }
        fail("unbalanced `$name` handler")
    }

    private fun assertGuarded(scope: String, vararg names: String) {
        for (name in names) {
            val h = handler(scope, name)
            assertTrue(
                "navigateFrom(entry" in h || "ifStillOn(entry)" in h,
                "`$name` opens a screen unguarded, so a double tap opens it twice:\n$h",
            )
        }
    }

    @Test
    fun `the vault list's openers are guarded`() {
        val vaultList = graph.substringAfter("private fun vaultList(", "").substringBefore("operator fun invoke(", "")
        assertTrue(vaultList.isNotEmpty(), "could not find the vaultList wiring")
        assertGuarded(
            vaultList,
            "onItemClick", "onItemSettingsClick", "onFileSettingsClick", "onFileItemClick",
            "onAddItem", "onAddFile", "onNewFolder", "onSetNewMasterPassword",
        )
    }

    @Test
    fun `Health's rows, the detail's buttons and Settings' rows are guarded`() {
        assertGuarded(destination("composable(HEALTH_DASHBOARD)"), "onItemClick")
        assertGuarded(destination("composable(ITEM)"), "onEdit", "onManageAttachments")
        assertGuarded(
            destination("composable(SETTINGS)"),
            "onHealthClick", "onImportClick", "onBackupClick", "onRecoveryKitClick", "onTrustedAppsClick",
            "onHiddenItemsClick",
        )
    }

    @Test
    fun `no back arrow pops unguarded`() {
        assertFalse(
            "navController::popBackStack" in graph,
            "a back arrow is wired straight to popBackStack, so a double tap pops two screens",
        )
    }

    private companion object {
        const val DETAIL = "item/{itemId}"
    }
}
