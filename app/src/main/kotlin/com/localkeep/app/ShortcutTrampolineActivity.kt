package com.localkeep.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.localkeep.app.navigation.AppShortcuts

/**
 * Where the two long-press launcher shortcuts land. It hands the shortcut to [MainActivity] and
 * finishes without drawing anything.
 *
 * Android launches a static shortcut with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`, and a
 * shortcut declared in XML cannot change that. Aimed straight at [MainActivity], the clear destroyed
 * the running activity, and with it an open item editor and everything typed into it — no prompt,
 * no "discarded" notice (device pass, 2026-10-04). The rule that a shortcut waits until the editor
 * is closed ([com.localkeep.app.navigation.shortcutRoutesToVaultList]) never got to apply, because
 * the screen it protects was already gone.
 *
 * This activity has its own task affinity, so the clear lands on that empty task instead.
 * [MainActivity] is `singleTask`, so the hand-off below reaches the running instance through
 * `onNewIntent`, or starts it if the app was not running. Only the two shortcut actions are passed
 * on; anything else just finishes.
 */
class ShortcutTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        if (AppShortcuts.targetOf(action) != null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }
}
