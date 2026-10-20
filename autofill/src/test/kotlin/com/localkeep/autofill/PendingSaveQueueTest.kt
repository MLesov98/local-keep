package com.localkeep.autofill

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.LockState
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A save the platform accepted but never got to show survives to the next unlock.
 *
 * The platform calls `onSaveRequest` only after the user has accepted its own "Save password to
 * LocalKeep?" sheet, so every proposal in the store is one the user said yes to. When the vault is
 * locked at that moment Android can refuse [SaveConfirmActivity]'s PendingIntent as a background
 * activity launch (`BAL_BLOCK`, result code 102, measured on API 35 on 2026-09-17): nothing runs,
 * nothing is shown, and the ordinary two-minute TTL then wiped the credential in silence.
 *
 * Every assertion below is about the window and the routing, never about bypassing the lock — the
 * offer is made after an unlock this code neither performs nor influences.
 */
@RunWith(RobolectricTestRunner::class)
class PendingSaveQueueTest {

    /** Driven by hand for the same reason [PendingSaveStoreTest] drives it: a TTL is only testable
     *  on a clock the test can move. */
    private var elapsedRealtimeMs = 30_000L
    private lateinit var previousClock: () -> Long

    @Before
    fun installClock() {
        previousClock = PendingSaveStore.elapsedRealtimeMs
        PendingSaveStore.elapsedRealtimeMs = { elapsedRealtimeMs }
    }

    @After
    fun restoreClock() {
        PendingSaveStore.discardAwaitingUnlock()
        PendingSaveStore.elapsedRealtimeMs = previousClock
    }

    private fun put(password: CharArray, awaitingUnlock: Boolean) = PendingSaveStore.put(
        PendingSaveStore.Entry(
            username = "splituser17",
            password = password,
            webDomain = "example.com",
            packageName = "com.example.browser",
            existingItemId = null,
            awaitingUnlock = awaitingUnlock,
        ),
    )

    private fun unlocked(isDuress: Boolean) = LockState.Unlocked(
        dek = ByteArray(32),
        vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
        isDuress = isDuress,
    )

    /**
     * The whole of F-3. Four minutes is past the ordinary 120-second window and inside the queued
     * one: long enough to walk back to LocalKeep, mistype a master password once, and wait out
     * Argon2 twice.
     *
     * Red before the fix in two independent ways — `awaitingUnlock` did not exist, and the entry
     * was expired by the single TTL — which is why it also asserts the password is intact rather
     * than only that a token comes back.
     */
    @Test
    fun `a save captured while locked is still offered four minutes later`() {
        val password = "hunter17".toCharArray()
        val token = put(password, awaitingUnlock = true)

        elapsedRealtimeMs += 240_000L

        assertEquals(token, PendingSaveStore.awaitingUnlock())
        assertNotNull("the queued save was expired by the ordinary TTL", PendingSaveStore.peek(token))
        assertEquals("hunter17", password.concatToString())
    }

    /**
     * The control: the ordinary window is untouched.
     *
     * A save captured against an *open* vault is confirmed there and then, so it neither needs the
     * longer window nor belongs in a queue whose only trigger is an unlock that will never come.
     * Without this, "keeps the short TTL for the common path" would be a claim nothing measures.
     */
    @Test
    fun `a save captured while unlocked keeps the two-minute window and never queues`() {
        val password = "hunter17".toCharArray()
        val token = put(password, awaitingUnlock = false)

        assertNull(PendingSaveStore.awaitingUnlock())

        elapsedRealtimeMs += 240_000L

        assertNull("the ordinary TTL was lengthened for every save", PendingSaveStore.peek(token))
        assertArrayEquals(CharArray(password.size), password)
    }

    /** The queue is a hand-off, not a store: past its own window the credential is gone. */
    @Test
    fun `a queued save expires and is wiped at its own deadline`() {
        val password = "hunter17".toCharArray()
        val token = put(password, awaitingUnlock = true)

        elapsedRealtimeMs += 300_001L

        assertNull(PendingSaveStore.awaitingUnlock())
        assertNull(PendingSaveStore.peek(token))
        assertArrayEquals(CharArray(password.size), password)
    }

    /**
     * A decoy session must not receive a real capture — it would file the credential in the wrong
     * vault and name, on screen, a site the real user visited minutes ago.
     */
    @Test
    fun `a duress unlock discards the queue and wipes it`() {
        val password = "hunter17".toCharArray()
        put(password, awaitingUnlock = true)

        assertEquals(PendingSaveAction.DISCARD, PendingSaveQueue.actionFor(unlocked(isDuress = true)))
        PendingSaveQueue.discardAll()

        assertNull(PendingSaveStore.awaitingUnlock())
        assertArrayEquals(CharArray(password.size), password)
    }

    @Test
    fun `a real unlock offers the queued save`() {
        val password = "hunter17".toCharArray()
        put(password, awaitingUnlock = true)

        assertEquals(PendingSaveAction.OFFER, PendingSaveQueue.actionFor(unlocked(isDuress = false)))
        assertNotNull(PendingSaveQueue.offerIntent(context()))
    }

    /** Nothing is offered while the vault is shut, whatever is waiting. The lock is never anticipated. */
    @Test
    fun `a locked vault is offered nothing`() {
        put("hunter17".toCharArray(), awaitingUnlock = true)

        assertEquals(PendingSaveAction.NONE, PendingSaveQueue.actionFor(LockState.Locked))
    }

    /**
     * The launch re-reads the queue because the caller waits for MainActivity to be resumed first,
     * and that wait is unbounded — an expired token would open the confirmation on its own
     * "this save expired" dead end, about something the user was never shown.
     */
    @Test
    fun `an offer that expires before the launch produces no intent`() {
        put("hunter17".toCharArray(), awaitingUnlock = true)
        assertEquals(PendingSaveAction.OFFER, PendingSaveQueue.actionFor(unlocked(isDuress = false)))

        elapsedRealtimeMs += 300_001L

        assertNull(PendingSaveQueue.offerIntent(context()))
    }

    /** A save the confirmation already resolved is not offered a second time. */
    @Test
    fun `a consumed save leaves the queue`() {
        val password = "hunter17".toCharArray()
        val token = put(password, awaitingUnlock = true)

        assertNotNull(PendingSaveStore.consume(token))
        password.fill('\u0000')

        assertNull(PendingSaveStore.awaitingUnlock())
        assertEquals(PendingSaveAction.NONE, PendingSaveQueue.actionFor(unlocked(isDuress = false)))
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()
}
