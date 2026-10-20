package com.localkeep.credential

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotSame
import kotlinx.coroutines.runBlocking

class PasskeyDekLifetimeTest {

    @Test
    fun `background DEK copy is distinct and wiped after operation`() = runBlocking {
        val sessionDek = byteArrayOf(1, 2, 3, 4)
        var operationDek: ByteArray? = null

        withSessionDekCopy(sessionDek) { copy ->
            operationDek = copy
            assertNotSame(sessionDek, copy)
            assertContentEquals(sessionDek, copy)
        }

        assertContentEquals(byteArrayOf(0, 0, 0, 0), operationDek!!)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), sessionDek)
    }
}
