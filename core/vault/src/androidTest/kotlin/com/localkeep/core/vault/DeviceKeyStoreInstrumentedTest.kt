package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.WrappedDek
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceKeyStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @AfterTest
    fun cleanup() {
        DeviceKeyStore.delete(context)
    }

    @Test
    fun saveLoadDelete_roundTripsExactWrappedDek() {
        val expected = wrappedDek()

        DeviceKeyStore.save(context, expected)
        assertEquals(expected, DeviceKeyStore.load(context))

        DeviceKeyStore.delete(context)
        assertNull(DeviceKeyStore.load(context))
    }

    @Test
    fun save_overwritesPriorValue() {
        val first = wrappedDek()
        val second = wrappedDek()

        DeviceKeyStore.save(context, first)
        DeviceKeyStore.save(context, second)

        assertEquals(second, DeviceKeyStore.load(context))
    }

    @Test
    fun malformedFile_returnsNull() {
        File(context.filesDir, DeviceKeyStore.FILE_NAME).writeBytes(ByteArray(59))

        assertNull(DeviceKeyStore.load(context))
    }

    @Test
    fun malformedWrappedDek_isRejectedBeforeWrite() {
        val malformed = WrappedDek(ByteArray(11), ByteArray(48))

        assertFailsWith<IllegalArgumentException> {
            DeviceKeyStore.save(context, malformed)
        }
        assertNull(DeviceKeyStore.load(context))
    }

    @Test
    fun failedAtomicReplace_cleansTemporaryFile() {
        val destination = File(context.filesDir, DeviceKeyStore.FILE_NAME)
        val temporary = File(context.filesDir, DeviceKeyStore.TEMP_FILE_NAME)
        destination.mkdirs()

        assertFailsWith<Exception> {
            DeviceKeyStore.save(context, wrappedDek())
        }

        assertFalse(temporary.exists())
        destination.delete()
    }

    private fun wrappedDek(): WrappedDek = WrappedDek(
        nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
        ciphertext = SecureRandomProvider.nextBytes(
            CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
        ),
    )
}
