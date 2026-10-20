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
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A callback that navigates or pops runs once per screen, however many taps reach it (O2-03,
 * 2026-10-02).
 *
 * Nothing in the app throttles a tap. A double tap on Generator's "Save as a new login" called
 * `navigate(itemNew(LOGIN))` twice and stacked two editors; only one could take the generated
 * secret, and after saving the top one the user landed in the second, blank. The same shape makes
 * a doubled `onSaved` pop one screen further than asked — or, from a single-entry stack, pop the
 * last entry and leave nothing on screen.
 *
 * [ifStillOn] is asserted against a real `NavHostController`, and the source check below pins the
 * three call sites that use it, since `AppNavGraph` itself cannot be composed without Hilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DoubleTapNavigatesOnceTest {

    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private val nav = NavHostController(activity).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        setLifecycleOwner(activity)
        setViewModelStore(activity.viewModelStore)
        graph = createGraph(startDestination = AppNavGraph.HOME) {
            composable(AppNavGraph.HOME) {}
            composable(AppNavGraph.GENERATOR) {}
            composable(EDITOR) {}
        }
    }

    private fun routes(): List<String?> =
        nav.currentBackStack.value.map { it.destination.route }.filter { it != null && it != nav.graph.route }

    @Test
    fun `a double tap on save-as-login opens one editor`() {
        nav.navigate(AppNavGraph.GENERATOR)
        val generator = requireNotNull(nav.currentBackStackEntry)

        // Both taps arrive from the generator's own composition, holding its entry.
        repeat(2) { nav.ifStillOn(generator) { nav.navigate(EDITOR) } }

        assertEquals(
            listOf(AppNavGraph.HOME, AppNavGraph.GENERATOR, EDITOR),
            routes(),
            "the second tap stacked a second editor on the first",
        )
    }

    @Test
    fun `a doubled save pops its own screen and nothing more`() {
        nav.navigate(EDITOR)
        val editor = requireNotNull(nav.currentBackStackEntry)

        repeat(2) { nav.ifStillOn(editor) { nav.popBackStack() } }

        assertEquals(
            listOf(AppNavGraph.HOME),
            routes(),
            "the second call popped the screen underneath the editor too",
        )
    }

    @Test
    fun `the last entry is never popped by a repeated callback`() {
        val home = requireNotNull(nav.currentBackStackEntry)
        nav.ifStillOn(home) { nav.navigate(EDITOR) }
        val editor = requireNotNull(nav.currentBackStackEntry)
        nav.ifStillOn(editor) { nav.popBackStack() }
        // A stale callback from the editor, after it is gone, must not pop HOME.
        nav.ifStillOn(editor) { nav.popBackStack() }

        assertEquals(listOf(AppNavGraph.HOME), routes())
    }

    @Test
    fun `the three call sites are guarded`() {
        val graph = File("src/main/kotlin/com/localkeep/app/navigation/AppNavGraph.kt").readText()
        val generator = graph.substringAfter("onSaveToVault = {", "").substringBefore("},", "")
        assertTrue(
            "navController.ifStillOn(entry)" in generator,
            "Generator's save-as-login navigates unguarded. Handler:\n$generator",
        )
        val itemEdit = graph.substringAfter("composable(ITEM_EDIT)", "").substringBefore("composable(", "")
        assertTrue(
            "onSaved = { navController.ifStillOn(entry) { navController.popBackStack() } }" in itemEdit,
            "ITEM_EDIT's onSaved pops unguarded. Destination:\n$itemEdit",
        )
        val folderNew = graph.substringAfter("onCreate = { name, parentId, onCreated ->", "").substringBefore("},", "")
        assertTrue(
            "navController.ifStillOn(entry)" in folderNew,
            "FOLDER_NEW's create runs unguarded. Handler:\n$folderNew",
        )
    }

    private companion object {
        const val EDITOR = "test-editor"
    }
}
