package com.localkeep.feature.vaultlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * A modifier that takes focus, and raises the keyboard, when the field it is on first composes.
 * For a field that appears because the user asked to type into it.
 */
@Composable
internal fun rememberFocusOnArrival(): Modifier {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focus) {
        // After the field is attached; a request made before that throws.
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    return Modifier.focusRequester(focus)
}
