package com.localkeep.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.localkeep.app.R
import com.localkeep.core.ui.ClipboardCountdown
import com.localkeep.core.ui.LkNoticeHost
import com.localkeep.core.ui.LkShellOverlays
import com.localkeep.core.ui.LocalDuressSession
import com.localkeep.core.ui.Haptic
import com.localkeep.core.ui.rememberHapticClick
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.lkExposeTestTags
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkLogo
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.lkClose
import com.localkeep.core.ui.theme.lkOpen
import com.localkeep.core.ui.theme.LkSectionLabel
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkFocusRing
import com.localkeep.core.ui.theme.lkPressScale
import com.localkeep.core.ui.theme.lkPressWash
import com.localkeep.core.ui.theme.lkRing
import com.localkeep.core.ui.theme.lkSurface
import com.localkeep.core.ui.theme.lkTween
import com.localkeep.feature.backup.BackupFocus
import com.localkeep.feature.settings.SettingsFocus
import com.localkeep.feature.vaultlist.HomeFilter
import com.localkeep.feature.vaultlist.ItemTypeFilter
import kotlinx.coroutines.launch

/**
 * The persistent navigation shell: a floating three-destination bottom bar and the full-height
 * command drawer (UI/UX spec §4.2, §4.3).
 *
 * The two surfaces answer different questions and deliberately overlap. The bottom bar is a
 * *frequency* surface — the three things reached constantly, kept in thumb range (§2 one-handed
 * reach). The drawer is the *complete index*, grouped by purpose so it stays scannable as features
 * are added. Generator and the security dashboard appear in both for exactly that reason.
 *
 * Both are hidden outside [shellRoutes]: onboarding, unlock and the item screens are focused tasks
 * where a nav bar would invite the user to wander off mid-edit, and unlock must not advertise
 * destinations that are inaccessible anyway.
 */
/**
 * The entry whose chrome the shell should be wearing — the screen that is actually **on screen**,
 * not the one the back stack has just switched to.
 *
 * `currentBackStackEntryAsState` flips on the `navigate()` / `popBackStack()` call, and the NavHost
 * swaps its content later: it has to compose the destination first, which on this emulator takes
 * around 400 ms for either the vault list or the item form. In between, the Scaffold wore the
 * destination's tab bar while still displaying the previous screen — and because the bar is a
 * Scaffold slot it also changed that screen's content padding, so its footer jumped. That is what
 * six days of reports called flickering. Measured whole-screen, bottom third, leaving the Login
 * form for the vault list:
 *
 *     56 56 53 53 60 60 60 60 37
 *
 * 56 is the Login footer, 60 is that same footer after the tab bar arrived and pushed it up, 37 is
 * the vault list. Four frames of a screen the user had already left, rearranging itself. No change
 * to the transition could fix it, because the transition was not what was moving.
 *
 * **The right moment to change the chrome is the frame the content changes, and that moment sits
 * on different sides of the nav call depending on direction.**
 *
 * - Going *forward*, the screen being left is about to be covered. Switching immediately costs at
 *   most a frame or two of it without its bar, on its way out.
 * - Going *back*, the screen being left stays on screen for the whole time the destination spends
 *   composing. Switching immediately puts the destination's bar on it — the measurement above.
 *   So a pop waits for the destination to reach RESUMED, which is the NavHost saying it is up.
 *
 * Waiting in both directions was tried and is worse: RESUMED lands about 500 ms after the content
 * appears, so a push then draws the *arriving* screen wearing the bar it is supposed to have shed
 * — 60 for seven frames, the same defect pointing the other way.
 */
@Composable
private fun rememberChromeEntry(navController: NavHostController): NavBackStackEntry? {
    val current by navController.currentBackStackEntryAsState()
    var chrome by remember { mutableStateOf(current) }
    // Ids we have already worn. An id coming round again is a pop — the only way back to an entry
    // is to pop to it, since every forward navigation mints a new one.
    val seen = remember { mutableSetOf<String>() }
    val state = current?.lifecycle?.currentStateAsState()?.value
    LaunchedEffect(current, state) {
        val entry = current ?: return@LaunchedEffect
        val returning = entry.id in seen
        val arrived = state?.isAtLeast(Lifecycle.State.RESUMED) == true
        if (!returning || arrived || chrome == null) {
            seen += entry.id
            chrome = entry
        }
    }
    return chrome
}

