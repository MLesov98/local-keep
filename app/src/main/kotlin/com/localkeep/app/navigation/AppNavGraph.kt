package com.localkeep.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.localkeep.core.common.PendingReveal
import com.localkeep.core.common.PendingSecret
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.model.MediaViewerFocus
import com.localkeep.core.ui.theme.LkScreenRise
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.LocalNavAnimatedVisibilityScope
import com.localkeep.core.ui.theme.LocalSharedTransitionScope
import com.localkeep.core.ui.theme.lkScreenEnter
import com.localkeep.core.ui.theme.lkScreenExit
import com.localkeep.core.ui.theme.lkScreenPopEnter
import com.localkeep.core.ui.theme.lkScreenPopExit
import com.localkeep.core.ui.theme.lkVaultOpenEnter
import com.localkeep.core.ui.theme.lkVaultOpenExit
import com.localkeep.core.ui.theme.lkVaultSealEnter
import com.localkeep.core.ui.theme.lkVaultSealExit
import com.localkeep.feature.backup.BackupFocus
import com.localkeep.feature.backup.BackupSettingsScreen
import com.localkeep.feature.files.AttachmentPickerScreen
import com.localkeep.feature.files.FileItemEditScreen
import com.localkeep.feature.files.FileItemEditViewModel
import com.localkeep.feature.files.FileItemViewerScreen
import com.localkeep.feature.generator.GeneratorScreen
import com.localkeep.feature.health.HealthDashboardScreen
import com.localkeep.feature.importwizard.ImportWizardScreen
import com.localkeep.feature.itemedit.ItemDetailScreen
import com.localkeep.feature.itemedit.ItemEditScreen
import com.localkeep.feature.itemedit.ItemEditUiState.ItemType
import com.localkeep.feature.itemedit.ItemEditViewModel
import com.localkeep.feature.onboarding.OnboardingScreen
import com.localkeep.feature.onboarding.OnboardingUiState
import com.localkeep.feature.onboarding.RecoveryKitScreen
import com.localkeep.feature.settings.HiddenItemsScreen
import com.localkeep.feature.settings.SettingsFocus
import com.localkeep.feature.settings.SettingsScreen
import com.localkeep.feature.settings.TrustedAppsScreen
import com.localkeep.feature.unlock.UnlockScreen
import com.localkeep.feature.vaultlist.FolderFocus
import com.localkeep.feature.vaultlist.FolderScreen
import com.localkeep.feature.vaultlist.ItemTypeFilter
import com.localkeep.feature.vaultlist.NewFolderScreen
import com.localkeep.feature.vaultlist.NewItemType
import com.localkeep.feature.vaultlist.TrashScreen
import com.localkeep.feature.vaultlist.VaultListScreen
import com.localkeep.feature.vaultlist.VaultListViewModel
import com.localkeep.app.TrashUndoBus
import java.util.UUID

/**
 * Runs [action] only while [entry] is still the top of the back stack.
 *
 * 2026-10-02 (O2-03): nothing throttles a tap, so a double tap on a control that navigates or pops
 * ran its callback twice. Generator's "Save as a new login" stacked two editors, and a doubled pop
 * after a save went one screen further than asked, or emptied the stack. The first call moves the
 * back stack synchronously, so the second finds [entry] no longer on top and does nothing.
 */
internal inline fun NavHostController.ifStillOn(entry: NavBackStackEntry, action: () -> Unit) {
    if (currentBackStackEntry?.id == entry.id) action()
}

/**
 * Opens [route] from the screen [entry] belongs to, once — [ifStillOn] around a plain navigate.
 *
 * 2026-10-04: a double tap on a vault-list row opened two item details, and Back had to be pressed
 * twice. Every row and button that opens a screen is the same shape, so they all come through here.
 */
internal fun NavHostController.navigateFrom(entry: NavBackStackEntry, route: String) {
    ifStillOn(entry) { navigate(route) }
}

/**
 * Leaves the screen [entry] belongs to, once. A doubled tap on an in-app back arrow popped the
 * screen underneath as well, and from a single-entry stack popped the last one.
 */
internal fun NavHostController.popFrom(entry: NavBackStackEntry) {
    ifStillOn(entry) { popBackStack() }
}

object AppNavGraph {
    const val ONBOARDING = "onboarding"
    const val UNLOCK = "unlock"
    const val HOME = "vault"
    const val TRASH = "trash"
    const val SETTINGS = "settings"
    const val GENERATOR = "generator"
    const val HEALTH_DASHBOARD = "health_dashboard"  // NEW (13)
    const val BACKUP = "backup"                      // NEW (15)
    const val IMPORT = "import"                      // NEW (B4)
    const val RECOVERY_KIT = "recovery_kit"
    const val TRUSTED_APPS = "trusted_apps"

