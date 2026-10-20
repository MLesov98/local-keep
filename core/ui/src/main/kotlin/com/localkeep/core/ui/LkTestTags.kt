package com.localkeep.core.ui

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Stable, language-independent handles for the automated locale sweep.
 *
 * Every device pass so far has driven the app by matching the text on screen, which cannot be done
 * once per flow and reused: the text is different in each of the five languages, and under the
 * `en-XA` pseudolocale it is not even ASCII — "Trash" renders as `[Ţŕåšĥ one]`, with nothing
 * matchable left in it. So the same walk had to be re-driven by hand for every language, and that
 * cost is the reason Run 13 closed with seven screens never opened in any locale.
 *
 * A test tag is the standard answer, and it is the *only* attribute of a control that is the same
 * in every language. `tools/i18n-sweep.py` navigates by these; nothing else in the app reads them.
 *
 * **Debuggable builds only.** Compose exposes a test tag to the accessibility tree as a
 * `resource-id`, which is what makes it visible to Maestro and to `uiautomator` — and would equally
 * expose the app's internal control names to any accessibility service on a shipped device. There
 * is no need to find out whether that matters: the sweep runs on a debug build, so the flag is
 * switched on for debug builds and nothing else. Checked against the *installed package* rather
 * than `BuildConfig`, so a release APK is false at runtime however it was assembled.
 */
@Composable
fun Modifier.lkExposeTestTags(): Modifier {
    val context = LocalContext.current
    val debuggable = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    // No wrapper node in either branch. An extra Box present only in debug builds would change the
    // very measurements the sweep exists to take — the whole method rests on comparing pixel bounds
    // across locales, and a debug-only layout difference would quietly invalidate all of them.
    return if (debuggable) semantics { testTagsAsResourceId = true } else this
}

/**
 * Names a control for the sweep, and only for the sweep.
 *
 * Applied at the few places navigation passes through — a drawer row, a bottom-bar tab, the FAB, a
 * dialog's two buttons — rather than sprinkled over the app. Each of those is a single composable
 * that every instance goes through, so the tag is derived from data already in hand (a route, an
 * enum name) and no call site has to remember to pass one.
 */
fun Modifier.lkTag(tag: String): Modifier = testTag(tag)
