package com.localkeep.feature.importwizard

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultVisibilitySnapshot
import com.localkeep.feature.files.FileBlobStore
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
 * A KeePass database that needs a password *and* a key file, opened the way the wizard's own error
 * tells the user to: type the password, hear "this database also needs its key file", add it, try
 * again (device report D1 finding 1, 2026-10-04).
 *
 * The second attempt failed as "wrong password" every time while the well still showed eight dots,
 * and retyping the same eight characters opened it. N-02 restores the typed password into the well
 * after a failure, but it read the restored copy out of the `CharArray` it had just handed to
 * `KdbxImporter` — which zeroes that array in its own `finally`. So the well came back holding eight
 * NULs, rendered as eight dots, and the retry sent them as the password.
 *
 * The fixture is the corpus's `pykeepass/test4.kdbx` (password `password`, key file `test4.key`) —
 * byte-identical to the `keepass-keyfile.kdbx` the device tester picked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportKeyFileRetryTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val emptyVault: VaultVisibilitySnapshot = mock {
        on { liveItems } doReturn emptyList()
    }
    private val repository: VaultRepository = mock {
        on { visibilitySnapshot() } doReturn emptyVault
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ImportWizardViewModel(repository, mock<VaultLockManager>(), mock<FileBlobStore>(), context)

    /** Runs the Main queue until [done], letting the IO and Default halves finish on their threads. */
    private fun settle(done: () -> Boolean) {
        repeat(3_000) {
            dispatcher.scheduler.advanceUntilIdle()
            if (done()) return
            Thread.sleep(10)
        }
        fail("the wizard never settled")
    }

    private fun corpus(path: String): ByteArray {
        val file = File("../../core/import/src/test/resources/realworld/$path")
        if (!file.isFile) fail("cannot find ${file.absolutePath}")
        return file.readBytes()
    }

    private fun picked(bytes: ByteArray): Uri =
        Uri.fromFile(File.createTempFile("picked", ".bin", context.cacheDir).apply { writeBytes(bytes) })

    @Test
    fun `adding the key file after a failed attempt opens the database without retyping the password`() {
        val vm = viewModel()
        vm.selectSource(ImportSource.KEEPASS)
        vm.onFilePicked(picked(corpus("keepass/pykeepass/test4.kdbx")))
        settle { !vm.state.value.isWorking }
        assertEquals(ImportUiState.Step.PASSWORD, vm.state.value.step)

        // Password alone: refused, and the well is refilled so "Add key file" is a one-tap repair.
        vm.onPasswordChanged("password")
        vm.submitPassword()
        settle { !vm.state.value.isWorking }
        assertEquals(ImportUiState.Step.PASSWORD, vm.state.value.step, "the password alone must not open it")
        assertNotNull(vm.state.value.error, "a refused attempt must say so")
        assertEquals(
            "password",
            vm.state.value.password,
            "the well must hold what was typed, not the wiped buffer the importer zeroed",
        )

        vm.onKeyFilePicked(picked(corpus("keepass/pykeepass/test4.key")))
        settle { vm.state.value.keyFileName != null && !vm.state.value.isWorking }
        vm.submitPassword()
        settle { !vm.state.value.isWorking }

        assertEquals(
            ImportUiState.Step.PREVIEW,
            vm.state.value.step,
            "the same visible password plus the key file must open the database; error was ${vm.state.value.error}",
        )
        assertTrue(vm.state.value.rows.isNotEmpty())
        assertEquals("", vm.state.value.password, "a successful open still clears the well")
    }
}