    /**
     * Authenticated recovery for anything concealed. Always registered, whether or not the vault
     * holds a hidden entry: a route that existed only while something was hidden would say so, and
     * would be missing for the user who has forgotten the reveal phrase. The destination composes no
     * entry content until its own biometric/vault-password gate passes.
     */
    const val HIDDEN_ITEMS = "hidden-items"

    /**
     * Folder management, and the only route to it that does not run through the item editor.
     *
     * Creating, renaming and deleting a folder lived inside the editor's folder picker, so tidying
     * folders meant opening an item you had no intention of changing.
     */
    const val FOLDERS = "folders"

    /**
     * Making a folder, on the comp's chooser screen.
     *
     * A destination of its own rather than a branch inside the vault list, so the shell drops
     * the tab bar for it — `Item Flows.dc.html` draws this screen with no bar, and the item
     * editor's route into the same screen already had none. `parent` seeds where the new folder
     * lands and is the folder the list was browsing; the screen itself lets the user move it.
     *
     * `fromSheet` records which door opened it: the add sheet's "New folder" row, or the folder
     * strip's own "+". Cancelling a chooser the sheet opened must land back *on the sheet* — a
     * plain pop landed on the bare list, so backing out of "New folder" read as a jump home.
     */
    const val FOLDER_NEW = "folder/new?parent={parent}&fromSheet={fromSheet}"

    /** The result key the vault list watches to re-raise the add sheet after such a cancel. */
    const val REOPEN_ADD_SHEET = "reopenAddSheet"

    /**
     * The same mechanism for the "Search" launcher shortcut: put the caret in the vault list's
     * search field once it is on screen.
     *
     * Held on the destination's `SavedStateHandle` rather than passed as a navigation argument, and
     * for the ordinary reason plus one: a flag in the route survives in the back stack and would
     * re-focus the field every time the user came back to the list from an item.
     */
    const val FOCUS_SEARCH = "focusSearch"
    /** One-shot localized viewer failure returned to the list that launched it. */
    const val VIEWER_ERROR = "viewerError"

    fun folderNew(parent: java.util.UUID?, fromSheet: Boolean = false): String = buildString {
        append("folder/new?fromSheet=")
        append(fromSheet)
        if (parent != null) append("&parent=").append(parent)
    }

    /**
     * Back out of a screen the add sheet opened, and put the sheet back.
     *
     * The folder chooser had this behaviour alone; every other row of the sheet popped to the bare
     * list, so backing out of "Location" — or Login, or Note — read as being thrown home from a
     * flow the user had barely entered (reported from a phone 2026-08-21: *"Clicked on Location,
     * instantly back, I'm into the main menu instead of vault… isn't it clear that it should work
     * like that across all items in the +"*). The sheet is one chooser with ten answers, so backing
     * out of any answer has to return the same place: the question.
     *
     * Only the *cancel* path calls this. After a save the list itself, with the new row on it, is
     * the confirmation — re-raising the sheet would cover the thing the user just made.
     */
    private fun NavHostController.cancelBackToSheet(fromSheet: Boolean) {
        if (fromSheet) previousBackStackEntry?.savedStateHandle?.set(REOPEN_ADD_SHEET, true)
        popBackStack()
    }

    /**
     * `type` is the item type picked in the add sheet (§4.4); absent means "let the form ask".
     * `fromSheet` is the same door-marker [FOLDER_NEW] carries — see [cancelBackToSheet].
     */
    private const val ITEM_NEW = "item/new?type={type}&fromSheet={fromSheet}"
    /** Read-only detail (§5.1). Editing is a separate, deliberate destination. */
    private const val ITEM = "item/{itemId}"
    private const val ITEM_EDIT = "item/{itemId}/edit"
    private const val FILE_NEW = "file/new?fromSheet={fromSheet}"   // NEW (11c)
    private const val FILE_ITEM = "file/{itemId}"            // NEW (11c)
    private const val FILE_SETTINGS = "file/{itemId}/settings"
    private const val ATTACHMENTS = "attachments/{itemId}"   // NEW (11c)

