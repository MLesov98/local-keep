package com.localkeep.core.ui

import android.content.ActivityNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S3-05, 2026-10-02: `rememberExternalPickerLauncher` caught a failed launch only to rethrow it
 * "to whoever handles a missing picker activity", and no caller does. A device or profile with no
 * handler for the document or photo picker therefore crashed the process on the tap, losing any
 * unsaved form behind it.
 *
 * The launch is driven through [launchExternalPicker], the plain function the composable is now a
 * thin wrapper over. Robolectric only because `ActivityNotFoundException` is an Android class.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalPickerUnavailableTest {

    private class Calls {
        var opened = 0
        var closed = 0
        var unavailable = 0
    }

    private fun run(calls: Calls, launch: (String) -> Unit) = launchExternalPicker(
        input = "*/*",
        launch = launch,
        onOpened = { calls.opened++ },
        onClosed = { calls.closed++ },
        onUnavailable = { calls.unavailable++ },
    )

    @Test
    fun `a missing picker activity is reported instead of crashing`() {
        val calls = Calls()

        run(calls) { throw ActivityNotFoundException("no handler") }

        assertEquals(1, calls.unavailable, "the user must be told why the tap did nothing")
    }

    @Test
    fun `a missing picker activity still balances the lock manager counter`() {
        val calls = Calls()

        run(calls) { throw ActivityNotFoundException("no handler") }

        // Nothing opened, so no result callback will ever call `onClosed` for this launch.
        assertEquals(1, calls.opened)
        assertEquals(1, calls.closed)
    }

    /** Control: a picker that opens is neither closed early nor reported unavailable. */
    @Test
    fun `a picker that opens leaves the counter to its result callback`() {
        val calls = Calls()
        var launched: String? = null

        run(calls) { launched = it }

        assertEquals("*/*", launched)
        assertEquals(1, calls.opened)
        assertEquals(0, calls.closed)
        assertEquals(0, calls.unavailable)
    }

    /** Control: only a missing picker is swallowed; any other failure still surfaces, balanced. */
    @Test
    fun `any other launch failure still propagates after balancing the counter`() {
        val calls = Calls()

        assertFailsWith<SecurityException> {
            run(calls) { throw SecurityException("denied") }
        }

        assertEquals(1, calls.closed)
        assertEquals(0, calls.unavailable)
    }
}
