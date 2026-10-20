package com.localkeep.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Opt-in, test-only fixture writer for the locale-neutral viewer Maestro flow. */
@RunWith(AndroidJUnit4::class)
class MediaViewerFixtureSeeder {
    @Test
    fun seedMediaViewerFixture() {
        val password = InstrumentationRegistry.getArguments().getString("lkPassword")
        assumeTrue("no lkPassword argument — not seeding", password != null)
        VaultTestDriver.createMediaViewerFixture(
            InstrumentationRegistry.getInstrumentation().targetContext,
            checkNotNull(password).toCharArray(),
        )
    }
}