/**
 * Whether the command drawer, in this state, owns the back press.
 *
 * `DrawerState.isOpen` alone is not the answer, and that gap is 2A-F10. `currentValue` is the
 * `AnchoredDraggableState`'s **settled** value: it stays `Closed` for the whole open animation and
 * only flips when the sheet has come to rest. So a back press in the ~300 ms after the hamburger —
 * while the drawer is visibly sliding in — found the handler disabled, fell through to the
 * NavController and popped the destination *behind* the sheet. The drawer then finished opening
 * over a screen the user had not chosen: measured on a device 2026-09-20 as Health → hamburger →
 * BACK, leaving `Close navigation menu` on screen with `All items, selected` underneath.
 *
 * [target] closes that window. [current] is still needed for the other end of it: while the drawer
 * animates *closed* the target is already `Closed`, and dropping the press there would navigate
 * under a sheet that is still drawn. Either end open means the drawer takes it.
 */
internal fun drawerConsumesBack(current: DrawerValue, target: DrawerValue): Boolean =
    current == DrawerValue.Open || target == DrawerValue.Open

/**
 * [vaultOpen] is whether the vault is unlocked. Defaults to true only so a test can compose the
 * shell without a lock manager; `MainActivity` always passes the real answer.
 */
@Composable
fun AppShell(
    navController: NavHostController,
    vaultOpen: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // `ModalNavigationDrawer` exposes no animation spec, so the reduced-motion path cannot go
    // through `lkTween` like every other surface. See `DrawerState.lkOpen`.
    val reduceMotion = LocalLkReduceMotion.current
    val backStackEntry = rememberChromeEntry(navController)
    val route = backStackEntry?.destination?.route
    val inShell = route in shellRoutes
    val currentVaultOpen by rememberUpdatedState(vaultOpen)

    // 2026-10-02 (O3-03): a lock never went through the drawer. Locking with it open (power
    // button, idle timer) left it over the unlock screen, where gestures are off so its scrim could
    // not dismiss it and swallowed taps on the password field — and its rows still navigated, so
    // Settings and Backup opened above UNLOCK on a sealed vault. Snapped shut, not animated: the
    // vault has already gone.
    LaunchedEffect(vaultOpen, route) {
        if (!vaultOpen || route == AppNavGraph.UNLOCK) drawerState.snapTo(DrawerValue.Closed)
    }

    fun go(destination: String) {
        // Every destination in the drawer is inside the vault. Read at tap time, so a tap that
        // lands between the lock and the snap above still goes nowhere.
        if (!currentVaultOpen) return
        scope.launch { drawerState.lkClose(reduceMotion) }
        navController.navigateTopLevel(destination)
    }

    // The tab bar gets the drawer's guard. It stays drawn for the length of the lock transition, so
    // a tap there can land after the vault has sealed; `navigateTopLevel` refuses UNLOCK as a
    // starting point too, which covers the frame before this flag has caught up.
    fun tab(destination: String) {
        if (!currentVaultOpen) return
        navController.navigateTopLevel(destination)
    }

    // Back closes the drawer before it means anything else.
    //
    // The `if` is the load-bearing part, not the handler inside it, and swapping it for
    // `BackHandler(enabled = ...)` puts 2A-F10 straight back. `OnBackPressedDispatcher` hands the
    // press to the *last enabled callback it was given*, and `NavHost` registers one of its own
    // from inside its composition — navigation-compose 2.9.8's `NavHostKt.NavHost` calls
    // `NavComposeUtils_androidKt.PredictiveBackHandler`, confirmed in its bytecode. The nav host is
    // a descendant of this shell, so its callback is always added after a callback this shell
    // registers once at its own composition, and wins every press no matter what `enabled` says.
    //
    // Measured on API 35, 2026-09-21, with the shell logging its own state on each frame: the
    // drawer open, the shell reading `consumes=true cur=Open tgt=Open`, the shell's handler never
    // called, and the destination behind the open sheet popped Health -> Vault. The enabled flag
    // was right; the registration order was not.
    //
    // Composing the handler only while the drawer owns the press re-registers it the moment the
    // drawer starts opening, which is after the nav host's, so the drawer holds the press for
    // exactly as long as it is on screen and gives it back when it has gone.
    if (drawerConsumesBack(drawerState.currentValue, drawerState.targetValue)) {
        // Routed through `lkClose` rather than Material's own dismissal so it honours reduced
        // motion like every other way this drawer closes.
        BackHandler { scope.launch { drawerState.lkClose(reduceMotion) } }
    }

    CompositionLocalProvider(
        LocalDrawerOpener provides { scope.launch { drawerState.lkOpen(reduceMotion) } },
    ) {
        ModalNavigationDrawer(
            // The outermost node of the shell, deliberately: the drawer's content is a *sibling*
            // of the Scaffold, not a descendant, so a flag set on the Scaffold reaches the bottom
            // bar and none of the drawer rows. Debug builds only — see lkExposeTestTags.
            modifier = Modifier.lkExposeTestTags(),
            drawerState = drawerState,
            // Without this the edge-swipe would open the drawer over unlock and mid-edit.
            gesturesEnabled = inShell,
            scrimColor = LkTheme.colors.scrim,
            drawerContent = {
                // While locked the drawer draws as it would in a decoy session. LocalDuressSession
                // reads false the moment a decoy session locks, so for the frame or two before the
                // snap above lands, the sheet would otherwise grow "Duress / decoy" in front of the
                // person the decoy is for. Hiding a real session's rows for those frames costs
                // nothing: the vault is shut either way.
                CompositionLocalProvider(
                    LocalDuressSession provides (LocalDuressSession.current || !vaultOpen),
                ) {
                    CommandDrawer(current = route, onNavigate = ::go)
                }
            },
        ) {
            Scaffold(
                containerColor = LkTheme.colors.ground,
                bottomBar = { if (inShell) LkBottomBar(route, onTab = ::tab) },
            ) { innerPadding ->
                // Whichever is higher, the tab bar or the keyboard. Placed against the tab bar alone,
                // a notice posted while typing — "Moved to trash" after trashing from a search —
                // landed under the IME, and a tap on its Undo typed into the search (QA 2026-10-03).
                //
                // The keyboard inset is measured from the window's bottom edge, but `MainActivity`
                // has already pushed this whole shell up by the navigation bar, so the part of it
                // that is left to clear is the inset minus [lkVerticalWindowInsets]. Taken whole, a
                // notice floated one navigation-bar height above where it was meant to be — and a
                // pinned action bar lifts by the notice's height and no more, so the notice slid
                // over the Save button instead of sitting in the gap left for it (S4-F-07).
                val density = LocalDensity.current
                val keyboardAboveShell = with(density) {
                    WindowInsets.ime.exclude(lkVerticalWindowInsets).getBottom(density).toDp()
                }
                val overlayFloor = maxOf(innerPadding.calculateBottomPadding(), keyboardAboveShell)
                Box(Modifier.fillMaxSize()) {
                    content(innerPadding)
                    // Mounted here rather than beside the nav host so these can sit *above* the tab
                    // bar. Placed as a sibling of the whole shell they covered the third tab, which
                    // is exactly the kind of thing that only shows up when the app is run.
                    //
                    // Stacked in a Column because both can be up at once — copying a password from
                    // a row and then trashing it posts a countdown and a confirmation, and as two
                    // independently bottom-aligned overlays the second drew straight over the first.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // These are siblings of the shell's content, not children of it, so
                            // they do not inherit the horizontal inset it applies — and they are
                            // prose in a plate, which must not run under a landscape cutout.
                            .windowInsetsPadding(lkHorizontalWindowInsets)
                            .padding(bottom = overlayFloor + 12.dp)
                            // Reported so the screen underneath can lift its own bottom-anchored
                            // controls clear of these — see LkShellOverlays.
                            .then(LkShellOverlays.measureModifier()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LkNoticeHost()
                        ClipboardCountdown()
                    }
                }
            }
        }
    }
}

