package com.localkeep.autofill

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The save-confirm screen, recreated mid-save, and its titles for a login with no username.
 *
 * Recreation (2026-10-02): the save consumes its pending entry before the write and finishes the
 * write in a coroutine that outlives the screen. A rotation, fold or dark-mode switch during that
 * write recreated the activity, found no entry, and toasted "this save expired" — right before the
 * old instance toasted "Saved". The activity needs Hilt to construct, so the decision it now takes
 * from its saved state is pinned here; the instance-state round trip itself is not run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveConfirmRecreationTest {

    @Test
    fun `a screen recreated after its save started does not report the save expired`() {
        val saved = Bundle().apply { putBoolean(SaveConfirmActivity.STATE_RESOLVED, true) }

        assertTrue(SaveConfirmActivity.saveAlreadyResolved(saved))
    }

    @Test
    fun `a first launch, or a recreation before any save, still reports a real expiry`() {
        assertFalse(SaveConfirmActivity.saveAlreadyResolved(null))
        assertFalse(SaveConfirmActivity.saveAlreadyResolved(Bundle()))
        assertFalse(
            SaveConfirmActivity.saveAlreadyResolved(
                Bundle().apply { putBoolean(SaveConfirmActivity.STATE_RESOLVED, false) },
            ),
        )
    }

    @Test
    fun `the no-username titles name the site once, in every shipped language`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        for (locale in listOf("en", "fr", "de", "es", "pt")) {
            val config = android.content.res.Configuration(base.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(locale))
            val context = base.createConfigurationContext(config)
            for (res in listOf(
                R.string.autofill_save_confirm_password_title_no_username,
                R.string.autofill_save_confirm_password_title_update_no_username,
            )) {
                val title = context.getString(res, "example.com")
                assertTrue("example.com" in title, "[$locale] $title")
                assertFalse("%" in title, "[$locale] a placeholder was left unfilled: $title")
            }
        }
    }
}