    /**
     * Whether a lock that interrupted [route] dropped unsaved editor state that the next unlock must
     * announce. [route] is a NavGraph route *template* (e.g. `"item/{itemId}"`), the value
     * `NavController.currentDestination?.route` returns — not a resolved path.
     *
     * The read-only [ITEM] detail route holds nothing unsaved, so it is excluded: a broad
     * `startsWith("item/")` used to match it too and post the "unsaved changes were discarded" notice
     * after the user had merely *viewed* an item. Only the editor-mounting item routes qualify —
     * [ITEM_NEW] (`item/new…`), [ITEM_EDIT] (`item/{itemId}/edit`), [FILE_NEW] and
     * [FILE_SETTINGS]. The standalone [FILE_ITEM] viewer is read-only and must not report an
     * unsaved edit when a lock interrupts it.
     */
    internal fun droppedEditRoute(route: String): Boolean =
        route.startsWith("item/new") || route.endsWith("/edit") ||
            route.startsWith("file/new") || route == FILE_SETTINGS ||
            route.startsWith("attachments/") || route == IMPORT ||
            // A half-typed folder name is an unsaved edit like any other. `NewFolderScreen` holds
            // it in `rememberSaveable`, so an auto-lock mid-word discarded it exactly as silently
            // as a dropped item edit used to - and this notice exists precisely so that a lock
            // landing under the user's hands is not indistinguishable from the app losing their
            // typing.
            route.startsWith("folder/new")

    private fun itemNew(type: NewItemType, fromSheet: Boolean = false): String =
        "item/new?type=${type.name}&fromSheet=$fromSheet"

    private fun fileNew(fromSheet: Boolean = false): String = "file/new?fromSheet=$fromSheet"

    /**
     * Maps the add sheet's choice onto the editor's own type enum. Returns null for anything the
     * shared form can't create (Files have their own editor) or for an unrecognised value, in
     * which case the form simply opens on its default type instead of failing.
     */
    private fun String.toEditorType(): ItemType? = when (this) {
        // PASSWORD is not a sixth item type — it is the Login form entered by a different door,
        // with the password already generated. See [startsGenerated] for the other half.
        NewItemType.PASSWORD.name -> ItemType.LOGIN
        NewItemType.LOGIN.name -> ItemType.LOGIN
        NewItemType.TOTP.name -> ItemType.TOTP
        NewItemType.NOTE.name -> ItemType.NOTE
        NewItemType.CARD.name -> ItemType.CARD
        NewItemType.IDENTITY.name -> ItemType.IDENTITY
        NewItemType.COORDINATE.name -> ItemType.COORDINATE
        NewItemType.PHRASE.name -> ItemType.PHRASE
        // Files have their own editor (FILE_NEW); a FILE that reaches this route is a caller bug,
        // and opening the shared form on its default type would silently create a Login instead —
        // exactly the 2026-07-29 defect. Null keeps the form's "ask the user" behaviour.
        NewItemType.FILE.name -> null
        else -> null
    }

    /**
     * Whether this add-sheet choice means "and fill the password in for me".
     *
     * Kept as its own read of the same argument rather than folded into [toEditorType], because the
     * two answers are genuinely independent: the type is LOGIN either way, and only the door the
     * user came through decides whether a password is waiting when the form opens.
     */
    private fun String.startsGenerated(): Boolean = this == NewItemType.PASSWORD.name

