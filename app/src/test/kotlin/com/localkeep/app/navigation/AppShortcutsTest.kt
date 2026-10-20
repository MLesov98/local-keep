package com.localkeep.app.navigation

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two long-press launcher shortcuts (§12.8): what an incoming intent action means, and when a
 * pending one is allowed to move the nav host.
 *
 * Pinned as decisions rather than through a NavController, exactly as [TopLevelNavDecisionTest]
 * pins the tab-vs-leaf options: both halves are pure functions of the intent action, the lock state
 * and the current route, so a rendering test would need a device to answer what the source already
 * answers exactly.
 */
class AppShortcutsTest {

    @AfterTest
    fun clearPending() {
        // The holder is a process singleton, like every other cross-screen request object in this
        // app. One test leaving a target in it would be another test's phantom shortcut.
        AppShortcuts.clear()
    }

    @Test
    fun `each shortcut action names its own destination`() {
        assertEquals(AppShortcuts.Target.SEARCH, AppShortcuts.targetOf(AppShortcuts.ACTION_SEARCH))
        assertEquals(
            AppShortcuts.Target.NEW_ITEM,
            AppShortcuts.targetOf(AppShortcuts.ACTION_NEW_ITEM),
        )
    }

    /**
     * The control that must stay green: an ordinary launch is an ordinary launch. `MainActivity` is
     * exported — it is the launcher activity — so it is started with `android.intent.action.MAIN`
     * every time the user taps the icon, and with whatever action any other app on the device cares
     * to send. Anything but the two known actions has to mean "just open the app".
     */
    @Test
    fun `an ordinary launch asks for nothing`() {
        assertNull(AppShortcuts.targetOf("android.intent.action.MAIN"))
        assertNull(AppShortcuts.targetOf(null))
        assertNull(AppShortcuts.targetOf("android.intent.action.VIEW"))
        assertNull(AppShortcuts.targetOf("com.someone.else.action.SEARCH"))
    }

    /**
     * The one that matters. A shortcut is a request for a destination and never a way into the
     * vault: while the vault is locked the nav host stays where it is, which is the unlock screen.
     */
    @Test
    fun `a locked vault never routes a shortcut`() {
        assertFalse(
            shortcutRoutesToVaultList(
                pending = AppShortcuts.Target.SEARCH,
                unlocked = false,
                currentRoute = AppNavGraph.UNLOCK,
            ),
        )
        assertFalse(
            shortcutRoutesToVaultList(
                pending = AppShortcuts.Target.NEW_ITEM,
                unlocked = false,
                currentRoute = AppNavGraph.GENERATOR,
            ),
        )
    }

    @Test
    fun `a shortcut tapped from another tab lands on the vault list`() {
        assertTrue(
            shortcutRoutesToVaultList(
                pending = AppShortcuts.Target.SEARCH,
                unlocked = true,
                currentRoute = AppNavGraph.GENERATOR,
            ),
        )
        assertTrue(
            shortcutRoutesToVaultList(
                pending = AppShortcuts.Target.NEW_ITEM,
                unlocked = true,
                currentRoute = AppNavGraph.HEALTH_DASHBOARD,
            ),
        )
    }

    /**
     * A focused task keeps the screen. The item editor, the import wizard and the folder chooser
     * hold unsaved work that navigating away discards without a word, and onboarding runs with the
     * vault *already open* — so a rule that asked only "is it unlocked?" would take someone off the
     * Recovery Kit's 24 words mid-ceremony. The request is not lost: the vault list consumes it
     * when the user gets back there.
     */
    @Test
    fun `a shortcut waits rather than interrupting a focused task`() {
        listOf(
            AppNavGraph.ONBOARDING,
            AppNavGraph.RECOVERY_KIT,
            AppNavGraph.IMPORT,
            "item/{itemId}/edit",
            "folder/new?parent={parent}&fromSheet={fromSheet}",
        ).forEach { route ->
            assertFalse(
                shortcutRoutesToVaultList(
                    pending = AppShortcuts.Target.NEW_ITEM,
                    unlocked = true,
                    currentRoute = route,
                ),
                "a pending shortcut must not navigate away from $route",
            )
        }
    }

