package com.localkeep.core.ui.theme

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.ClipboardHelper
import com.localkeep.core.ui.R
import com.localkeep.core.ui.minTouchTarget

/** The two lock actions the toolbar control needs, without the feature modules knowing the manager. */
@Immutable
class LkVaultLocker(
    val lock: () -> Unit,
    val panicLock: () -> Unit,
)

/**
 * Published once by the activity that owns the lock manager, read by [LkLockButton] wherever it is
 * mounted. Same shape and same reason as [com.localkeep.core.ui.LocalDuressSession]: three screens
 * in three modules need one action, and threading a lambda through each of their signatures and
 * ViewModels is more wiring than the action is worth.
 *
 * Defaults to a pair of no-ops so a screen rendered outside the shell — a preview, the autofill
 * activities — draws the button dead rather than crashing.
 */
val LocalVaultLocker = staticCompositionLocalOf { LkVaultLocker(lock = {}, panicLock = {}) }

/**
 * The toolbar's lock control: tap = ordinary lock (biometric can reopen), long-press = panic lock,
 * which also blocks biometric until the master password is re-entered (§10.2).
 *
 * Shared rather than owned by the vault list, because it has to appear identically on all three
 * top-level tabs. It previously lived only on Vault; Generator and Security instead showed a back
 * chevron — a control pointing at a back stack that a tab destination does not have. A user two taps
 * into the generator had no way to lock from where they were, on the screen most likely to be open
 * when someone walks up behind you.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LkLockButton(
    modifier: Modifier = Modifier,
    onLocked: () -> Unit = {},
) {
    val locker = LocalVaultLocker.current
    val c = LkTheme.colors
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val panicLockToast = stringResource(R.string.core_ui_panic_lock_toast)
    Row(
        modifier = modifier
            .minTouchTarget()
            .lkPressScale(interaction, pressedScale = 0.95f)
            .background(c.field, LkShape.sm)
            .lkRing(LkShape.sm, c.ring)
            // `lkRing` above is a static hairline, not a focus indicator — same gap as the vault
            // rows: `indication = null` leaves this control with nothing to show when it holds
            // focus.
            .lkFocusRing(LkShape.sm)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    locker.lock()
                    onLocked()
                },
                onLongClick = {
                    // Clipboard first, then the lock. Panic lock is the one lock where clearing the
                    // clipboard has no cost: every other lock path deliberately leaves it alone,
                    // because backgrounding the app to paste a password IS the ordinary flow, and
                    // an aggressive auto-lock would otherwise wipe the clip mid-paste. Panic lock
                    // has no such user - it is pressed with someone standing over the phone - and
                    // leaving a just-copied password one paste away from any app undercuts the one
                    // control whose whole purpose is that adversary. `clearNow` is ownership-gated,
                    // so it can never wipe a clip this app did not put there.
                    ClipboardHelper.clearNow(context)
                    locker.panicLock()
                    Toast.makeText(context, panicLockToast, Toast.LENGTH_SHORT).show()
                    onLocked()
                },
                onClickLabel = stringResource(R.string.core_ui_lock_action_label),
                // combinedClickable turns this into a real long-click accessibility action, so
                // panic lock stays reachable from TalkBack's actions menu rather than depending on
                // a gesture a switch-access user can't make.
                onLongClickLabel = stringResource(R.string.core_ui_panic_lock_action_label),
            )
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(LkIcons.Lock, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.core_ui_lock_button), style = LkText.chip, color = c.textSecondary)
    }
}
