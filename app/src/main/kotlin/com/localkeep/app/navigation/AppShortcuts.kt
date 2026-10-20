package com.localkeep.app.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The two long-press launcher shortcuts (design spec §12.8: "long-press app-shortcuts (search /
 * new item)") and the seam that carries one of them into the vault list.
 *
 * The shortcuts themselves are static, declared in `res/xml/shortcuts.xml`, and both target
 * [com.localkeep.app.MainActivity] explicitly with one of the actions below. They are launched by
 * the home screen, which is outside this app, so **nothing about them may be treated as
 * authenticated**: a shortcut names a destination and nothing else. The vault is opened by the
 * unlock screen exactly as it always is, and the request simply waits here until it is — see
 * [shortcutRoutesToVaultList].
 *
 * A decoy session honours both shortcuts, and does so by having no opinion about them: the whole
 * path below asks only whether the vault is open, never whether it is the real one. Searching the
 * decoy vault and adding an item to it are things the decoy has to be able to do, since the point
 * of the decoy is to be an ordinary vault to whoever is watching.
 */
object AppShortcuts {

    /** Land on the vault list with the search field focused. */
    const val ACTION_SEARCH = "com.localkeep.action.SEARCH"

    /** Land on the vault list with the add-type sheet raised. */
    const val ACTION_NEW_ITEM = "com.localkeep.action.NEW_ITEM"

    /** Where a shortcut wants to land, once there is an unlocked vault to land in. */
    enum class Target { SEARCH, NEW_ITEM }

    /**
     * The shortcut a launch [Intent][android.content.Intent] action asks for, or null for an
     * ordinary launch.
     *
     * A plain function of the action string rather than a read of the Activity's intent, because
     * that is the half worth pinning: an ordinary `android.intent.action.MAIN` launch must not
     * raise a sheet, and neither must a stray action from another app — `MainActivity` is exported
     * (it is the launcher activity), so any app can start it with any action it likes.
     */
    fun targetOf(action: String?): Target? = when (action) {
        ACTION_SEARCH -> Target.SEARCH
        ACTION_NEW_ITEM -> Target.NEW_ITEM
        else -> null
    }

    private val _pending = MutableStateFlow<Target?>(null)

    /**
     * The shortcut that is still owed a landing, observed rather than taken once.
     *
     * A flow, unlike the `take()`-shaped [com.localkeep.feature.settings.SettingsFocus] this
     * otherwise resembles, because the request can arrive while the vault list is already on
     * screen: `MainActivity` is `singleTask`, so a shortcut tapped while the app is open is
     * delivered to `onNewIntent` with nothing about to compose for the first time.
     */
    val pending: StateFlow<Target?> = _pending.asStateFlow()

    fun request(target: Target) {
        _pending.value = target
    }

    fun clear() {
        _pending.value = null
    }
}

/**
 * Whether a pending shortcut should move the nav host to the vault list.
 *
 * Three things this deliberately does *not* do, each of which would be a defect:
 *
 * - **It never routes while the vault is locked.** The request outlives the unlock screen and is
 *   answered on the other side of it; it is not a way past it. `unlocked` is true for a decoy
 *   session too, which is the intent — see [AppShortcuts].
 * - **It never interrupts a focused task.** Only the three shell destinations and Settings are
 *   routed from. Settings is not a shell route (it is full-screen) but holds no unsaved work: every
 *   control writes as it is touched, so leaving it discards nothing. The item editor, the import
 *   wizard and the folder chooser hold unsaved work that navigating away would discard silently, and onboarding's Recovery Kit step runs with the vault *already open* —
 *   so a rule that only asked "is it unlocked?" would yank someone off the 24 words mid-ceremony.
 *   The request keeps waiting and is answered when they reach the list, which is where it belongs.
 * - **It does not re-navigate when the list is already showing.** [AppNavGraph.HOME] consumes the
 *   pending target where it composes; sending it to itself first would be a wasted transition.
 */
internal fun shortcutRoutesToVaultList(
    pending: AppShortcuts.Target?,
    unlocked: Boolean,
    currentRoute: String?,
): Boolean =
    pending != null &&
        unlocked &&
        currentRoute != AppNavGraph.HOME &&
        (currentRoute in shellRoutes || currentRoute == AppNavGraph.SETTINGS)