/**
 * The half of the window insets that the shell applies *inside* the drawer host.
 *
 * `MainActivity` insets its root column vertically only. Everything horizontal — the landscape
 * navigation bar and the display cutout — is applied here instead, at each place that actually
 * draws content, because `ModalNavigationDrawer` parks its closed sheet relative to the origin of
 * its own layout box. Inset that box and the closed sheet stops short of the screen edge by exactly
 * the inset, which is how a 50dp strip of live, clickable drawer came to be painted down the left
 * of every landscape screen.
 *
 * Three places consume it: the banners above the shell, the shell's content, and the drawer sheet
 * itself. Adding a fourth is fine; putting it back on a common ancestor of the drawer is not.
 */
/**
 * The frame the shell's content sits in: the scaffold's top and bottom padding (the tab bar), and
 * the horizontal system bars and cutout from [lkHorizontalWindowInsets].
 *
 * Top and bottom only from the scaffold. Its padding also carries the horizontal system bars, and
 * taking those too and then applying [lkHorizontalWindowInsets] counted them twice: in landscape
 * the content sat about 50dp further in than the tab bar, which applies them once (SCL-023).
 */
@Composable
fun ShellContentFrame(innerPadding: PaddingValues, content: @Composable () -> Unit) {
    Box(
        Modifier
            .padding(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding(),
            )
            .windowInsetsPadding(lkHorizontalWindowInsets),
    ) { content() }
}

