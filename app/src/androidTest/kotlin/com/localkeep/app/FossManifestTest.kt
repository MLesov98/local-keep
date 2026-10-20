package com.localkeep.app

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** No-network guardrail, mechanism 1 (§I): the merged manifest of an installed `foss` build must
 *  never declare INTERNET. Mechanism 2 (checkNoNetworkingDeps, root build.gradle.kts) is the
 *  load-bearing build-graph check; this is the runtime cross-check against the actual installed APK. */
@RunWith(AndroidJUnit4::class)
class FossManifestTest {
    @Test fun foss_flavor_declares_no_internet() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue("foss-only check", ctx.packageName.endsWith(".foss"))
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val perms = info.requestedPermissions?.toList().orEmpty()
        assertFalse(perms.contains(android.Manifest.permission.INTERNET), "foss must not request INTERNET; got $perms")
    }

    /**
     * The whole permission set, not just INTERNET.
     *
     * Checking one permission is what let the foss build ship asking for seven: POST_NOTIFICATIONS,
     * WAKE_LOCK, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED and FOREGROUND_SERVICE all arrived
     * through androidx.work's own manifest — carried in for one daily backup reminder — while every
     * doc in the repo called this build "zero-permission". A library can add a `uses-permission`
     * without a line of app source changing, so the guard has to be an allowlist, not a denylist.
     *
     * The build-time twin is `checkFlavorPermissions` in the root build.gradle.kts; this one runs
     * against the APK that actually got installed.
     *
     * CAMERA joined 2026-07-29 for authenticator QR scanning; the two foreground location
     * permissions joined with the coordinate item type. The air gap is the absence of INTERNET,
     * which this class still pins in the test above — a coordinate is stored in the encrypted vault
     * and goes nowhere.
     */
    @Test fun foss_flavor_requests_only_biometric_unlock_and_the_scanner_camera() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue("foss-only check", ctx.packageName.endsWith(".foss"))
        // The first two come from androidx.biometric, for the unlock the user opts into; CAMERA is
        // this app's own, declared for QR scanning and gated by a runtime prompt per use.
        val allowed = setOf(
            "android.permission.USE_BIOMETRIC",
            "android.permission.USE_FINGERPRINT",
            "android.permission.CAMERA",
            // Save current location, added with schema 6. Foreground only and requested at the
            // capture tap. ACCESS_BACKGROUND_LOCATION and FOREGROUND_SERVICE are absent from this
            // set on purpose: there is no tracking, no history and no scheduled work behind the
            // feature, so either one appearing here would be a real change in what the app can do.
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
        )
        val requested = ctx.packageManager
            .getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toList().orEmpty()
            // Synthesised by AGP per package for its own broadcast plumbing; grants nothing.
            .filterNot { it.endsWith("DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }
            // Contributed by the instrumentation APK, which is not what ships.
            .filterNot { it == "android.permission.REORDER_TASKS" }
        val unexpected = requested - allowed
        assertTrue(
            unexpected.isEmpty(),
            "foss must request only $allowed; it also requests $unexpected",
        )
        // Positive half of the pin: an allowlist alone would pass just as happily if the scanner
        // silently stopped shipping in foss, which is the regression this feature exists against.
        assertTrue(
            requested.contains("android.permission.CAMERA"),
            "foss must declare CAMERA — QR scanning ships in both flavours; got $requested",
        )
    }
}
