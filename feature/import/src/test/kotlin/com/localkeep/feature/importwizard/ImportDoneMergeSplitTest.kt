package com.localkeep.feature.importwizard

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.model.Login
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultMutator
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultVisibilitySnapshot
import com.localkeep.feature.files.FileBlobStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Done step's "merged into entries you already had" counts rows that merged into the VAULT,
 * and only those. A file that lists the same login twice, imported into a vault holding neither,
 * used to report "1 merged into an entry you already had" — `selected.size - added` lumped
 * in-file repeats ([PreviewRow.collidesWithVault] false) in with vault merges.
 *
 * Driven through the real wizard: CSV rows carry no dates, so the second copy previews as
 * ALREADY_CURRENT and is pre-deselected; the test selects it, which is the path a user takes with
 * "Select all".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportDoneMergeSplitTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now: Instant = Instant.now()

    private var vaultItems: List<VaultItem> = emptyList()

    private val snapshot: VaultVisibilitySnapshot = mock {
        on { liveItems } doAnswer { vaultItems }
    }

    private val repository: VaultRepository = mock {
        on { visibilitySnapshot() } doReturn snapshot
        onBlocking { mutate(any()) } doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val block = invocation.arguments[0] as (VaultMutator) -> Unit
            block(VaultMutator(Vault(items = vaultItems, createdAt = now, modifiedAt = now)))
            true
        }
    }

    private val lockManager: VaultLockManager = mock {
        on { sessionSnapshotOrNull() } doAnswer {
            VaultLockManager.SessionSnapshot(
                vault = Vault(items = emptyList(), createdAt = now, modifiedAt = now),
                dek = ByteArray(32) { 9 },
            )
        }
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

    private fun previewOf(csv: String): ImportWizardViewModel {
        val vm = ImportWizardViewModel(repository, lockManager, blobStore, context)
        vm.selectSource(ImportSource.CHROME)
        val file = File.createTempFile("import", ".csv", context.cacheDir).apply { writeText(csv) }
        vm.onFilePicked(Uri.fromFile(file))
        settle { !vm.state.value.isWorking }
        return vm
    }

    private fun applyWithEverythingSelected(vm: ImportWizardViewModel) {
        vm.setAllSelected(true)
        vm.applyImport()
        settle { vm.state.value.step == ImportUiState.Step.DONE }
    }

    @Test
    fun `the same login twice in a file, against an empty vault, is a file repeat and not a vault merge`() {
        val vm = previewOf(
            """
            name,url,username,password
            Site,https://example.com,alice,hunter2
            Site,https://example.com,alice,hunter2
            """.trimIndent(),
        )
        assertEquals(
            listOf(false, false),
            vm.state.value.rows.map { it.collidesWithVault },
            "precondition: neither row matches anything in the vault",
        )

        applyWithEverythingSelected(vm)

        assertEquals(1, vm.state.value.importedCount)
        assertNull(vm.state.value.mergedIntoExistingCount, "the vault held nothing for either row to merge into")
        assertEquals(1, vm.state.value.repeatedInFileCount)
    }

    @Test
    fun `control - a file row matching a vault entry still counts as merged into an existing entry`() {
        vaultItems = listOf(
            VaultItem(
                id = UUID.randomUUID(),
                title = "Site",
                createdAt = now,
                updatedAt = now,
                content = Login(
                    username = "alice",
                    password = "old",
                    uris = listOf(UriMatch(uri = "https://example.com")),
                ),
            ),
        )
        val vm = previewOf(
            """
            name,url,username,password
            Site,https://example.com,alice,hunter2
            """.trimIndent(),
        )
        assertEquals(listOf(true), vm.state.value.rows.map { it.collidesWithVault })

        applyWithEverythingSelected(vm)

        assertEquals(0, vm.state.value.importedCount)
        assertEquals(1, vm.state.value.mergedIntoExistingCount)
        assertNull(vm.state.value.repeatedInFileCount)
    }

    @Test
    fun `a vault match and an in-file repeat in one import are counted separately`() {
        vaultItems = listOf(
            VaultItem(
                id = UUID.randomUUID(),
                title = "Site",
                createdAt = now,
                updatedAt = now,
                content = Login(
                    username = "alice",
                    password = "old",
                    uris = listOf(UriMatch(uri = "https://example.com")),
                ),
            ),
        )
        val vm = previewOf(
            """
            name,url,username,password
            Site,https://example.com,alice,hunter2
            Other,https://other.org,bob,pw
            Other,https://other.org,bob,pw
            """.trimIndent(),
        )

        applyWithEverythingSelected(vm)

        assertEquals(1, vm.state.value.importedCount)
        assertEquals(1, vm.state.value.mergedIntoExistingCount)
        assertEquals(1, vm.state.value.repeatedInFileCount)
    }

    @Test
    fun `ADD_ALL folds nothing, so neither count is reported`() {
        val row = PreviewRow(
            id = UUID.randomUUID(),
            title = "x",
            subtitle = "",
            disposition = com.localkeep.core.import.ImportMerger.Disposition.ALREADY_CURRENT,
            collidesWith = "x",
            collidesWithVault = true,
            selected = true,
            item = VaultItem(
                id = UUID.randomUUID(),
                title = "x",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                content = Login(username = "u", password = "p"),
            ),
        )
        val mode = com.localkeep.core.import.ImportMerger.Mode.ADD_ALL
        assertNull(mergedIntoVaultCount(mode, listOf(row)))
        assertNull(repeatedInFileCount(mode, listOf(row)))
    }
}