/**
 * The half `MainActivity` applies to its root column, above the shell: the status bar, the
 * navigation bar and the cutout, top and bottom. Whatever sits inside the shell is already clear of
 * these, so an inset measured from the window's edge has to have this taken off before it is used
 * as an offset from the shell's own bottom (see the notice floor in [AppShell]).
 */
internal val lkVerticalWindowInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Vertical)

internal val lkHorizontalWindowInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal)

/**
 * Lets a screen inside the shell raise the command drawer without every screen having to accept
 * and forward an `onOpenDrawer` lambda. Defaults to a no-op so a screen rendered outside the shell
 * (previews, the autofill activities) simply does nothing rather than crashing.
 */
val LocalDrawerOpener = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * A floating bar, not an edge-to-edge one.
 *
 * The gradient above it is what makes this work: list content scrolls *under* the bar and fades
 * into the ground rather than being clipped by a hard edge, so the vault reads as one continuous
 * surface with a control floating over it. A solid opaque bar on a near-black screen just looks
 * like the screen ends early.
 */
@Composable
internal fun LkBottomBar(route: String?, onTab: (String) -> Unit) {
    val c = LkTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.26f to c.ground.copy(alpha = 0.86f),
                    0.58f to c.ground,
                ),
            )
            // Inside the gradient, so the fade still runs edge to edge while the tabs themselves
            // clear the landscape navigation bar and the cutout. Its ancestor no longer supplies
            // this — see [lkHorizontalWindowInsets].
            .windowInsetsPadding(lkHorizontalWindowInsets)
            .padding(start = 14.dp, end = 14.dp, bottom = 10.dp, top = 18.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .lkSurface(LkShape.bar, c.cardTop.copy(alpha = 0.92f), c.ringStrong)
                .padding(7.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            bottomDestinations.forEach { destination ->
                val selected = destination.isSelected(route)
                BottomTab(
                    destination = destination,
                    selected = selected,
                    modifier = Modifier.weight(1f),
                    onClick = { onTab(destination.route) },
                )
            }
        }
    }
}

@Composable
private fun BottomTab(
    destination: BottomDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val label = stringResource(destination.labelRes)
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        if (selected) c.jade.copy(alpha = 0.09f) else Color.Transparent,
        lkTween(LkMotion.Base),
        label = "tab-bg",
    )
    val content by animateColorAsState(
        if (selected) c.jade else c.textFaint,
        lkTween(LkMotion.Base),
        label = "tab-fg",
    )

    Column(
        modifier = modifier
            .lkTag("tab-${destination.route}")
            // A floor, not a fixed height: at font scale 1.8 the icon, label and underline outgrew
            // 52dp and the underline ran into the label (QA 2026-10-03).
            .heightIn(min = 52.dp)
            .lkPressWash(interaction, LkShape.xl)
            .background(container, LkShape.xl)
            .lkFocusRing(LkShape.xl)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = rememberHapticClick(Haptic.SELECT, onClick),
            )
            // The label already names the destination; describing the icon too makes a screen
            // reader say "Vault, Vault".
            .labelled(if (selected) stringResource(R.string.app_nav_tab_selected, label) else label),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // A margin top and bottom inside the min-height floor, not just centring in it. Past font
        // scale 1.45 the icon, label slot and dot fill the 52dp floor exactly, and with nothing
        // reserved the dot sat on the tab's bottom edge (SCL-036). Equal either side, so at ordinary
        // size the content stays centred where it was.
        Spacer(Modifier.height(4.dp))
        Icon(
            destination.icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.height(4.dp))
        // LkText.chip (12sp), not LkText.badge (9sp). The badge style exists for the "Pro" and
        // "2FA" pills — two or three uppercase characters read at a glance beside an icon and a
        // sentence — and it was borrowed here for the three labels naming the app's primary
        // navigation. Measured on device 2026-07-29: 12dp tall. Material's navigation-bar label is
        // labelMedium at 12sp, and 9sp is smaller than any role in the Material scale; the tabs are
        // 122dp wide, so the longest label ("Generator") has room several times over. The code was
        // already reaching for chip's letter-spacing, which was the tell.
        // Autosized and pinned to one line. The tab is 122dp and the label had no `maxLines` and no
        // overflow at all, so a label that outgrew it wrapped or clipped. Measured on device at
        // fontScale 2.0: "Generator" already fills 280px of the 320px tab and pushes the selection
        // dot to within 3px of the bar's bottom edge — and English is the *short* locale here
        // ("Gesundheit" computes to ~310px). Shrinking beats wrapping: a nav label that goes to two
        // lines moves every tab's icon.
        //
        // The floor is a *rendered* size, not an sp one (SCL-019). 9sp was a floor that grew with the
        // font: at scale 2.0 it is 18dp, and "Gesundheit" at 18dp is wider than the ~88dp tab of a
        // 308dp window, so the shrink ran out and the word lost its last letter ("Gesundhei" on the
        // device). A one-line label cannot hyphenate and two lines would move the icons, so the
        // floor has to be one the longest label fits at: 11dp, which only ever applies when the
        // label does not fit at anything larger.
        val density = LocalDensity.current
        val maxLabel = LkText.chip.fontSize
        val floorLabel = with(density) { 11.dp.toSp() }.let { if (it > maxLabel) maxLabel else it }
        // A fixed-height slot, so three labels that settled on three sizes still leave the three
        // icons, and the three tabs, at one height (the selected tab used to stand taller).
        Box(
            Modifier.height(with(density) { (maxLabel.toDp() * 1.3f) }),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = label,
                style = LkText.chip.copy(color = content),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = floorLabel, maxFontSize = maxLabel),
            )
        }
        // The same decision as the drawer rail and the chip's check glyph: selection carries a
        // shape, not a tint alone. Laid out unconditionally so the tab's contents do not move when
        // it becomes selected — only the dot's colour changes.
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .lkTag("tab-dot-${destination.route}")
                .width(14.dp)
                .height(2.dp)
                .background(if (selected) c.jade else Color.Transparent, LkShape.xs),
        )
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * `internal` rather than private so [com.localkeep.app.DuressDrawerTest] can compose it directly.
 * Whether the duress row is drawn is a composition-level fact and there is no way to assert it
 * without composing the drawer — and getting it wrong is a P0.
 */
