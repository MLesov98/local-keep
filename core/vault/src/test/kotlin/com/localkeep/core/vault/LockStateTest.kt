package com.localkeep.core.vault

import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class LockStateTest {
    private val timestamp = Instant.EPOCH
    private val secretVault = Vault(
        items = listOf(
            VaultItem(
                id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                title = "private title",
                createdAt = timestamp,
                updatedAt = timestamp,
                content = Note("private body"),
            ),
        ),
        createdAt = timestamp,
        modifiedAt = timestamp,
    )

    @Test
    fun `unlocked state uses structural byte-array equality`() {
        val first = LockState.Unlocked(ByteArray(32) { it.toByte() }, secretVault)
        val same = LockState.Unlocked(ByteArray(32) { it.toByte() }, secretVault)
        val different = LockState.Unlocked(ByteArray(32) { (it + 1).toByte() }, secretVault)

        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, different)
    }

    @Test
    fun `unlocked state rejects malformed DEK and hides secret content in text`() {
        assertFailsWith<IllegalArgumentException> {
            LockState.Unlocked(ByteArray(31), secretVault)
        }

        val text = LockState.Unlocked(ByteArray(32), secretVault).toString()
        assertFalse("private title" in text)
        assertFalse("private body" in text)
    }
}
