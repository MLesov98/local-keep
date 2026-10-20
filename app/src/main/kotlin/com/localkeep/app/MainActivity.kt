package com.localkeep.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.localkeep.app.navigation.AppNavGraph
import com.localkeep.app.navigation.AppShell
import com.localkeep.app.navigation.AppShortcuts
import com.localkeep.app.navigation.ShellContentFrame
import com.localkeep.app.navigation.LockNavigation
import com.localkeep.app.navigation.awaitCurrentRoute
import com.localkeep.app.navigation.holdUnlockWhileLocked
import com.localkeep.app.navigation.lockNavigation
import com.localkeep.app.navigation.lkHorizontalWindowInsets
import com.localkeep.app.navigation.lkVerticalWindowInsets
import com.localkeep.app.navigation.navigateTopLevel
import com.localkeep.app.navigation.shellRoutes
import com.localkeep.app.navigation.shortcutRoutesToVaultList
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.ui.LocalDuressSession
import com.localkeep.core.ui.LocalProEntitled
import com.localkeep.core.ui.SecureActivity
import com.localkeep.core.ui.announce
import com.localkeep.feature.backup.BackupPreferences
import com.localkeep.feature.backup.BackupStaging
import com.localkeep.feature.backup.R as BackupR
import com.localkeep.feature.backup.StaleExportReminder
import com.localkeep.feature.settings.SettingsFocus
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkVaultLocker
import com.localkeep.core.ui.theme.LocalVaultLocker
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkRing
import com.localkeep.core.vault.LocalKeepSettings
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLoader
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.app.review.ReviewGate
import com.localkeep.autofill.PendingSaveAction
import com.localkeep.autofill.PendingSaveQueue
import com.localkeep.app.review.launchPlatformReview
import com.localkeep.core.billing.Entitlement
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : SecureActivity() {
    @Inject
    lateinit var lockManager: VaultLockManager

    /** Read only to *describe* the tier (drawer padlocks, the unlock acknowledgement) — never to
     *  gate: gating lives in ProGate and the feature ViewModels. See [LocalProEntitled]. */
    @Inject
    lateinit var entitlement: Entitlement

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a genuine launch. A configuration change — rotation, a font-scale change, a
        // theme switch — recreates this Activity and hands it the *same* Intent back, so capturing
        // unconditionally would re-raise the add sheet every time the phone was turned sideways,
        // long after the shortcut that asked for it.
        if (savedInstanceState == null) {
            captureShortcut(intent)
        }
        // Go edge to edge so the keyboard is reported to Compose as `WindowInsets.ime` instead of
        // silently resizing the window out from under the layout. Without it the IME height is
        // taken off the top of every measure pass, which is invisible in code and shows up only on
        // a device as content that squeezes rather than scrolls, and it makes `imePadding()` a
        // no-op — so the pinned action bars below would look correct and do nothing.
        // Dark bar styling passed explicitly rather than left to the default, which derives it from
        // the system theme. The app's palette is dark-only on purpose — `LocalKeepTheme` always
        // passes `darkColorScheme`, so the UI does not follow the system — and with a phone in
        // light mode the default would have picked dark status-bar icons to sit on top of it.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val startRoute = when {
            lockManager.lockState.value is LockState.Unlocked -> AppNavGraph.HOME
            VaultLoader.hasVaultFiles(this) -> AppNavGraph.UNLOCK
            else -> AppNavGraph.ONBOARDING
        }
        setContent {
            LocalKeepTheme {
                val navController = rememberNavController()
                    // A lock that interrupts an in-progress edit drops the form: the inclusive pop
                    // destroys the editor's back-stack entry and its ViewModel, and the editor's
                    // unsaved-change guard only listens to Back and the header chevron, not to the
                    // lock observer. Nothing should say so during the lock transition (a notice
                    // would sit on top of the unlock screen), but the next successful unlock should
                    // tell the user the draft is gone rather than letting it vanish in silence.
                    // rememberSaveable, not remember: MainActivity declares no android:configChanges,
                    // so a rotation or other config change recreates the Activity. The LaunchedEffect
                    // below re-subscribes and the lock StateFlow replays, but a plain-remember flag
                    // would silently reset to false across that recreation — dropping the pending
                    // "your edit was discarded" notice the next unlock owes the user.
                    var editDroppedByLock by rememberSaveable { mutableStateOf(false) }
                    // The lock's half of navigation, in one place: the lock collector below and the
                    // back-stack watcher further down both seal the app onto UNLOCK, and used to carry
                    // two copies of these steps "kept in step by hand". [keepRecordedDrop] is the one
                    // difference between them.
                    fun sealToUnlock(keepRecordedDrop: Boolean) {
                        // The notice host is mounted around the whole nav graph, so a
                        // confirmation naming what was just trashed or saved would ride
                        // the lock transition and sit on top of the unlock screen —
                        // leaking the last action to whoever picks the phone up next.
                        LkNotices.clear()
                        // Same reasoning, one step further: a drawer tap on "Duress /
                        // decoy" that the lock interrupted would still be waiting, and
                        // the next session to open Settings could be a decoy one — which
                        // would put the words "Duress vault" on screen in front of exactly
                        // the person the decoy exists to convince nothing is there.
                        SettingsFocus.clear()
                        // Remember that an edit form was open and is being discarded, so
                        // the re-unlock can say so. The three edit routes are the only
                        // ones with unsaved-change guards, and "new" variants mount the
                        // same editor. The import wizard is included even though it has
                        // no discard guard: it holds staged state (picked file, KDBX
                        // password, reviewed rows) that the lock's `onCleared` wipes,
                        // and a KDBX Argon2 parse can easily outlive the auto-lock
                        // timer — so dropping it without a word made a user redo the
                        // whole wizard from the source picker, believing the import
                        // never started.
                        val route = navController.currentDestination?.route.orEmpty()
                        // Editor-mounting routes only. A broad startsWith("item/") also
                        // matched the read-only detail route ("item/{itemId}"), so the
                        // next unlock announced discarded edits after the user had merely
                        // viewed an item. See AppNavGraph.droppedEditRoute.
                        //
                        // The `||` half is asked of the ViewModel, not of the route,
                        // and that is the whole point. A decrypted `.vault` waiting on
                        // Merge or Replace is dropped by this lock exactly like a
                        // half-typed editor, and `droppedEditRoute` never matched
                        // "backup" — but adding it there would post the notice to
                        // everyone an auto-lock caught merely *reading* the backup
                        // screen, which is the same mistake the comment above records
                        // fixing for the read-only item route. The route says where
                        // they were; only BackupStaging says what they had. Read
                        // before the pop below, because the pop destroys the ViewModel
                        // that owns the answer.
                        val droppedStagedRestore = BackupStaging.take()
                        val dropped = AppNavGraph.droppedEditRoute(route) || droppedStagedRestore
                        // The collector records the verdict outright; the back-stack watcher
                        // only ever raises it, because the collector may already have recorded
                        // an editor this lock discarded and a later arrival is not one.
                        editDroppedByLock = dropped || (keepRecordedDrop && editDroppedByLock)
                        // Pop the whole graph, not `HOME`. A `popUpTo` naming a route that
                        // is not on the stack does nothing but log, and during first-run
                        // onboarding the stack is `[ONBOARDING]` — no HOME yet. So an
                        // auto-lock while the Recovery Kit was on screen used to *stack*
                        // Unlock on top of a live kit, and one Back press put 24 plaintext
                        // words that open the real vault back in front of whoever had the
                        // phone. Popping the graph inclusive leaves `[UNLOCK]` from every
                        // state, which also fixes the second-order case: unlocking then
                        // pops UNLOCK to `[HOME]` instead of `[ONBOARDING, HOME]`, where
                        // Back from the vault list dropped into the kit with the vault open.
                        navController.navigate(AppNavGraph.UNLOCK) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    }
                    // One review ask can be owed at a time. Without this a second external unlock
                    // arriving while the first ask is still waiting for the Activity to resume
                    // would queue a second one, and the next foreground would spend two of the
                    // three asks this install will ever make. Not saveable: a pending ask that
                    // does not survive a config change is one the gate simply offers again.
                    var reviewAskPending by remember { mutableStateOf(false) }
                    LaunchedEffect(navController) {
                        // Side effects that must not run while this Activity is off screen are
                        // launched into this scope and suspended on `withResumed`; the collect
                        // below must keep running for them, so they cannot suspend it in place.
                        val deferred = this
                        // NavController restores its back stack after process death. Observe the
                        // initial lock state too, so a restored vault route cannot bypass unlock.
                        lockManager.lockState.collect { state ->
                            // Decided by `lockNavigation` so the "graph not set yet" case is a
                            // tested branch rather than a crash: see its KDoc. The route is
                            // *awaited*, not read: an emission that beats the graph is applied to
                            // whatever back stack the graph restores, not dropped.
                            val decision = lockNavigation(
                                currentRoute = navController.awaitCurrentRoute(),
                                state = state,
                                hasVaultFiles = VaultLoader.hasVaultFiles(this@MainActivity),
                            )
                            when (decision) {
                                null -> Unit
                                LockNavigation.TO_UNLOCK -> sealToUnlock(keepRecordedDrop = false)
                                // The other direction, which nothing used to watch. The vault can be
                                // unlocked by something that is not this screen — the credential
                                // provider activity shares the same VaultLockManager, so answering an
                                // autofill or passkey request from Chrome unlocks it. Coming back to a
                                // LocalKeep that was sitting on the unlock screen then left the user
                                // stuck there: `unlock()` asserts the vault is locked, so the correct
                                // master password threw and was reported as "Unlock failed", over and
                                // over, until the idle timer eventually re-locked the vault.
                                LockNavigation.TO_HOME -> {
                                    navController.navigate(AppNavGraph.HOME) {
                                        popUpTo(AppNavGraph.UNLOCK) { inclusive = true }
                                    }
                                    if (editDroppedByLock) {
                                        editDroppedByLock = false
                                        LkNotices.postAtUnlock(R.string.app_notice_edit_dropped_by_lock)
                                    }
                                    // Onboarding writes the Android-backup choice at its own
                                    // step, which comes after the vault is already on disk. Kill
                                    // the app in between and the vault exists, onboarding never
                                    // runs again — `startRoute` sends every later launch to
                                    // UNLOCK — and the choice is silently left at its shipped
                                    // default. That default decides whether vault ciphertext
                                    // leaves the device, so making it on the user's behalf without
                                    // ever showing them the question is the part worth fixing.
                                    //
                                    // Shown once, then cleared, rather than nagged: the flag's
                                    // whole job is to carry the unasked question across the
                                    // interruption, and Settings -> Cloud backup leads to the same
                                    // switch for anyone who dismisses it. Cleared here even if the
                                    // action is ignored, for the same reason — a prompt that
                                    // returns at every unlock about a setting the user has already
                                    // decided to leave alone is worse than the gap it closes.
                                    //
                                    // Absent on every install that predates the flag, which reads
                                    // as "already chosen" — see LocalKeepSettings.
                                    if (LocalKeepSettings.isCloudBackupChoicePending(this@MainActivity)) {
                                        LocalKeepSettings.clearCloudBackupChoicePending(this@MainActivity)
                                        LkNotices.post(
                                            messageRes = R.string.app_notice_cloud_backup_unchosen,
                                            actionLabelRes = R.string.app_notice_cloud_backup_unchosen_action,
                                        ) {
                                            navController.navigate(AppNavGraph.BACKUP)
                                        }
                                    }
                                    // The same problem as the dropped edit, from the other end: a
                                    // snapshot restore force-locks the vault on success, so the one
                                    // operation that replaces the whole vault used to finish in
                                    // total silence — the Backup screen and its ViewModel are gone
                                    // before they can say it worked. Not a `rememberSaveable` flag
                                    // like the one above: nothing on this side of the lock knows a
                                    // restore happened, so the record is written by
                                    // RollingBackupManager and read back here.
                                    //
                                    // Deferred until this Activity is resumed, because this
                                    // collector deliberately has no lifecycle guard — it has to
                                    // re-route the nav host even when the unlock came from
                                    // somewhere else. The autofill, picker and credential-provider
                                    // activities share this VaultLockManager, so answering a fill
                                    // request from another app ran this branch with MainActivity
                                    // stopped: the flag was consumed and the notice posted behind
                                    // another app's window, where LkNoticeHost's four-second timer
                                    // dismissed it unseen. The flag is once-only, so that was the
                                    // entire confirmation for the one operation that replaces the
                                    // whole vault.
                                    deferred.launch {
                                        lifecycle.withResumed {
                                            if (BackupPreferences.consumeRestoreNoticePending(this@MainActivity)) {
                                                // A rolling snapshot holds `vault.enc` and nothing
                                                // else, so restoring an older generation can bring
                                                // back an item whose attachment the blob collector
                                                // already swept as orphaned. The item returns; the
                                                // file it points at does not. Reporting an
                                                // unqualified success there is the same error the
                                                // import path fixed with `missingBlobRefCount` —
                                                // the user finds the gap themselves, days later,
                                                // and has no way to tell a restore that dropped
                                                // files from one that did not.
                                                val missing = BackupPreferences
                                                    .consumeRestoreNoticePendingMissingBlobRefCount(
                                                        this@MainActivity,
                                                    )
                                                if (missing > 0) {
                                                    LkNotices.postAtUnlock(
                                                        LkNotices.Message.Plural(
                                                            BackupR.plurals
                                                                .backup_status_restore_complete_missing_files,
                                                            missing,
                                                        ),
                                                    )
                                                } else {
                                                    LkNotices.postAtUnlock(
                                                        R.string.app_notice_vault_restored,
                                                        tone = LkNotices.Tone.Success,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    // The only moment this app asks anything of the user it did not
                                    // have to. Gated hard — see ReviewGate: five unlocks, two weeks
                                    // installed, ninety days between asks, three asks ever. A real
                                    // unlock is the signal, so a duress session is excluded: the
                                    // decoy exists to convince someone nothing is here, and a Play
                                    // review card is a conversation about the app itself.
                                    //
                                    // Deferred for the same reason as the notice above, and it
                                    // costs more here: an ask is spent when `onAsked` records it,
                                    // not when a card appears, and PlatformReview swallows Play's
                                    // refusal. Requesting the card from a stopped Activity either
                                    // threw a rating prompt over the third-party sign-in form the
                                    // user was filling, or silently burned one of three lifetime
                                    // asks and waited another ninety days. `onUnlock` still runs
                                    // inline — it is the only place the unlock counter increments,
                                    // and external unlocks are exactly the ones that feed the gate.
                                    if ((state as? LockState.Unlocked)?.isDuress == false &&
                                        ReviewGate.onUnlock(this@MainActivity) &&
                                        !reviewAskPending
                                    ) {
                                        reviewAskPending = true
                                        deferred.launch {
                                            // The gate is the wait, not the block: `withResumed`
                                            // takes a non-suspending lambda and both calls below
                                            // suspend, so they run after it returns rather than
                                            // inside it.
                                            lifecycle.withResumed { reviewAskPending = false }
                                            ReviewGate.onAsked(this@MainActivity)
                                            launchPlatformReview(this@MainActivity)
                                        }
                                    }
                                }
                        }
                            // A save the platform accepted but never got to show. Android can
                            // refuse SaveConfirmActivity's PendingIntent as a background activity
                            // launch when the vault locks between the system's own "Save password
                            // to LocalKeep?" sheet and this app coming forward (BAL_BLOCK, result
                            // code 102, API 35) — the service is told the launch succeeded, the
                            // screen never appears, and the credential used to be wiped by the TTL
                            // with nothing said anywhere. The unlock is the first moment this app
                            // is in the foreground and the vault can be written, so it is where
                            // the offer belongs. See PendingSaveQueue.
                            when (PendingSaveQueue.actionFor(state)) {
                                PendingSaveAction.DISCARD -> PendingSaveQueue.discardAll()
                                // Deferred for the reason the two side effects above are, and one
                                // more that is particular to this: the collector runs with
                                // MainActivity stopped — AuthActivity, PickerActivity and the
                                // credential provider all share this VaultLockManager — and
                                // starting an Activity from there is the very background launch
                                // that was blocked in the first place.
                                PendingSaveAction.OFFER -> deferred.launch {
                                    lifecycle.withResumed {
                                        PendingSaveQueue.offerIntent(this@MainActivity)
                                            ?.let(::startActivity)
                                    }
                                }
                                PendingSaveAction.NONE -> Unit
                            }
                    }
                }
                    // The collector above routes to UNLOCK once per lock, and `Locked` never emits
                    // again — so a destination pushed after it (a tab tap that landed during the
                    // lock transition did this, 6/6 on a device) used to sit over a sealed vault
                    // with no unlock screen until a force-stop. This watches the back stack instead
                    // and sends any such arrival back to UNLOCK the way the collector would. The
                    // dropped-edit flag is only ever raised here, never cleared: the collector may
                    // already have recorded an editor this lock discarded, and the later arrival
                    // is not one.
                    LaunchedEffect(navController) {
                        navController.holdUnlockWhileLocked(
                            lockState = lockManager.lockState,
                            hasVaultFiles = { VaultLoader.hasVaultFiles(this@MainActivity) },
                        ) {
                            sealToUnlock(keepRecordedDrop = true)
                        }
                    }
                // The ground colour is painted outside the inset padding so the status- and
                // navigation-bar areas match the app rather than showing the window background
                // through as a lighter band.
                //
                // System bars and the cutout, deliberately NOT `safeDrawingPadding()`. `safeDrawing`
                // includes the IME, so padding by it handed every keyboard opening to the layout as
                // lost height — and a bounded column that cannot scroll does not clip under that, it
                // *crushes*. Measured on onboarding step 2: with the keyboard up the CONFIRM label
                // went from 35px to 22px and its field disappeared entirely, so tapping where the
                // field appeared to be typed into the master-password field instead. Screens that
                // need to sit above the keyboard now say so themselves with `imePadding()` on the
                // element that must stay reachable, which is the primary action.
                //
                // **Vertical only.** The horizontal half — the landscape navigation bar and the
                // display cutout — is applied further in, by [lkHorizontalWindowInsets], and that
                // is not a stylistic preference. `ModalNavigationDrawer` parks its closed sheet at
                // minus-one-sheet-width from the origin of *its own* layout box, so an ancestor
                // that insets this Column horizontally moves that origin inboard and leaves the
                // sheet's tail on screen. Measured in landscape on a cutout device: a 132px strip
                // of drawer down the left edge of every screen, jade-tinted, with a half-cut "All
                // items" pill in it and up to eight navigation rows still clickable and still in
                // the accessibility tree, with the drawer closed. The drawer host has to span the
                // real window for a closed drawer to be off it.
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(LkTheme.colors.ground)
                        .windowInsetsPadding(lkVerticalWindowInsets),
                ) {
                    val context = LocalContext.current
                    // Seeded false and answered off the main thread. The verdict is four `stat()`
                    // calls plus a SharedPreferences read, and it used to be computed inline in
                    // this `remember` — on the first composition of every cold launch and, since
                    // no `android:configChanges` is declared (see above), every rotation too.
                    // Small, but it is disk I/O on the frame that inflates the whole shell, and it
                    // buys nothing by being early: this gates a dismissible advisory banner, not
                    // anything in the vault or crypto path — [RootCheck] says so itself — so
                    // arriving a frame later is invisible.
                    var showRootWarning by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        showRootWarning = withContext(Dispatchers.IO) {
                            RootCheck.looksRooted() && !RootWarningPreferences.isDismissed(context)
                        }
                    }
                    // Replaces the daily notification this used to post from a WorkManager job —
                    // see [StaleExportReminder]. Confined to the shell routes: the vault is
                    // technically unlocked the moment onboarding creates it, and driving that flow
                    // on a device is how this banner was caught telling someone still choosing a
                    // biometric setting that it had been a while since they last exported the vault
                    // they had made ninety seconds earlier. Unlock is excluded for the same reason
                    // the nav bars are — it is advice you cannot act on from there.
                    var remindExport by remember {
                        mutableStateOf(StaleExportReminder.shouldRemind(context))
                    }
                    val vaultOpen by lockManager.lockState.collectAsStateWithLifecycle()
                    val currentRoute = navController.currentBackStackEntryAsState().value
                        ?.destination?.route
                    // `remindExport` above only seeds the *first* verdict; without this it never
                    // changes again for the life of the process. `shouldRemind` reads one
                    // timestamp and is cheap to re-run, so re-derive it every time the back stack
                    // settles back on a shell route rather than trusting a cached answer — an
                    // export made from Settings -> Backup (which never touches this flag) would
                    // otherwise leave the banner claiming the vault is overdue a minute after it
                    // was exported. Gated to shellRoutes, not every route, for the same reason the
                    // banner itself is: re-deriving mid-onboarding would follow it right back onto
                    // a screen that cannot act on it.
                    LaunchedEffect(currentRoute) {
                        if (currentRoute in shellRoutes) {
                            remindExport = StaleExportReminder.shouldRemind(context)
                        }
                    }
                    // The launcher half of the two long-press shortcuts. This only ever moves the
                    // nav host *to* the vault list; the request itself is answered there, where the
                    // search field and the add sheet are. The rule — including the two cases it
                    // refuses, a locked vault and a focused task — is [shortcutRoutesToVaultList].
                    //
                    // A flow rather than a one-shot read, because the shortcut can arrive while the
                    // app is already open: `MainActivity` is `singleTask`, so that lands in
                    // `onNewIntent` with nothing about to compose for the first time.
                    val pendingShortcut by AppShortcuts.pending.collectAsStateWithLifecycle()
                    LaunchedEffect(pendingShortcut, vaultOpen, currentRoute) {
                        if (shortcutRoutesToVaultList(
                                pending = pendingShortcut,
                                unlocked = vaultOpen is LockState.Unlocked,
                                currentRoute = currentRoute,
                            )
                        ) {
                            navController.navigateTopLevel(AppNavGraph.HOME)
                        }
                    }
                    // The banners carry the horizontal inset themselves, because their parent no
                    // longer does. They are prose in a coloured plate and must not run under a
                    // cutout.
                    Column(Modifier.windowInsetsPadding(lkHorizontalWindowInsets)) {
                        if (showRootWarning) {
                            RootWarningBanner(
                                onDismiss = {
                                    RootWarningPreferences.setDismissed(context)
                                    showRootWarning = false
                                },
                            )
                        }
                        if (remindExport && vaultOpen is LockState.Unlocked && currentRoute in shellRoutes) {
                            StaleExportBanner(
                                onExport = {
                                    // Not cleared here: tapping "Back up" only navigates, it does
                                    // not export. Clearing eagerly is what let backing out without
                                    // exporting suppress a true reminder for the rest of the
                                    // process — the re-derivation above corrects the flag once the
                                    // back stack returns to a shell route, whichever way this goes.
                                    navController.navigate(AppNavGraph.BACKUP)
                                },
                                onDismiss = {
                                    StaleExportReminder.snooze(context)
                                    remindExport = false
                                },
                            )
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        // Published once, here, because this activity owns the lock manager. Every
                        // surface that would otherwise reveal the duress feature during a decoy
                        // session reads it from the same place — see [LocalDuressSession].
                        val lockState by lockManager.lockState.collectAsStateWithLifecycle()
                        val isPro by entitlement.isPro.collectAsStateWithLifecycle()
                        val isProLoaded by entitlement.isProLoaded.collectAsStateWithLifecycle()

                        // Buying Pro produced no affirmative moment anywhere: the gates simply
                        // stopped firing, which is indistinguishable from nothing having happened
                        // (2026-07-29). Posted on a genuine false-to-true transition only — the
                        // first *loaded* value after a cold start is the current tier, not a
                        // purchase, so seeding on it is what keeps this from firing on every launch.
                        // See observeProTier for why "loaded" matters.
                        var seenPro by remember { mutableStateOf<Boolean?>(null) }
                        val proUnlockMessage = stringResource(R.string.app_pro_unlock_announcement)
                        LaunchedEffect(isPro, isProLoaded) {
                            val observed = observeProTier(seenPro, isPro, isProLoaded, proUnlockMessage)
                            seenPro = observed.seen
                            observed.say?.let(LkNotices::post)
                        }

                        CompositionLocalProvider(
                            LocalDuressSession provides
                                ((lockState as? LockState.Unlocked)?.isDuress == true),
                            LocalProEntitled provides isPro,
                            // Same reason as the two above: the lock control sits on all three
                            // top-level tabs, in three different feature modules, and only this
                            // activity holds the manager.
                            LocalVaultLocker provides remember {
                                LkVaultLocker(
                                    lock = lockManager::lock,
                                    panicLock = lockManager::panicLock,
                                )
                            },
                        ) {
                            // AppShell also mounts the clipboard countdown, once, above its tab bar
                            // — so every copy site in the app gets the "clears in Ns" affordance
                            // without each screen having to remember to add it.
                            // The horizontal inset lands here, inside the drawer host, so every
                            // screen still clears the cutout while the drawer itself is measured
                            // against the whole window.
                            AppShell(
                                navController = navController,
                                vaultOpen = lockState is LockState.Unlocked,
                            ) { innerPadding ->
                                ShellContentFrame(innerPadding) {
                                    AppNavGraph(
                                        navController = navController,
                                        startDestination = startRoute,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * A long-press launcher shortcut tapped while LocalKeep is already alive.
     *
     * `launchMode` is `singleTask`, so the second launch reuses this Activity and its task instead
     * of creating another — which is what the vault requires, and also means the shortcut arrives
     * here rather than through [onCreate]. Without this override the intent would be dropped and
     * the shortcut would read as a plain app launch.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // So that a later configuration change replays the new intent rather than the launch one.
        setIntent(intent)
        captureShortcut(intent)
    }

    /**
     * Records which shortcut, if any, this intent is asking for. It is a *request for a
     * destination* and is treated as nothing more: the vault is opened by the unlock screen exactly
     * as it always is, and this Activity is exported, so an action arriving here is whatever any
     * app on the device chose to send. See [AppShortcuts].
     */
    private fun captureShortcut(intent: Intent?) {
        AppShortcuts.targetOf(intent?.action)?.let(AppShortcuts::request)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (::lockManager.isInitialized) {
            lockManager.extendAutoLock()
        }
    }
}

/**
 * Dismissible, non-blocking root/tamper awareness banner (Phase 16, Task 1 Step 5).
 *
 * **Coral, not amber.** It was amber until 2026-07-29 on the reasoning that nothing had failed and
 * nothing was blocked, so dressing it as a failure would train the user to dismiss real ones. That
 * reasoning is right for a note about a feature; it is wrong here, and the difference is what this
 * app is. Amber in this palette means "worth looking at, nothing is wrong" — but on a rooted device
 * something *is* wrong, and it is the assumption the whole product rests on. Every other guarantee
 * LocalKeep makes is downstream of the OS not being under someone else's control.
 *
 * The "no action, so red gets ignored" objection does not hold either. There is an action: stop
 * trusting this device with this vault. It is not a button because the app cannot perform it, which
 * is a reason to state the severity honestly rather than to soften it.
 *
 * The population argument cuts the same way. Root detection also fires on developers and
 * custom-ROM users, who know exactly what they did and lose nothing by seeing coral. The person the
 * colour is actually for is the one who did not root their phone — and for them a beige advisory
 * they skim past is the failure mode.
 *
 * `THREAT-MODEL.md` puts a root-level compromise (T3) out of scope, and this banner is how the app
 * says so out loud. Out of scope is a statement about what LocalKeep can defend, not a reason to
 * under-report the condition to the person holding the phone.
 *
 * Announced as a live region: it appears unprompted at launch, so a screen-reader user would
 * otherwise meet it only by chance of reading order. Contrast measured 2026-07-29 — coralText on
 * the 8%-coral ground is 8.97:1, against the 4.5:1 floor.
 */
@Composable
private fun RootWarningBanner(onDismiss: () -> Unit) {
    val c = LkTheme.colors
    // "...than on an unmodified device" was cut on 2026-07-29: the first sentence already says the
    // device is modified, so the comparison restated a premise the reader had just been given and
    // pushed the banner onto a third line for it.
    val message = stringResource(R.string.app_root_warning_message)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A single entrance, and deliberately nothing after it. A pulse or a shake was
            // considered and rejected: an animation that never stops is the "continuous animation"
            // anti-pattern (reserved for loading indicators), it fails `motion-meaning` because
            // there is no cause-and-effect for it to express, and it is a vestibular hazard on a
            // banner the user cannot make go away except by dismissing it. Shake is also the wrong
            // verb — in this app and everywhere else it means "your input was rejected", not "your
            // device is compromised". Severity is already carried four times over: coral, the
            // warning glyph, the sentence, and the live-region announcement. `lkEnter` collapses to
            // a single frame under reduced motion, like every other entrance here.
            .lkEnter()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(c.coral.copy(alpha = 0.08f), LkShape.md)
            .lkRing(LkShape.md, c.coral.copy(alpha = 0.20f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            LkIcons.Warning,
            contentDescription = null,
            tint = c.coral,
            modifier = Modifier.size(16.dp),
        )
        Text(
            message,
            // The colour carries severity and the glyph carries it again, but the sentence has to
            // carry it on its own for anyone who reads neither — so the prose moves to coralText
            // rather than staying the neutral grey it shared with ordinary captions.
            modifier = Modifier.weight(1f).announce(message),
            style = LkText.caption,
            color = c.coralText,
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.app_root_warning_dismiss), style = LkText.buttonSmall, color = c.coralText)
        }
    }
}

/**
 * The stale-export reminder, in the app rather than in the notification shade.
 *
 * Two actions, because a reminder whose only control is "Dismiss" makes the user go and find the
 * screen it is talking about. "Back up" goes straight there; "Not now" snoozes for a week.
 */
@Composable
private fun StaleExportBanner(
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LkTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(c.amber.copy(alpha = 0.08f), LkShape.md)
            .lkRing(LkShape.md, c.amber.copy(alpha = 0.20f))
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            LkIcons.Export,
            contentDescription = null,
            tint = c.amber,
            modifier = Modifier.size(16.dp),
        )
        Text(
            stringResource(R.string.app_stale_export_message),
            modifier = Modifier.weight(1f),
            style = LkText.caption,
            color = c.textTertiary,
        )
        TextButton(onClick = onExport) {
            Text(stringResource(R.string.app_stale_export_action), style = LkText.buttonSmall, color = c.amber)
        }
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.app_stale_export_dismiss), style = LkText.buttonSmall, color = c.textFaint)
        }
    }
}