@Composable
internal fun CommandDrawer(
    current: String?,
    onNavigate: (String) -> Unit,
) {
    val c = LkTheme.colors
    // 308dp, but never the whole window. At the system's largest display size the window is 308.6dp
    // wide, so a sheet pinned to 308 covered it edge to edge and the tap-outside-to-dismiss target
    // — the drawer's primary dismissal — collapsed from 271px to 2px. Swiping still worked, so this
    // was not a trap with no way out, but a modal whose visible exit is two pixels wide is one the
    // user cannot see a way out of, which is close enough.
    //
    // 56dp reserved, which is Material's own minimum margin beside a navigation drawer and not a
    // number picked to clear a threshold. Reserving exactly the 48dp touch floor was tried and
    // measured on device at that density: the close target came out 166px, 47.4dp, half a point
    // under the floor purely because `screenWidthDp` is a rounded integer. A floor you meet by
    // rounding is one you do not meet.
    val windowWidth = LocalConfiguration.current.screenWidthDp.dp
    val sheetWidth = minOf(308.dp, (windowWidth - 56.dp).coerceAtLeast(240.dp))
    ModalDrawerSheet(
        drawerShape = LkShape.drawer,
        drawerContainerColor = Color.Transparent,
        modifier = Modifier.width(sheetWidth),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .background(Brush.verticalGradient(listOf(c.drawerTop, c.drawerBottom)))
                .verticalScroll(rememberScrollState())
                // The sheet is measured against the true window now, so it is the sheet that has to
                // keep its rows out of the cutout.
                .windowInsetsPadding(lkHorizontalWindowInsets)
                .padding(bottom = 24.dp),
        ) {
            DrawerHeader()

            DrawerGroup(stringResource(R.string.app_drawer_group_items))
            // Requests a filter reset alongside the navigation: "All items" navigates to HOME, which
            // is a no-op when the list is already the plain HOME destination, so a Favourites chip
            // set on it would otherwise survive the tap. The filters live in the ViewModel, not the
            // route. See [HomeFilter].
            DrawerLink(
                label = stringResource(R.string.app_drawer_all_items),
                icon = LkIcons.Layers,
                route = AppNavGraph.HOME,
                current = current,
                onNavigate = { route ->
                    HomeFilter.requestReset()
                    onNavigate(route)
                },
            )
            // No per-type rows. Type filtering has one surface now — the filter control beside the
            // vault list's search field — where it used to have three, and the drawer's copy was
            // the one that *navigated* to what is really a filter: tapping "Logins" on the Cards
            // destination left a screen titled "Cards" listing logins. The brass padlock those rows
            // carried for Pro types moved with them into the filter sheet on 2026-09-02.

            // Under ITEMS rather than TOOLS: a folder is a way of narrowing the vault, which is
            // what every other row in this group does, and it sits last because it is the only one
            // that manages the axis rather than applying it.
            DrawerLink(
                label = stringResource(R.string.app_drawer_folders),
                icon = LkIcons.Folder,
                route = AppNavGraph.FOLDERS,
                current = current,
                onNavigate = onNavigate,
            )

            DrawerGroup(stringResource(R.string.app_drawer_group_tools))
            DrawerLink(stringResource(R.string.app_drawer_generator), LkIcons.Refresh, AppNavGraph.GENERATOR, current, onNavigate)
            DrawerLink(stringResource(R.string.app_drawer_migrate_import), LkIcons.Import, AppNavGraph.IMPORT, current, onNavigate)
            DrawerLink(stringResource(R.string.app_drawer_backup_export), LkIcons.Export, AppNavGraph.BACKUP, current, onNavigate)
            // Its own entry, because rolling snapshots are the vault's rollback point and they were
            // the fifth and last section of the screen above — reachable only by already knowing
            // they were there and scrolling past four groups of encryption and export settings.
            // That is the wrong depth for the thing someone reaches for when something has already
            // gone wrong.
            //
            // Not a second screen: it opens Backup & Export and asks it to scroll to the section,
            // the same trick "Duress / decoy" below uses to land on a row rather than the top of a
            // long list. See [BackupFocus].
            DrawerLink(
                label = stringResource(R.string.app_drawer_restore_snapshot),
                icon = LkIcons.History,
                route = AppNavGraph.BACKUP,
                current = current,
                onNavigate = { route ->
                    BackupFocus.request(BackupFocus.SNAPSHOTS)
                    onNavigate(route)
                },
                tag = "backup-snapshots",
            )

            DrawerGroup(stringResource(R.string.app_drawer_group_security))
            DrawerLink(stringResource(R.string.app_drawer_password_health), LkIcons.ShieldCheck, AppNavGraph.HEALTH_DASHBOARD, current, onNavigate)
            // Reported from a phone, 2026-08-19: "Where is invisiblity edit on phase etc? I's hard
            // to find under Settings." It was reachable only from a row inside Settings, and a user
            // scanning for a security control has no reason to look there. The Settings row stays —
            // this is a second door, and Settings search still finds it.
            //
            // Straight to HIDDEN_ITEMS, not to Settings with a focus request the way "Duress /
            // decoy" and "Restore a snapshot" do: both of those name a *row* inside a long screen,
            // and SettingsFocus works by pre-filling Settings' search field. Invisible items is a
            // screen of its own, so it gets its own route — which also means the route is unique
            // and this link needs no `tag` override to stay distinct from "Settings" below.
            //
            // Not ShieldDecoy: that glyph means duress, and the two features are unrelated.
            //
            // Deliberately *not* hidden in a decoy session — same reasoning as Recovery Kit below.
            // The screen's own gate accepts the current vault password, so a coercer holding the
            // duress password can pass it; what they then see is the decoy vault's hidden set,
            // which is empty, because `revealPhrase` and `hiddenUntil` live inside the Vault model.
            // Nothing crosses the boundary, and a Security group one row short would be the tell.
            DrawerLink(stringResource(R.string.app_drawer_invisible_items), LkIcons.EyeOff, AppNavGraph.HIDDEN_ITEMS, current, onNavigate)
            // Absent in the decoy session. A coercer who has been handed the duress password and
            // finds a row named "Duress / decoy" has been told, by the app itself, that a second
            // vault exists — which is the one fact the feature exists to hide. See
            // [LocalDuressSession].
            if (!LocalDuressSession.current) {
                // The duress form is a row in Settings, not a screen of its own, so this link used
                // to keep its promise only as far as the top of a twenty-row list. It now names the
                // row it meant, and Settings opens on it.
                DrawerLink(
                    label = stringResource(R.string.app_drawer_duress_decoy),
                    icon = LkIcons.ShieldDecoy,
                    route = AppNavGraph.SETTINGS,
                    current = current,
                    onNavigate = { route ->
                        SettingsFocus.request(SettingsFocus.DURESS)
                        onNavigate(route)
                    },
                    // Same reason "Restore a snapshot" names itself: this row shares its route with
                    // "Settings" below and differs only in the section it asks for. Without the
                    // override both rows answered to `drawer-settings`, and `tapOn` takes the first
                    // match in tree order — which is this one. `.maestro/screens/settings.yaml` had
                    // therefore been capturing Settings scrolled to the duress row, never the top of
                    // the screen, in every language of the i18n sweep.
                    tag = "settings-duress",
                )
            }
            // Hidden in a decoy session since 2026-08-30, reversing the note that used to stand
            // here. That note argued a Recovery Kit is something an ordinary vault has and that a
            // Security group one row short would itself be the tell — both still true, and the
            // reversal is a deliberate choice between two tells rather than a correction.
            //
            // What tipped it: the row was not merely present, it *worked as far as the button and
            // then failed in a way a real vault never does*. `prepareRecoveryKit` returns null in a
            // decoy session, so "Create a Recovery Kit" answered with "Couldn't create a Recovery
            // Kit for this vault." — an outcome a healthy real vault cannot produce, two taps from
            // the drawer, repeatable. A missing row is ambiguous; a message that only ever appears
            // in the decoy is not. Found by the F14 QA persona, 2026-08-30.
            if (!LocalDuressSession.current) {
                // Key, not LkIcons.RecoveryKit, and this is deliberate rather than an oversight.
                //
                // The unlock screen's "Use Recovery Kit" button does use RecoveryKit, so this row
                // and that button draw one feature two ways — a real inconsistency, filed by the
                // 2026-09-01 visual sweep. It was fixed here and reverted the same session, because
                // the fix was worse: `LkIcons.RecoveryKit` and `LkIcons.SeedPhrase` are
                // byte-identical path definitions (LkIcons.kt:199 and :212), and SeedPhrase is the
                // Recovery *phrases* item type eight rows above this one in the same drawer. So
                // switching made the two rows identical — and the project's binding glossary says Recovery Kit and
                // Recovery phrase "must stay distinct", the confusion being dangerous enough that
                // the phrase type carries its own save-refusal.
                //
                // Neither glyph is right until the two have different art. That is an icon
                // decision, not a fix, and it is tracked as a follow-up.
                DrawerLink(stringResource(R.string.app_drawer_recovery_kit), LkIcons.Key, AppNavGraph.RECOVERY_KIT, current, onNavigate)
            }

            DrawerGroup(stringResource(R.string.app_drawer_group_system))
            // Trash lives here rather than under Security: recovering something you deleted is data
            // management, and a user scanning Security for security settings found a restore screen
            // while a user hunting for deleted items had no reason to look under Security at all.
            DrawerLink(stringResource(R.string.app_drawer_trash), LkIcons.Trash, AppNavGraph.TRASH, current, onNavigate)
            DrawerLink(stringResource(R.string.app_drawer_settings), LkIcons.Settings, AppNavGraph.SETTINGS, current, onNavigate)
        }
    }
}

