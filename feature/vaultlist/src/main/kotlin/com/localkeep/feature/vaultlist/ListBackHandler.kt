package com.localkeep.feature.vaultlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * The vault list's one Back handler. See [resolveListBack] for the order it undoes things in.
 *
 * **With the keyboard up, the press only puts the keyboard away**, query and focus left where they
 * are — what the keyboard's own Back does, and what this screen's Back did until the handler went
 * in (F-08). It stopped being true by registration order rather than by design: the window keeps
 * one stack of back callbacks and the newest registration wins, the keyboard registers when it
 * opens, and this handler is enabled only once there is something to undo. So the first letter of
 * a search enabled it *after* the keyboard had registered and put it on top — one Back cleared
 * the query under a keyboard that stayed up (device pass, API 36, 2026-10-05). Typing into a
 * search that was already there, the other order held and the keyboard won.
 *
 * Checked here rather than left to the order, because nothing this screen controls decides the
 * order. When the keyboard's own callback is on top this handler never sees the press, and the
 * result is the same.
 */
@Composable
internal fun ListBackHandler(action: ListBackAction?, onBack: () -> Unit) {
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(enabled = action != null) {
        if (keyboardUp && keyboard != null) keyboard.hide() else onBack()
    }
}
