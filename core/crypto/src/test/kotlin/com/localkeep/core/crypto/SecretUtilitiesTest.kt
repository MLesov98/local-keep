package com.localkeep.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretUtilitiesTest {
    @Test
    fun `password UTF-8 conversion handles non-ASCII without changing input`() {
        val password = charArrayOf('p', '\u0105', '\ud83d', '\udd10')
        val original = password.copyOf()

        val bytes = Passwords.toUtf8(password)

        assertContentEquals(original, password)
        assertEquals("70c485f09f9490", bytes.toHex())
    }

    @Test
    fun `secret bytes wipe once and reject access after close`() {
        val raw = byteArrayOf(1, 2, 3)
        val secret = SecretBytes(raw)

        assertEquals(3, secret.size)
        assertContentEquals(raw, secret.bytes)
        secret.close()
        secret.close()

        assertContentEquals(byteArrayOf(0, 0, 0), raw)
        assertFailsWith<IllegalStateException> { secret.bytes }
    }

    @Test
    fun `zeroizer handles arrays and nulls`() {
        val bytes = byteArrayOf(1)
        val chars = charArrayOf('x')
        Zeroizer.wipe(bytes)
        Zeroizer.wipe(chars)
        Zeroizer.wipe(null as ByteArray?)
        Zeroizer.wipe(null as CharArray?)
        assertContentEquals(byteArrayOf(0), bytes)
        assertContentEquals(charArrayOf('\u0000'), chars)
    }

    @Test
    fun `secure random validates length`() {
        assertEquals(0, SecureRandomProvider.nextBytes(0).size)
        assertEquals(32, SecureRandomProvider.nextBytes(32).size)
        assertFailsWith<IllegalArgumentException> { SecureRandomProvider.nextBytes(-1) }
    }

    @Test
    fun `constant-time facade compares content`() {
        assertTrue(ConstantTime.equals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(ConstantTime.equals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(ConstantTime.equals(byteArrayOf(1), byteArrayOf(1, 0)))
    }

    @Test
    fun `KDF params enforce hard floors`() {
        assertFailsWith<IllegalArgumentException> { KdfParams(19_455, 2, 1) }
        assertFailsWith<IllegalArgumentException> { KdfParams(19_456, 1, 1) }
        assertFailsWith<IllegalArgumentException> { KdfParams(19_456, 2, 0) }
    }
}
