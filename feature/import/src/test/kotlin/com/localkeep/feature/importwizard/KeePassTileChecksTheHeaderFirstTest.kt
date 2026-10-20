package com.localkeep.feature.importwizard

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.import.ImportFailureReason
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultVisibilitySnapshot
import com.localkeep.feature.files.FileBlobStore
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The KeePass tile reads the file's first bytes before it asks for a database password.
 *
 * Every file picked under that tile went to the password step, a JPEG or a `keepass.xml` included,
 * where the screen said "This database is encrypted" about something that is not a database, and
 * the real cause appeared only after a password was typed and a key derivation was run on it.
 * A KDBX file carries a four-byte signature, so a file without one has no password to ask for and
 * is answered with the same sentence as before, straight away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeePassTileChecksTheHeaderFirstTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now: Instant = Instant.now()

    private val emptyVault: VaultVisibilitySnapshot = mock { on { liveItems } doReturn emptyList() }
    private val repository: VaultRepository = mock { on { visibilitySnapshot() } doReturn emptyVault }
    private val lockManager: VaultLockManager = mock {
        on { sessionSnapshotOrNull() } doReturn
            VaultLockManager.SessionSnapshot(vault = Vault(createdAt = now, modifiedAt = now), dek = ByteArray(32))
    }
    private val blobStore: FileBlobStore = mock()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun settle(done: () -> Boolean) {
        repeat(1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            if (done()) return
            Thread.sleep(10)
        }
        fail("the wizard never settled")
    }

    private fun pick(bytes: ByteArray): ImportWizardViewModel {
        val vm = ImportWizardViewModel(repository, lockManager, blobStore, context)
        vm.selectSource(ImportSource.KEEPASS)
        val uri = Uri.fromFile(File.createTempFile("import", ".bin", context.cacheDir).apply { writeBytes(bytes) })
        vm.onFilePicked(uri)
        settle { !vm.state.value.isWorking }
        return vm
    }

    @Test
    fun `a file that is not a KDBX is refused without a password prompt`() {
        val vm = pick("<?xml version=\"1.0\"?><KeePassFile/>".toByteArray())

        assertEquals(ImportUiState.Step.INSTRUCTIONS, vm.state.value.step, "no password well for a file with no database in it")
        assertEquals(ImportError.FromImporter(ImportFailureReason.NotAKdbxFile), vm.state.value.error)
    }

    @Test
    fun `a KDBX file still goes to the password step`() {
        // The control: KDBX 4's signature, 0x9AA2D903 then 0xB54BFB67, both little-endian.
        val header = byteArrayOf(
            0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(),
            0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte(),
        )
        val vm = pick(header + ByteArray(64))

        assertEquals(ImportUiState.Step.PASSWORD, vm.state.value.step)
        assertEquals(true, vm.state.value.passwordIsForDatabase)
    }
}