    /**
     * Settings holds no unsaved work (every control writes as it is touched), so "Search the vault"
     * from the launcher, tapped while Settings is open, lands on the list instead of being ignored.
     */
    @Test
    fun `a shortcut tapped from settings lands on the vault list`() {
        listOf(AppShortcuts.Target.SEARCH, AppShortcuts.Target.NEW_ITEM).forEach { target ->
            assertTrue(
                shortcutRoutesToVaultList(
                    pending = target,
                    unlocked = true,
                    currentRoute = AppNavGraph.SETTINGS,
                ),
            )
        }
    }

    @Test
    fun `no shortcut and no navigation`() {
        assertFalse(
            shortcutRoutesToVaultList(
                pending = null,
                unlocked = true,
                currentRoute = AppNavGraph.GENERATOR,
            ),
        )
    }

    /** Already there: the vault list answers the request where it composes, so this is a no-op. */
    @Test
    fun `a shortcut does not re-navigate to the list it is already on`() {
        assertFalse(
            shortcutRoutesToVaultList(
                pending = AppShortcuts.Target.SEARCH,
                unlocked = true,
                currentRoute = AppNavGraph.HOME,
            ),
        )
    }

    @Test
    fun `the pending target is published and cleared`() {
        assertNull(AppShortcuts.pending.value)
        AppShortcuts.request(AppShortcuts.Target.NEW_ITEM)
        assertEquals(AppShortcuts.Target.NEW_ITEM, AppShortcuts.pending.value)
        AppShortcuts.clear()
        assertNull(AppShortcuts.pending.value)
    }

    /**
     * Each flavour's `res/xml/shortcuts.xml` names its own installed package, written out, and the
     * trampoline as the class.
     *
     * Red on the device before this (2026-10-04): one file in the main source set took the package
     * from `@string/lk_application_id`, the shortcut service stored the literal `@2131756249` as
     * the package, and every tap failed with "Shortcut could not be started". The old version of
     * this test pinned exactly that reference, which is why it never caught it. The failure is
     * silent in every build output, so the values are compared with app/build.gradle.kts here.
     */
    @Test
    fun `each flavour's shortcuts name its real application id and the trampoline`() {
        val buildFile = source("app/build.gradle.kts")
        val applicationId = Regex("""applicationId = "([^"]+)"""")
            .find(buildFile)?.groupValues?.get(1)
            ?: error("no applicationId in app/build.gradle.kts")
        val fossSuffix = Regex("""applicationIdSuffix = "([^"]+)"""")
            .find(buildFile)?.groupValues?.get(1)
            ?: error("no applicationIdSuffix in app/build.gradle.kts")

        for ((flavour, expected) in listOf("play" to applicationId, "foss" to applicationId + fossSuffix)) {
            val xml = source("app/src/$flavour/res/xml/shortcuts.xml")
            val packages = Regex("""android:targetPackage="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
            assertEquals(listOf(expected, expected), packages, "$flavour: both shortcut intents must name $expected")
            assertEquals(
                2,
                Regex("""android:targetClass="com\.localkeep\.app\.ShortcutTrampolineActivity"""")
                    .findAll(xml).count(),
                "$flavour: both shortcut intents must name ShortcutTrampolineActivity, not MainActivity",
            )
        }
        // A copy in main would be shadowed by both flavours today and silently win the day either
        // flavour's file goes missing.
        assertFalse(
            File(repoRoot(), "app/src/main/res/xml/shortcuts.xml").exists(),
            "shortcuts.xml must live in the flavour source sets only",
        )
    }

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repo root not found above ${File("").absolutePath}")

    private fun source(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue(file.isFile, "expected a file at $relative")
        return file.readText()
    }
}
