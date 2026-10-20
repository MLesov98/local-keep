package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.common.PendingSecret
import com.localkeep.core.crypto.KeystoreManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import org.junit.runner.RunWith

/**
 * A generated password no editor collected is gone the instant the vault reports itself locked.
 *
 * `PendingSecret` is the in-memory handover from the generator to the item editor: the user taps
 * "Save as a new login", and the editor consumes it on arrival. If they lock instead — auto-lock,
 * screen-off, panic — nothing consumes it, and clearing it was an `appScope` collector on
 * `Dispatchers.Default` that observes `Locked` *after* the fact. So `lock()` zeroized the DEK,
 * published `Locked`, and the plaintext password was still reachable in the process until a
 * coroutine happened to get scheduled.
 *
 * A `String` cannot be wiped, so dropping the reference is the whole of what can be done — but it
 * has to happen in the same critical section as the DEK, which is what this asserts.
 *
 * Since 2026-09-15 the handover also carries the entropy of the draw (F-29). It is offered here so
 * the whole `Offer` is what the lock has to drop: a bit count left behind would attach itself to the
 * next password the editor adopts and describe a different string.
 */
@RunWith(AndroidJUnit4::class)
class PendingSecretClearedOnLockTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @AfterTest
    fun cleanup() {
        PendingSecret.clear()
    }

    @Test
    fun lock_dropsAnUncollectedGeneratedPassword() {
        val lockManager = VaultLockManager(context, KeystoreManager(context))
        PendingSecret.offer("correct-horse-battery-staple", entropyBits = 77.5)

        lockManager.lock()

        // Synchronously, on the calling thread — no coroutine has had a chance to run yet, which is
        // exactly the window this closes.
        assertNull(PendingSecret.take(), "a generated password outlived the lock")
    }
}
