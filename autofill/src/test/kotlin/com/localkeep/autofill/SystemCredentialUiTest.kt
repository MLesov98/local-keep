package com.localkeep.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCredentialUiTest {
    @Test
    fun skipsDeviceLockScreens() {
        assertTrue(SystemCredentialUi.shouldSkipSave("com.android.settings", webDomain = null))
        assertTrue(SystemCredentialUi.shouldSkipSave("com.android.systemui", webDomain = null))
    }

    @Test
    fun keepsOrdinaryApps() {
        assertFalse(SystemCredentialUi.shouldSkipSave("com.example.bank", webDomain = null))
        assertFalse(SystemCredentialUi.shouldSkipSave(null, webDomain = null))
    }

    @Test
    fun keepsWebPagesEvenFromAListedPackage() {
        // A site loaded in a WebView owns its own credential; the host package is irrelevant there.
        assertFalse(
            SystemCredentialUi.shouldSkipSave("com.android.settings", webDomain = "example.com"),
        )
    }

    @Test
    fun matchesPackagesExactlyRatherThanByPrefix() {
        // "com.android.settingsfoo" is a third-party app squatting a similar name, not the OS.
        assertFalse(
            SystemCredentialUi.shouldSkipSave("com.android.settingsfoo", webDomain = null),
        )
    }
}
