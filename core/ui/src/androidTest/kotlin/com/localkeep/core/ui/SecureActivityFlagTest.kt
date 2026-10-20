package com.localkeep.core.ui

import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FLAG_SECURE is the mechanism behind two claims this app makes to users — screenshots are blocked
 * and the app-switcher preview is obscured — and until 2026-08-10 nothing in the repo asserted it
 * at runtime, in any direction.
 *
 * `SecureActivity` now sets the flag only when `R.bool.lk_obscure_screen` resolves true, so that a
 * `debug` app build can be photographed while UI work is verified. This file covers the secure
 * branch: an instrumentation test for a *library* runs against the library's own variant, which
 * carries `core/ui/src/main/res/values/bools.xml` and therefore `true`. The insecure branch is
 * covered by `ScreenSecurityDebugTest` in `:app`, whose debug variant merges the override.
 *
 * Neither test can speak for a release APK — instrumentation only ever observes the variant it is
 * running in. That guarantee is `checkScreenSecurity` in the root build script, which reads the
 * merged release resources of both flavours. Do not let these tests stand in for it.
 */
@RunWith(AndroidJUnit4::class)
class SecureActivityFlagTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @Test
    fun setsFlagSecureWhenTheBoolIsTrue() {
        ActivityScenario.launch(SecureActivityUnderTest::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    activity.resources.getBoolean(R.bool.lk_obscure_screen),
                    "This module's own variant must carry the secure default.",
                )
                assertTrue(
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
                    "SecureActivity must set FLAG_SECURE when lk_obscure_screen is true.",
                )
            }
        }
    }

    /**
     * The tap-jacking defence lives on the same method and is deliberately not gated. Someone
     * tidying the `if` could easily pull this inside it; the app would still block screenshots and
     * would quietly start accepting touches delivered through an overlay.
     */
    @Test
    fun filterTouchesStaysOnRegardlessOfTheBool() {
        ActivityScenario.launch(SecureActivityUnderTest::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<View>(android.R.id.content)
                assertTrue(
                    content.filterTouchesWhenObscured,
                    "filterTouchesWhenObscured must be on in every build.",
                )
            }
        }
    }

    /**
     * This window's fields are withheld from the autofill structure walk.
     *
     * **Read the scope before trusting this test.** It proves the flag is set on a real window, and
     * the flag is what stops a bound service receiving this window's fields and their contents. It
     * does **not** prove that no autofill session opens: Compose reports its text fields as virtual
     * children of the AndroidComposeView, and `AndroidAutofillManager.onFocusChanged` gates that on
     * the node's semantics actions alone, never on `importantForAutofill`. Focusing a secret well
     * therefore still opens a session with the bound service — measured 5/5 on API 29 against
     * Google's provider on 2026-09-12. That residual is accepted and documented in
     * docs/THREAT-MODEL.md; it is not fixable in Compose 1.11.3.
     *
     * This test used to be called `noWindowIsOfferedToTheAutofillFramework` and asserted exactly
     * what it asserts now while claiming the broader property. It passed 4/4 on the same emulator
     * where the behaviour failed, because a bare SecureActivity hosts no Compose content — a green
     * test sitting on top of a live finding. Renamed rather than deleted so the flag stays guarded.
     *
     * This is **not** covered by FLAG_SECURE and must not be folded into the `if` above. An autofill
     * *save* session is not gated by the window flag at all: on the shipped release build the OS was
     * raising "Save password to LocalKeep?" over the vault list after every unlock, having tracked
     * the master-password field as it was typed. Asserted on a real window rather than on the source,
     * because the value is set from a posted runnable and a source-text assertion would pass over a
     * runnable that never ran — the exact failure that let the import wizard's dead Discard button
     * ship green (see ImportPreviewDiscardGuardTest).
     */
    @Test
    fun theContentViewIsExcludedFromTheAutofillStructureWalk() {
        ActivityScenario.launch(SecureActivityUnderTest::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<View>(android.R.id.content)
                assertEquals(
                    View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                    content.importantForAutofill,
                    "SecureActivity must exclude its content view from the autofill structure " +
                        "walk, in every build, so a bound service never receives this window's " +
                        "fields or their contents.",
                )
            }
        }
    }

    /**
     * The library default is the fail-closed half of the design: every failure mode that is not an
     * explicit override — a typo'd name, a deleted file, a mis-merged source set — lands here.
     */
    @Test
    fun theLibraryDefaultIsSecure() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(
            context.resources.getBoolean(R.bool.lk_obscure_screen),
            "core/ui must ship lk_obscure_screen = true.",
        )
    }
}
