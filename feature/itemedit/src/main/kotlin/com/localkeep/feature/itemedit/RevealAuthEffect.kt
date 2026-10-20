package com.localkeep.feature.itemedit

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import com.localkeep.core.ui.RevealAuth

/**
 * Raises the re-auth prompt for a high-value secret and reports the outcome back to
 * [ItemEditViewModel] (UI/UX spec §5.2).
 *
 * Lives here, shared by the read view and the edit form, so the two can't drift into different
 * reveal rules — a gate that applies on one screen and not the other is no gate at all. The
 * ViewModel parks the request instead of raising it itself because [RevealAuth] needs an Activity.
 */
@Composable
internal fun RevealAuthEffect(
    pendingField: String?,
    viewModel: ItemEditViewModel,
    // True when the prompt was raised by a Copy tap, so the subtitle and the refusals say "copy":
    // answering a Copy with "reveal this field" described an act the user never asked for.
    forCopy: Boolean = false,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    // RevealAuth.authenticate needs an actual CharSequence (it feeds a biometric prompt, not a
    // Composable), so these two are resolved here, at composition, and captured for the suspend
    // block below — the same pattern as the clipboard label in ItemEditScreen's SecretField.
    val promptTitle = stringResource(R.string.itemedit_reveal_prompt_title)
    val promptSubtitle = stringResource(revealPromptSubtitle(forCopy))
    LaunchedEffect(pendingField) {
        pendingField ?: return@LaunchedEffect
        val activity = context as? FragmentActivity
        when {
            activity == null ->
                viewModel.onRevealDenied(R.string.itemedit_reveal_no_activity)
            !RevealAuth.isAvailable(context) ->
                // No enrolled biometric and no device lock: say so instead of silently revealing.
                viewModel.onRevealDenied(noAuthMessage(forCopy = forCopy))
            else -> {
                when (RevealAuth.authenticate(activity, promptTitle, promptSubtitle)) {
                    RevealAuth.RevealOutcome.Success -> viewModel.onRevealAuthenticated()
                    RevealAuth.RevealOutcome.Unavailable ->
                        viewModel.onRevealDenied(noAuthMessage(forCopy = forCopy))
                    else -> viewModel.onRevealDenied()
                }
                // The prompt is a window of its own, and closing it hands focus back to the first
                // focusable node in ours — the Back chevron, which then wears the jade focus ring
                // and reads as the screen's primary control while the user is looking at the secret
                // they just unlocked (F-27, 2026-09-15 visual pass). Nothing put focus there and
                // nothing on the way back takes it away, so this does.
                //
                // Only on the branch that actually raised a prompt: the two above never left this
                // window, so focus is wherever the user put it and clearing it would be taking it
                // from them. `force` because the holder is a plain focusable, not a text field that
                // would release focus on its own.
                focusManager.clearFocus(force = true)
            }
        }
    }
}

/**
 * Which refusal to show when the device cannot challenge the user.
 *
 * "Set a screen lock or biometrics" is a dead-end instruction on API 28 and 29 (3C-1, 2026-09-20).
 * `RevealAuth.allowedAuthenticators` asks for `BIOMETRIC_STRONG` alone below API 30, because
 * androidx.biometric rejects the `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` pair there — so on the two
 * lowest supported releases **no** screen lock can ever satisfy this gate, and a phone without a
 * Class 3 sensor was told to go and set one. Measured on emulator-5556 (API 29): the message read
 * "Set a screen lock or biometrics to reveal this field." while `locksettings verify --old 1234`
 * answered "Lock credential verified successfully".
 *
 * Copy only. The authenticator policy is `:core:ui`'s and is deliberate — Class 3 alone rather than
 * `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`, which is the other pairing 28/29 accepts and would relax a
 * reveal gate to a Class 2 biometric. `RevealAuth` states that trade and its cost; this only stops
 * the UI misreporting it.
 *
 * The boundary is spelled out again here because `allowedAuthenticators` is `internal` to
 * `:core:ui` and cannot be read from this module. `RevealAuthApiBoundaryTest` asserts the two still
 * say the same thing, so they cannot drift apart silently.
 *
 * [forCopy] picks the same sentence with "copy" in place of "reveal": a Copy tap raises this gate
 * too, and a refusal about revealing is about an act the user did not ask for.
 *
 * [sdkInt] is a parameter so this is testable off a device.
 */
internal fun noAuthMessage(sdkInt: Int = Build.VERSION.SDK_INT, forCopy: Boolean = false): Int =
    if (sdkInt < Build.VERSION_CODES.R) {
        if (forCopy) {
            R.string.itemedit_copy_no_biometric_available
        } else {
            R.string.itemedit_reveal_no_biometric_available
        }
    } else {
        if (forCopy) {
            R.string.itemedit_copy_no_auth_available
        } else {
            R.string.itemedit_reveal_no_auth_available
        }
    }

/** The biometric prompt's subtitle: names the act the user asked for, Copy or Show. */
internal fun revealPromptSubtitle(forCopy: Boolean): Int =
    if (forCopy) R.string.itemedit_copy_prompt_subtitle else R.string.itemedit_reveal_prompt_subtitle