@Composable
private fun DrawerHeader() {
    val c = LkTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 30.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LkLogo(size = 38.dp, filled = false)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.app_name), style = LkText.cardTitle, color = c.textStrong)
            // Was "nothing leaves this device", on every screen, regardless of the user's actual
            // cloud-backup setting — the strongest version of a claim the app could not keep, since
            // Android Auto Backup takes vault.enc by default. What is stated now is true whatever
            // that setting says: this build declares no INTERNET permission and talks to nothing.
            // Whether an encrypted copy goes to Google is the user's own choice, made at onboarding
            // and shown on the Backup screen, which is where a claim about it belongs.
            Text(
                stringResource(R.string.app_drawer_subtitle),
                style = LkText.badge.copy(letterSpacing = LkText.subtitle.letterSpacing),
                color = c.textFaint,
            )
        }
    }
}

@Composable
private fun DrawerGroup(title: String) {
    // No `bottom` here: [LkSectionLabel] owns the gap to the group it heads (LkSectionLabelGap).
    // The 7dp this used to add was a caller's own guess at the same relationship, and it now stacks
    // on top of the label's rather than supplying it.
    LkSectionLabel(
        title,
        modifier = Modifier.padding(start = 24.dp, top = 18.dp),
    )
}

@Composable
private fun DrawerLink(
    label: String,
    icon: ImageVector,
    route: String,
    current: String?,
    onNavigate: (String) -> Unit,
    /**
     * Overrides the sweep's handle for this row. Defaults to the route, which is unique for every
     * row but one: "Backup & Export" and "Restore a snapshot" both open [AppNavGraph.BACKUP] and
     * differ only in the section they ask it to scroll to, so the second names itself.
     */
    tag: String = route,
) {
    val c = LkTheme.colors
    val selected = current == route
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        if (selected) c.jade.copy(alpha = 0.08f) else Color.Transparent,
        lkTween(LkMotion.Quick),
        label = "drawer-bg",
    )
    val content by animateColorAsState(
        if (selected) c.jade else c.textTertiary,
        lkTween(LkMotion.Quick),
        label = "drawer-fg",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // From the route, so every drawer row is named without any call site passing one, and
            // a row added later is reachable by the sweep the day it appears.
            .lkTag("drawer-$tag")
            .padding(horizontal = 12.dp)
            .minTouchTarget()
            .lkPressScale(interaction, pressedScale = 0.97f, shape = LkShape.md)
            .background(container, LkShape.md)
            .lkFocusRing(LkShape.md)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = rememberHapticClick(Haptic.SELECT) { onNavigate(route) },
            )
            // Where you are was carried by colour alone, so the drawer read identically on every row
            // to anyone not seeing it.
            //
            // Spelled into the label, the way BottomTab does it, and not as `semantics { selected }`
            // — which is the tidier API and does not survive the trip. Measured with `uiautomator
            // dump` on the running app: with the property set, every row still came back
            // `selected="false"`, the current one included, while the bottom bar's spelled-out
            // "Vault, selected" was right there in the same tree. The dump is what TalkBack reads,
            // so the property was a green test and nothing else.
            //
            .labelled(
                if (selected) stringResource(R.string.app_drawer_item_selected, label) else label,
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A shape, not only a tint. `uiux:31` says colour is never the sole carrier of meaning, and
        // the selected and unselected rows measured **1.21:1** against each other — far under any
        // perceptual threshold, so where-you-are was invisible to a sighted user with reduced colour
        // discrimination. The accessibility half was already handled by spelling ", selected" into
        // the label above; this is the other half. UX debt 6-1/6-2, fixed as one decision across the
        // drawer, the bottom bar and the filter chips.
        //
        // The rail occupies its 3dp whether or not it is drawn, so selecting a row does not shift
        // the icon and label sideways.
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .background(
                    if (selected) c.jade else Color.Transparent,
                    LkShape.xs,
                ),
        )
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(19.dp))
        Text(label, style = LkText.navLabel, color = content, modifier = Modifier.weight(1f))
    }
}

