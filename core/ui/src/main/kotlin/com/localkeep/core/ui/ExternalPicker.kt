package com.localkeep.core.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * An activity-result launcher that tells the lock manager the app is *not* being abandoned.
 *
 * A system picker runs in another process, so `ProcessLifecycleOwner` reports LocalKeep as
 * backgrounded and the auto-lock's background grace — five seconds on the Aggressive preset, thirty
 * on Balanced — starts running against a user who is doing exactly what the app just asked them to
 * do. Browsing
 * for a vault file to import, choosing where to write an export, picking a photo to attach, or
 * saving a Recovery Kit PDF all take longer than that, and the vault relocking mid-flow does not
 * merely annoy: the result callback comes back to a locked vault, so the chosen file is dropped and
 * the operation silently produces nothing.
 *
 * `VaultLockManager.beginExternalPicker`/`endExternalPicker` already existed to say "we launched
 * this, hold the longer timer". The problem was that pairing them was left to each call site, and
 * four of them — backup export/import, the file and attachment pickers, the Recovery Kit PDF —
 * simply did not. That is not a thing a reviewer can see: the missing call is an *absence*, in a
 * file whose diff looks complete.
 *
 * So the pairing is structural here. `begin` happens inside the returned launch function and `end`
 * inside the result callback, on every path including cancel (the contract still delivers a result
 * for a dismissed picker) and including a picker that refuses to open at all — leaking the counter
 * would pin the vault on the longer timer for the rest of the session.
 *
 * @return call it with the contract's input to open the picker.
 */
@Composable
fun <I, O> rememberExternalPickerLauncher(
    contract: ActivityResultContract<I, O>,
    onPickerOpened: () -> Unit,
    onPickerClosed: () -> Unit,
    onResult: (O) -> Unit,
): (I) -> Unit {
    // Captured by `rememberUpdatedState` so the launcher, which is remembered for the lifetime of
    // the composition, always calls the current lambdas rather than the ones from first composition.
    val currentClosed by rememberUpdatedState(onPickerClosed)
    val currentResult by rememberUpdatedState(onResult)
    val currentOpened by rememberUpdatedState(onPickerOpened)

    val launcher = rememberLauncherForActivityResult(contract) { result ->
        currentClosed()
        currentResult(result)
    }
    val context = LocalContext.current
    return remember(launcher) {
        { input ->
            launchExternalPicker(
                input = input,
                launch = { launcher.launch(it) },
                onOpened = { currentOpened() },
                onClosed = { currentClosed() },
                onUnavailable = {
                    Toast.makeText(context, R.string.core_ui_picker_unavailable, Toast.LENGTH_LONG).show()
                },
            )
        }
    }
}

/**
 * The bracketed launch, as a plain function so a JVM test can drive it without an activity.
 *
 * A device or profile with no handler for the picker intent (system Files debloated, a restricted
 * work profile) makes `launch` throw [ActivityNotFoundException] synchronously from the tap. The
 * comment here used to promise the exception would reach "whoever handles a missing picker
 * activity", and nobody did — every caller invokes the launcher bare — so the tap killed the
 * process and took any unsaved form with it (S3-05, 2026-10-02). It is answered here once, for all
 * of them. Anything else still propagates: only a missing picker is an expected condition.
 */
internal fun <I> launchExternalPicker(
    input: I,
    launch: (I) -> Unit,
    onOpened: () -> Unit,
    onClosed: () -> Unit,
    onUnavailable: () -> Unit,
) {
    onOpened()
    try {
        launch(input)
    } catch (_: ActivityNotFoundException) {
        // Nothing opened, so nothing will call back: balance the counter or the vault stays on the
        // longer timer for the rest of the session, then say why the tap did nothing.
        onClosed()
        onUnavailable()
    } catch (t: Throwable) {
        onClosed()
        throw t
    }
}
