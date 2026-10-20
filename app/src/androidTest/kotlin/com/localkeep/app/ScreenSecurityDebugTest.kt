package com.localkeep.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.R
import kotlin.test.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The other half of `SecureActivityFlagTest` (`:core:ui`), which asserts the secure branch against
 * the library's own variant. Instrumentation for `:app` runs against the **debug** variant, which
 * merges `app/src/debug/res/values/bools.xml` — so this is the one place the override can be
 * observed working.
 *
 * If this test starts failing, the debug bypass has stopped applying and `adb exec-out screencap`
 * is black again. That is a broken development workflow, not a security problem. The security
 * problem is the opposite direction, and it is caught by `checkScreenSecurity` in the root build
 * script — which reads the merged **release** resources and is the only check that can speak for a
 * shipped APK. Neither of these tests can.
 */
@RunWith(AndroidJUnit4::class)
class ScreenSecurityDebugTest {

    @Test
    fun debugBuildsDropTheScreenshotBlock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(
            context.resources.getBoolean(R.bool.lk_obscure_screen),
            "app/src/debug/res/values/bools.xml must override lk_obscure_screen to false, or UI " +
                "work cannot be photographed. Release is unaffected — see checkScreenSecurity.",
        )
    }
}
