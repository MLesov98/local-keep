package com.localkeep.core.vault

/**
 * A borrow of the live session DEK, handed to UI that outlives any single read.
 *
 * The media viewer used to be given [VaultLockManager]'s own key array and to keep it for as long
 * as it was on screen, and `lock()` zeroes that array in place — so an auto-lock, a screen-off or a
 * panic lock landing during a decrypt pulled the key out mid-operation. The obvious repair is a
 * copy per screen, and it is the wrong one: it multiplies the number of live copies of the key,
 * which is the one number the rest of this module works to keep at one. A handle instead scopes
 * every read, so the wipe waits for the read rather than racing it, and nothing new is copied.
 *
 * Holding a handle is not a claim that the vault is still open. [use] returns null once the session
 * it was taken from has ended, and the caller must read that as "there is nothing left to show" —
 * a lock routes the UI away in the same instant.
 */
class SessionKey internal constructor(
    private val manager: VaultLockManager,
    private val sessionToken: Long?,
) {
    /**
     * Runs [block] with the live DEK, or returns null without running it if the session has ended.
     * The DEK belongs to the manager: read it, never wipe or mutate it.
     */
    fun <T> use(block: (ByteArray) -> T): T? = manager.withSessionDek(sessionToken, block)
}
