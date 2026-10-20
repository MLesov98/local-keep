package com.localkeep.feature.files

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.model.File
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.AttachmentQuota
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultStorageInventory
import com.localkeep.core.vault.VaultVisibilitySnapshot
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * On `foss` the quota refusal must not tell the reader to buy something that cannot be bought.
 *
 * `FossEntitlement.canPurchase` is permanently false, yet the files refusal ended "or unlock Pro for
 * unlimited" with no branch on it — an instruction to look for a control that does not exist. The
 * vault list's dialog already chooses its last clause on the same flag; this is the same decision
 * for the two other screens that show the same refusal. Behaviour, not source text: the picker's
 * real view-model raises the refusal, and the strings are resolved from the real catalogues in every
 * language (Robolectric includes them), through the same id list the screen renders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuotaRefusalWithoutPurchaseTest {

    private val locales = listOf("en", "fr", "de", "es", "pt")

    private fun target() = VaultItem(
        id = UUID.randomUUID(), title = "t", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        content = Login(username = "u"),
    )

    private fun fileItem() = VaultItem(
        id = UUID.randomUUID(), title = "f", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        content = File(filename = "f.jpg", mime = "image/jpeg", blobRef = "blob-${UUID.randomUUID()}"),
    )

    private fun fullVaultViewModel(canPurchase: Boolean): Pair<AttachmentPickerViewModel, VaultItem> {
        val target = target()
        val items = List(AttachmentQuota.FREE_LIMIT) { fileItem() } + target
        val repository = mock<VaultRepository>()
        whenever(repository.visibilitySnapshot()).thenReturn(
            VaultVisibilitySnapshot(
                liveItems = items, trashItems = emptyList(), liveFolders = emptyList(),
                trashedFolderRows = emptyList(), concealedItemIds = emptySet(),
                concealedFolderIds = emptySet(), directlyHiddenItemIds = emptySet(),
                directlyHiddenFolderIds = emptySet(), nextExpiry = null, purgeableTrashItemCount = 0,
            ),
        )
        whenever(repository.getCompleteStorageInventory())
            .thenReturn(VaultStorageInventory(allItems = items, allFolders = emptyList()))
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        whenever(entitlement.canPurchase).thenReturn(canPurchase)
        val viewModel = AttachmentPickerViewModel(
            repository = repository,
            lockManager = mock<VaultLockManager>(),
            fileBlobStore = mock<FileBlobStore>(),
            photoPickerImport = mock<PhotoPickerImport>(),
            entitlement = entitlement,
            context = ApplicationProvider.getApplicationContext<Context>(),
        )
        viewModel.load(target.id.toString())
        return viewModel to target
    }

    /** The refusal as the screen renders it, in [tag]'s catalogue. */
    private fun rendered(tag: String, state: AttachmentPickerUiState): String {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        val context = base.createConfigurationContext(config)
        val error = assertNotNull(state.error, "the refusal must have been raised")
        return refusalStringIds(error, state.canPurchase).joinToString("") {
            context.getString(it, AttachmentQuota.FREE_LIMIT, state.quotaMustRemove)
        }
    }

    @Test
    fun `without a purchase path the refusal never offers one, in any language`() {
        val (viewModel, _) = fullVaultViewModel(canPurchase = false)
        assertTrue(viewModel.refuseIfQuotaFull(), "a full free vault must refuse")
        val state = viewModel.state.value

        // The purchase clause in each language, taken from the shipped vault-list wording the two
        // screens must agree with. The no-purchase clause names where Pro lives instead.
        val bannedClause = mapOf(
            "en" to "for unlimited", "fr" to "accès illimité", "de" to "unbegrenzt",
            "es" to "acceso ilimitado", "pt" to "acesso ilimitado",
        )
        val wherePro = mapOf(
            "en" to "Play version", "fr" to "version Play", "de" to "Play-Version",
            "es" to "versión de Play", "pt" to "versão Play",
        )
        locales.forEach { tag ->
            val text = rendered(tag, state)
            assertFalse(bannedClause.getValue(tag) in text, "$tag still offers an unlimited unlock: \"$text\"")
            assertTrue(wherePro.getValue(tag) in text, "$tag must say where Pro can be bought: \"$text\"")
            assertTrue(AttachmentQuota.FREE_LIMIT.toString() in text, "$tag lost the count: \"$text\"")
            assertTrue(state.quotaMustRemove.toString() in text, "$tag lost the remove count: \"$text\"")
        }
    }

    @Test
    fun `with a purchase path the refusal keeps its purchase clause, in any language`() {
        val (viewModel, _) = fullVaultViewModel(canPurchase = true)
        assertTrue(viewModel.refuseIfQuotaFull())
        val state = viewModel.state.value
        assertTrue(state.canPurchase)

        val purchaseClause = mapOf(
            "en" to "for unlimited", "fr" to "accès illimité", "de" to "unbegrenzt",
            "es" to "acceso ilimitado", "pt" to "acesso ilimitado",
        )
        locales.forEach { tag ->
            val text = rendered(tag, state)
            assertTrue(purchaseClause.getValue(tag) in text, "$tag lost the purchase clause: \"$text\"")
        }
    }

    @Test
    fun `an error that is not the quota refusal is rendered whole whatever the build`() {
        assertEquals(
            listOf(R.string.files_attachments_error_attach_failed),
            refusalStringIds(R.string.files_attachments_error_attach_failed, canPurchase = false),
        )
    }
}
