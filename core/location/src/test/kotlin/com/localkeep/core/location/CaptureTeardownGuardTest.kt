package com.localkeep.core.location

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every `removeUpdates` in the capture provider is guarded.
 *
 * The teardown paths run inside `Handler`-posted callbacks, not inside the suspend function's own
 * call stack, so an exception thrown there is not caught by the enclosing `withTimeout` — it
 * reaches the thread's default uncaught-exception handler, which on stock Android is a crash. Two
 * of the six call sites were left bare: `finish` (the ordinary success path, taken by every early
 * settle and every deadline that has a fix) and `deadline`'s empty branch. The other four were
 * already wrapped, by the same author, against the same API.
 *
 * A source assertion, and the same reasoning as `VaultListMainThreadWorkTest`: the listener is an
 * anonymous object built inside a `suspendCancellableCoroutine` around a platform `LocationManager`
 * and cannot be constructed on the JVM, so what can be checked cheaply and exactly is that the
 * guard is written where it has to be.
 */
class CaptureTeardownGuardTest {

    private val source =
        File("src/main/kotlin/com/localkeep/core/location/AndroidCurrentLocationProvider.kt")

    private fun lines(): List<String> {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readLines()
    }

    @Test
    fun `every removeUpdates call is wrapped in runCatching`() {
        val bare = lines()
            .withIndex()
            .filter { (_, line) -> ".removeUpdates(" in line }
            .filterNot { (_, line) -> "runCatching {" in line }
        assertTrue(
            bare.isEmpty(),
            "removeUpdates must not be able to abort a teardown. These call sites run inside " +
                "Handler-posted callbacks, outside the withTimeout that would otherwise catch a " +
                "throw, so an unguarded one skips the remaining unregistrations and the " +
                "continuation resume. Unguarded at: " +
                bare.joinToString { (i, line) -> "line ${i + 1}: ${line.trim()}" },
        )
    }

    /**
     * Control. This held before the fix and must keep holding: the test is reading the real file
     * and finding the real call sites, not passing because it matched nothing.
     */
    @Test
    fun `the capture listener still has the call sites this test is about`() {
        val sites = lines().count { ".removeUpdates(" in it }
        assertTrue(
            sites >= 5,
            "expected at least the five capture-listener teardown paths (finish, " +
                "finishWithFallback, deadline's empty branch, invokeOnCancellation, the " +
                "no-provider bail); found $sites. Fewer means this file was restructured and the " +
                "guard assertion above is reading the wrong thing — or nothing at all.",
        )
    }
}