private data class BottomDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    fun isSelected(current: String?): Boolean = current == route
}

// A plain (non-composable) list, so the label is a @StringRes id here and resolved with
// stringResource inside BottomTab, where composition is available.
private val bottomDestinations = listOf(
    BottomDestination(AppNavGraph.HOME, R.string.app_nav_vault, LkIcons.Shield),
    BottomDestination(AppNavGraph.GENERATOR, R.string.app_nav_generator, LkIcons.Refresh),
    BottomDestination(AppNavGraph.HEALTH_DASHBOARD, R.string.app_nav_health, LkIcons.ShieldCheck),
)

/**
 * Routes that display the shell. Everything else is a focused, full-screen task.
 *
 * Also the answer to "is the user somewhere they can be interrupted?" — MainActivity's stale-export
 * banner reads it for exactly that.
 */
internal val shellRoutes: Set<String> = buildSet {
    add(AppNavGraph.HOME)
    add(AppNavGraph.GENERATOR)
    add(AppNavGraph.HEALTH_DASHBOARD)
}

/**
 * Switches top-level destination without growing the back stack: repeatedly tapping between tabs
 * should not build up a history the user has to unwind with Back.
 *
 * Drawer leaves navigate plainly — see [topLevelNavDecision].
 *
 * `internal` rather than private because the shell is not the only thing that switches tab: a
 * long-press launcher shortcut arrives at `MainActivity` and has to reach the vault list the same
 * way the Vault tab does, saved stack and all. Two ways to select a tab would be two behaviours.
 */
internal fun NavHostController.navigateTopLevel(route: String) {
    val entry = currentBackStackEntry
    // Nothing top-level is reachable from the unlock screen. A tab tap that landed just after the
    // idle lock ran from here against `[UNLOCK]`: `popUpTo(HOME)` below found no HOME, logged
    // "Ignoring popBackStack to route vault", and the tab was pushed *over* UNLOCK — a sealed vault
    // with no unlock screen, failing every save until a force-stop. The lock collector never looked
    // again, because a `Locked` StateFlow does not re-emit. Read from the stack rather than the lock
    // state so it holds for every caller, whatever it believes about the vault.
    if (entry?.destination?.route == AppNavGraph.UNLOCK) return
    val decision = topLevelNavDecision(
        currentPattern = entry?.destination?.route,
        target = route,
    ) ?: return
    navigate(route) {
        popUpTo(AppNavGraph.HOME) { saveState = decision.saveAndRestore }
        launchSingleTop = decision.saveAndRestore
        restoreState = decision.saveAndRestore
    }
}