    /**
     * Shared by the unfiltered and per-type vault destinations so both keep identical behaviour —
     * the filter narrows the list and changes nothing else.
     */
    @Composable
    private fun vaultList(
        navController: NavHostController,
        typeFilter: ItemTypeFilter?,
        entry: NavBackStackEntry,
    ) {
        // The folder chooser posts this into the list's own entry when a sheet-opened chooser is
        // cancelled; collecting it as state is what lets the sheet come back after the pop.
        val reopenAddSheet by entry.savedStateHandle
            .getStateFlow(REOPEN_ADD_SHEET, false)
            .collectAsStateWithLifecycle()
        val focusSearch by entry.savedStateHandle
            .getStateFlow(FOCUS_SEARCH, false)
            .collectAsStateWithLifecycle()
        // Where a long-press launcher shortcut is finally answered. `MainActivity` only gets the
        // nav host here (see shortcutRoutesToVaultList); both destinations a shortcut can ask for —
        // the search field and the add-type sheet — belong to this screen, so the request is
        // converted into the two one-shot flags the screen already understands rather than into a
        // third mechanism.
        //
        // Consumed here rather than at the Activity because "the vault list is composed" is the
        // condition that matters: a shortcut tapped against a locked vault waits through the unlock
        // screen, and one tapped mid-edit waits until the user is back on the list.
        val pendingShortcut by AppShortcuts.pending.collectAsStateWithLifecycle()
        LaunchedEffect(pendingShortcut) {
            when (pendingShortcut) {
                AppShortcuts.Target.SEARCH -> entry.savedStateHandle[FOCUS_SEARCH] = true
                AppShortcuts.Target.NEW_ITEM -> entry.savedStateHandle[REOPEN_ADD_SHEET] = true
                null -> return@LaunchedEffect
            }
            AppShortcuts.clear()
        }
        val viewerError by entry.savedStateHandle
            .getStateFlow<Int?>(VIEWER_ERROR, null)
            .collectAsStateWithLifecycle()
        LaunchedEffect(viewerError) {
            viewerError?.let {
                LkNotices.postRefusal(it)
                entry.savedStateHandle[VIEWER_ERROR] = null
            }
        }
        VaultListScreen(
            viewModel = hiltViewModel(),
            typeFilter = typeFilter,
            // Read here rather than in the feature module: :feature:vaultlist can't depend on
            // :app, so the shell's opener is resolved at the wiring layer and passed down plain.
            onOpenDrawer = LocalDrawerOpener.current,
            // Lands on the row, not the top of a twenty-row list — the same SettingsFocus route the
            // drawer's "Duress / decoy" link uses, and for the same reason. Carried in memory rather
            // than as a navigation argument: a route string is serialised into saved instance state
            // and written to disk, and "this session was opened with the Recovery Kit" is not a fact
            // to leave on the device.
            onSetNewMasterPassword = {
                navController.ifStillOn(entry) {
                    SettingsFocus.request(com.localkeep.feature.settings.R.string.settings_password_title)
                    navController.navigate(SETTINGS)
                }
            },
            // Every opener below is guarded: a double tap on a row stacked two details (see
            // [navigateFrom]).
            onItemClick = { itemId -> navController.navigateFrom(entry, "item/$itemId") },
            onItemSettingsClick = { itemId -> navController.navigateFrom(entry, "item/$itemId/edit") },
            onFileSettingsClick = { itemId -> navController.navigateFrom(entry, "file/$itemId/settings") },
            // The focus request goes inside the guard with the navigate: a second tap must not
            // re-aim a viewer the first one has already opened.
            onFileItemClick = { itemId, focus ->
                navController.ifStillOn(entry) {
                    runCatching { java.util.UUID.fromString(itemId) }
                        .getOrNull()
                        ?.let { MediaViewerFocus.request(it, focus) }
                    navController.navigate("file/$itemId")
                }
            },
            onAddItem = { type, fromSheet -> navController.navigateFrom(entry, itemNew(type, fromSheet)) },
            onAddFile = { fromSheet -> navController.navigateFrom(entry, fileNew(fromSheet)) },
            onNewFolder = { parent, fromSheet -> navController.navigateFrom(entry, folderNew(parent, fromSheet)) },
            reopenAddSheet = reopenAddSheet,
            onReopenAddSheetHandled = { entry.savedStateHandle[REOPEN_ADD_SHEET] = false },
            focusSearch = focusSearch,
            onSearchFocusHandled = { entry.savedStateHandle[FOCUS_SEARCH] = false },
        )
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    operator fun invoke(
        navController: NavHostController,
        startDestination: String,
    ) {
        // Read here, not inside the transition lambdas: those are plain lambdas, not composables,
        // so the preference cannot be looked up from within them. The rise is resolved here for the
        // same reason — the slide lambdas are handed a size in pixels and there is no LocalDensity
        // reachable inside one.
        val reduceMotion = LocalLkReduceMotion.current
        val risePx = with(LocalDensity.current) { LkScreenRise.roundToPx() }

        // Locking and unlocking get their own motion, decided here rather than on the UNLOCK
        // destination. Navigation resolves a transition from two places — the entering destination
        // supplies enterTransition, the *exiting* one supplies exitTransition — so overriding only
        // UNLOCK would animate half of it and leave the vault sliding sideways out the other. These
        // lambdas can see both ends, so one place decides the whole crossing.
        // The decision itself is [vaultCrossing], which is a pure function of the two routes and is
        // unit-tested per route there — including panic lock, which leaves from Settings.
        val crossing: AnimatedContentTransitionScope<NavBackStackEntry>.() -> VaultCrossing =
            { vaultCrossing(initialState.destination.route, targetState.destination.route) }

        // Wraps every destination so a shared element (the vault row's tile → the item detail
        // header) can morph across the crossing. The scope is published via a CompositionLocal
        // rather than threaded through screen signatures — see LkSharedTransition. Screens that are
        // not part of a shared-element pair are unaffected; the enter/exit transitions above still
        // own the whole-screen motion, and this only adds the one element that continues across it.
        SharedTransitionLayout {
        val sharedScope = this
        CompositionLocalProvider(LocalSharedTransitionScope provides sharedScope) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            enterTransition = {
                when (crossing()) {
                    VaultCrossing.SEAL -> lkVaultSealEnter(reduceMotion)
                    VaultCrossing.OPEN -> lkVaultOpenEnter(reduceMotion)
                    VaultCrossing.ORDINARY -> lkScreenEnter(reduceMotion, risePx)
                }
            },
            exitTransition = {
                when (crossing()) {
                    VaultCrossing.SEAL -> lkVaultSealExit(reduceMotion)
                    VaultCrossing.OPEN -> lkVaultOpenExit(reduceMotion)
                    VaultCrossing.ORDINARY -> lkScreenExit(reduceMotion, risePx)
                }
            },
            // Both crossings are forward navigations with popUpTo rather than pops, so these two are
            // belt and braces — but a lock reached by any route must still look like a lock.
            popEnterTransition = {
                when (crossing()) {
                    VaultCrossing.SEAL -> lkVaultSealEnter(reduceMotion)
                    VaultCrossing.OPEN -> lkVaultOpenEnter(reduceMotion)
                    VaultCrossing.ORDINARY -> lkScreenPopEnter(reduceMotion, risePx)
                }
            },
            popExitTransition = {
                when (crossing()) {
                    VaultCrossing.SEAL -> lkVaultSealExit(reduceMotion)
                    VaultCrossing.OPEN -> lkVaultOpenExit(reduceMotion)
                    VaultCrossing.ORDINARY -> lkScreenPopExit(reduceMotion, risePx)
                }
            },
        ) {
            composable(ONBOARDING) {
                OnboardingScreen(
                    viewModel = hiltViewModel(),
                    // Onboarding unlocks the vault with the password the user just chose, so it
                    // normally hands straight to the vault list (§3.2 step 6) rather than asking
                    // for that same password again. NEEDS_UNLOCK is the fallback if that unlock
                    // did not take — the vault exists either way, so never dead-end here.
                    onFinished = { finish ->
                        val target = when (finish) {
                            OnboardingUiState.Finish.OPEN_VAULT -> HOME
                            OnboardingUiState.Finish.OPEN_IMPORT -> IMPORT
                            OnboardingUiState.Finish.NEEDS_UNLOCK -> UNLOCK
                        }
                        // The import wizard opens over onboarding, not instead of it, so Back
                        // from the wizard lands on the Done step again with its autofill and
                        // passkey shortcuts. Every other finish leaves onboarding for good.
                        if (onboardingLeavesStackOnFinish(finish)) {
                            navController.navigate(target) {
                                popUpTo(ONBOARDING) { inclusive = true }
                            }
                        } else {
                            navController.navigate(IMPORT)
                        }
                    },
                )
            }
            composable(UNLOCK) {
                UnlockScreen(
                    viewModel = hiltViewModel(),
                    onUnlocked = {
                        navController.navigate(HOME) {
                            popUpTo(UNLOCK) { inclusive = true }
                        }
                    },
                )
            }
            composable(HOME) { entry ->
                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                    vaultList(navController, typeFilter = null, entry = entry)
                }
            }
            // Back arrows and openers alike go through [popFrom] / [navigateFrom]: a doubled tap
            // must not pop the screen underneath, or stack a second copy of the one it opened.
            composable(TRASH) { entry ->
                TrashScreen(
                    viewModel = hiltViewModel(),
                    onBack = { navController.popFrom(entry) },
                )
            }
            composable(SETTINGS) { entry ->
                SettingsScreen(
                    viewModel = hiltViewModel(),
                    onBack = { navController.popFrom(entry) },
                    onHealthClick = { navController.navigateFrom(entry, HEALTH_DASHBOARD) },
                    onImportClick = { navController.navigateFrom(entry, IMPORT) },
                    onBackupClick = { navController.navigateFrom(entry, BACKUP) },
                    onRecoveryKitClick = { navController.navigateFrom(entry, RECOVERY_KIT) },
                    onTrustedAppsClick = { navController.navigateFrom(entry, TRUSTED_APPS) },
                    onHiddenItemsClick = { navController.navigateFrom(entry, HIDDEN_ITEMS) },
                )
            }
            composable(HIDDEN_ITEMS) { entry ->
                HiddenItemsScreen(
                    viewModel = hiltViewModel(),
                    onBack = { navController.popFrom(entry) },
                )
            }
            composable(TRUSTED_APPS) { entry ->
                TrustedAppsScreen(
                    viewModel = hiltViewModel(),
                    onBack = { navController.popFrom(entry) },
                )
            }
            composable(RECOVERY_KIT) { entry ->
                RecoveryKitScreen(
                    viewModel = hiltViewModel(),
                    onBack = { navController.popFrom(entry) },
                )
            }
            composable(IMPORT) { entry ->
                ImportWizardScreen(
                    viewModel = hiltViewModel(),
                    onBack = { navController.popFrom(entry) },
                    // Land on the vault so the freshly imported items are the first thing seen.
                    onFinished = {
                        // When the wizard was opened from onboarding's Done step, that step is
                        // still underneath and has to go with the rest of the stack.
                        val onboardingOnStack = runCatching {
                            navController.getBackStackEntry(ONBOARDING)
                        }.isSuccess
                        navController.navigate(HOME) {
                            popUpTo(importLandingPopTarget(onboardingOnStack)) { inclusive = true }
                        }
                    },
                    // Instead of the wizard, not on top of it: the user has just learned the file is
                    // a backup and is going to restore it, so Back from Backup & export lands where
                    // they came from rather than on a refusal they have already read.
                    onOpenBackup = {
                        navController.ifStillOn(entry) {
                            // Lands on "Import a .vault file", not on Cloud backup three sections up.
                            BackupFocus.request(BackupFocus.IMPORT)
                            navController.navigate(BACKUP) { popUpTo(IMPORT) { inclusive = true } }
                        }
                    },
                )
            }
            composable(BACKUP) { entry ->
                BackupSettingsScreen(
                    viewModel = hiltViewModel(),
                    onBack = { navController.popFrom(entry) },
                    // Tier B copy that lives in :feature:settings, which :feature:backup cannot
                    // depend on. See BackupMessage.ImportComplete.
                    recoveryKitVoidedNotice =
                        com.localkeep.feature.settings.R.string.settings_password_changed_recovery_kit_voided,
                )
            }
            composable(HEALTH_DASHBOARD) { entry ->
                HealthDashboardScreen(
                    viewModel = hiltViewModel(),
                    onItemClick = { itemId -> navController.navigateFrom(entry, "item/$itemId") },
                    onOpenDrawer = LocalDrawerOpener.current,
                )
            }
            composable(
                FOLDER_NEW,
                arguments = listOf(
                    navArgument("parent") { nullable = true; defaultValue = null; type = NavType.StringType },
                    navArgument("fromSheet") { defaultValue = false; type = NavType.BoolType },
                ),
            ) { entry ->
                // Its own VaultListViewModel instance, scoped to this back-stack entry. It reads the
                // same repository, so the folder list it shows and the folder it writes are the one
                // vault — the precedent FolderViewModel already sets on the list screen itself.
                val folderListViewModel: VaultListViewModel = hiltViewModel()
                val listState by folderListViewModel.state.collectAsStateWithLifecycle()
                NewFolderScreen(
                    folders = listState.folders,
                    folderItemCounts = listState.folderItemCounts,
                    initialParentId = entry.arguments?.getString("parent")
                        ?.let(java.util.UUID::fromString),
                    // Cancelling returns to the door the user came through. Only the cancel path
                    // posts the result: after a *create*, the list itself — with the new folder row
                    // on it — is the confirmation, and re-raising the sheet would cover it.
                    onDismiss = {
                        navController.ifStillOn(entry) {
                            navController.cancelBackToSheet(
                                entry.arguments?.getBoolean("fromSheet") == true,
                            )
                        }
                    },
                    // Leaving after at least one folder was made, which the screen decides: a plain
                    // pop, with nothing re-raised over the list that now shows the new rows.
                    onDone = { navController.ifStillOn(entry) { navController.popBackStack() } },
                    // Does not leave. The screen clears its own field and stays for the next folder
                    // (2026-10-12: "What if I want to create 5 or 10 folders or 20?"); the callback
                    // is the ViewModel telling it the write landed. A refused create keeps the name
                    // and posts a notice saying why (QA 2026-10-03), and a second tap while the
                    // first is in flight is refused by the ViewModel's own write guard.
                    onCreate = { name, parentId, onCreated ->
                        navController.ifStillOn(entry) {
                            folderListViewModel.createFolder(name, parentId, onCreated)
                        }
                    },
                )
            }
            composable(FOLDERS) { entry ->
                FolderScreen(
                    viewModel = hiltViewModel(),
                    // Back to the list, narrowed. The narrowing lives in VaultListViewModel and not
                    // in the route, so it travels through FolderFocus rather than a route argument.
                    onOpenFolder = { path ->
                        navController.ifStillOn(entry) {
                            FolderFocus.request(path)
                            navController.navigate(HOME) { popUpTo(HOME) { inclusive = true } }
                        }
                    },
                    onBack = { navController.popFrom(entry) },
                )
            }
            composable(GENERATOR) { entry ->
                GeneratorScreen(
                    viewModel = hiltViewModel(),
                    onOpenDrawer = LocalDrawerOpener.current,
                    // The value goes through PendingSecret rather than into the route. A navigation
                    // argument is held in the back-stack entry and serialised into saved instance
                    // state, which the system writes to disk — a plaintext password outside the
                    // vault, and in the route string of anything that logs navigation.
                    // The entropy rides along with the value, and for the same reason the value does
                    // not ride in the route: it is the generator's own measure of the draw, and the
                    // editor cannot recompute it from the string. Without it the form re-scored the
                    // password with zxcvbn and contradicted the verdict the user had just read one
                    // tap earlier (F-29, 2026-09-15 visual pass).
                    // Guarded, offer included: a second tap must neither stack a second editor nor
                    // re-offer the secret the first one is about to take. See [ifStillOn].
                    onSaveToVault = { generated, entropyBits ->
                        navController.ifStillOn(entry) {
                            PendingSecret.offer(generated, entropyBits)
                            // No `fromSheet`: this door is the generator, and backing out of the
                            // editor returns there, not to a sheet the user never opened.
                            navController.navigate(itemNew(NewItemType.LOGIN))
                        }
                    },
                )
            }
            composable(
                ITEM_NEW,
                arguments = listOf(
                    navArgument("type") { nullable = true; defaultValue = null; type = NavType.StringType },
                    navArgument("fromSheet") { defaultValue = false; type = NavType.BoolType },
                ),
            ) { entry ->
                val editorViewModel: ItemEditViewModel = hiltViewModel()
                // The FAB adds into the folder the list was browsing. Taken here rather than in the
                // ViewModel's init, and only on this route: the same ViewModel serves ITEM_EDIT, so
                // an init that consumed the pending would have re-filed the next *existing* item
                // the user opened.
                LaunchedEffect(Unit) { editorViewModel.seedFolderFromPending() }
                ItemEditScreen(
                    viewModel = editorViewModel,
                    itemId = null,
                    onManageAttachments = { id -> navController.navigateFrom(entry, "attachments/$id") },
                    // Where "done" leads depends on how the editor was reached, which popping alone
                    // cannot know. From the FAB the previous entry is the vault list and popping is
                    // right. From the generator it is the generator — so saving a freshly generated
                    // login put the user back on the generator, looking at a password they had
                    // already filed, with no sign of the item they had just made.
                    //
                    // Both routes now also ask the list to point at what was just saved. Popping
                    // alone was not enough: returning to a type-filtered list that cannot show the
                    // new item is the same failure in a quieter form — the item is there, the
                    // screen cannot display it, and nothing says so. Landing on the item's own
                    // detail screen was considered and rejected; a card's CVV is masked and
                    // re-auth-gated there, so the user would have been asked for a fingerprint to
                    // see the number they had typed seconds earlier.
                    onSaved = {
                        PendingReveal.offer(editorViewModel.state.value.itemId.toString())
                        if (navController.previousBackStackEntry?.destination?.route == GENERATOR) {
                            navController.navigate(HOME) { popUpTo(HOME) { inclusive = true } }
                        } else {
                            navController.popBackStack()
                        }
                    },
                    // The Identity camera route's own exit. It lands on the item rather than the
                    // list, which is the opposite of the reasoning above and deliberately so: that
                    // flow ends on a screen saying the photographs are re-auth gated, so being
                    // asked for a fingerprint to open one immediately afterwards is the promise
                    // being kept, not a surprise. `popUpTo` so Back leaves the wizard behind — a
                    // saved item must not reopen a viewfinder.
                    onOpenSaved = { id ->
                        navController.navigate("item/$id") {
                            popUpTo(ITEM_NEW) { inclusive = true }
                        }
                    },
                    onBack = {
                        navController.ifStillOn(entry) {
                            navController.cancelBackToSheet(
                                entry.arguments?.getBoolean("fromSheet") == true,
                            )
                        }
                    },
                    presetType = entry.arguments?.getString("type")?.toEditorType(),
                    startGenerated = entry.arguments?.getString("type")?.startsGenerated() == true,
                )
            }
            composable(ITEM) { entry ->
                val itemId = entry.arguments?.getString("itemId") ?: return@composable
                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                    ItemDetailScreen(
                        viewModel = hiltViewModel(),
                        itemId = itemId,
                        onEdit = { navController.navigateFrom(entry, "item/$itemId/edit") },
                        onManageAttachments = { id -> navController.navigateFrom(entry, "attachments/$id") },
                        // The detail screen destroys itself by succeeding, so the Undo it offers
                        // cannot belong to it — see TrashUndoBus (4A-F7). The strings arrive
                        // already resolved because LkNotices is Context-free.
                        onTrashed = { id, moved, undo, restored, restoreFailed ->
                            TrashUndoBus.offerUndo(
                                id = UUID.fromString(id),
                                movedNotice = moved,
                                undoLabel = undo,
                                restoredNotice = restored,
                                restoreFailedNotice = restoreFailed,
                            )
                        },
                        onBack = { navController.popFrom(entry) },
                    )
                }
            }
            composable(ITEM_EDIT) { entry ->
                ItemEditScreen(
                    viewModel = hiltViewModel(),
                    itemId = entry.arguments?.getString("itemId"),
                    onManageAttachments = { id -> navController.navigateFrom(entry, "attachments/$id") },
                    // Saving returns to the detail view, not past it — the user asked to edit this
                    // item, so landing back on it lets them confirm the change took. Guarded so a
                    // second call cannot pop the detail view too. See [ifStillOn].
                    onSaved = { navController.ifStillOn(entry) { navController.popBackStack() } },
                    onBack = { navController.popFrom(entry) },
                )
            }
            composable(
                FILE_NEW,
                arguments = listOf(
                    navArgument("fromSheet") { defaultValue = false; type = NavType.BoolType },
                ),
            ) { entry ->
                val fileEditorViewModel: FileItemEditViewModel = hiltViewModel()
                // The folder the list was browsing, taken here for ITEM_NEW's reason: the same
                // ViewModel serves FILE_SETTINGS, which must never consume it.
                LaunchedEffect(Unit) { fileEditorViewModel.seedFolderFromPending() }
                FileItemEditScreen(
                    viewModel = fileEditorViewModel,
                    itemId = null,
                    onManageAttachments = { id -> navController.navigateFrom(entry, "attachments/$id") },
                    // F50, 2026-09-13 (device walk): every other "new item" route offers the same
                    // reveal (see ITEM_NEW's onSaved above, and its own comment) — a plain
                    // popBackStack here is what left this the one save in the app that landed the
                    // list at the top instead of scrolled-and-washed onto the new row.
                    onSaved = {
                        PendingReveal.offer(fileEditorViewModel.state.value.itemId.toString())
                        navController.popBackStack()
                    },
                    onBack = {
                        navController.ifStillOn(entry) {
                            navController.cancelBackToSheet(
                                entry.arguments?.getBoolean("fromSheet") == true,
                            )
                        }
                    },
                )
            }
            composable(FILE_SETTINGS) { entry ->
                FileItemEditScreen(
                    viewModel = hiltViewModel(),
                    itemId = entry.arguments?.getString("itemId"),
                    onManageAttachments = { id -> navController.navigateFrom(entry, "attachments/$id") },
                    onSaved = { navController.popFrom(entry) },
                    onBack = { navController.popFrom(entry) },
                )
            }
            composable(FILE_ITEM) { entry ->
                val itemId = entry.arguments?.getString("itemId") ?: return@composable
                FileItemViewerScreen(
                    viewModel = hiltViewModel(),
                    itemId = itemId,
                    onBack = { navController.popFrom(entry) },
                    onSettings = { id -> navController.navigateFrom(entry, "file/$id/settings") },
                    onViewerError = { messageRes ->
                        navController.previousBackStackEntry?.savedStateHandle?.set(
                            VIEWER_ERROR,
                            messageRes,
                        )
                    },
                )
            }
            composable(ATTACHMENTS) { entry ->
                val itemId = entry.arguments?.getString("itemId") ?: return@composable
                AttachmentPickerScreen(
                    viewModel = hiltViewModel(),
                    itemId = itemId,
                    onBack = { navController.popFrom(entry) },
                )
            }
        }
        }
        }
    }
}

/** Whether finishing onboarding with [finish] removes onboarding from the back stack. */
internal fun onboardingLeavesStackOnFinish(finish: OnboardingUiState.Finish): Boolean =
    finish != OnboardingUiState.Finish.OPEN_IMPORT

/**
 * What a finished import wizard pops up to before landing on the vault list: onboarding when the
 * wizard was opened from its Done step (which still sits underneath), otherwise the list itself.
 */
internal fun importLandingPopTarget(onboardingOnStack: Boolean): String =
    if (onboardingOnStack) AppNavGraph.ONBOARDING else AppNavGraph.HOME
