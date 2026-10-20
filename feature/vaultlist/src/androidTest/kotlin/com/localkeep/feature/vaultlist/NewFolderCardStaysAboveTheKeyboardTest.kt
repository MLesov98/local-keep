package com.localkeep.feature.vaultlist

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpRect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.billing.PurchaseOutcome
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Folder
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * With the keyboard up on the Folders screen's New folder card, where the folder goes and the
 * Create button are still on screen.
 *
 * Device pass on the release build, API 36, 2026-10-05 (lane D3, finding 3): Drawer > Folders > New
 * folder, tap the name field. With the keyboard up the card showed only the name field above the
 * pinned Cancel; "At the top level" and "Create folder" sat below the list's viewport and came back
 * only once the keyboard was hidden. The keyboard's Done key creates the folder, so the one route to
 * a parent folder was to dismiss the keyboard first. Measured on emulator-5562: field bottom at
 * y=1275 against a viewport ending at y=1275 — the field brought into view and nothing under it.
 *
 * The card already brings itself fully into view when it is laid out or changes size; the keyboard
 * changes neither. It shrinks the list's viewport (the screen's `imePadding()`), and the only thing
 * that scrolls in answer is the focused field keeping *itself* visible. Nothing re-asked for the
 * card.
 *
 * Real keyboard, not a dispatched inset: the defect is the interaction of the focused field's own
 * scroll with the card's, and only a real focus and a real IME produce both. The activity is put
 * edge to edge with `adjustResize`, as `MainActivity` is, so the keyboard reaches Compose as
 * `WindowInsets.ime` rather than resizing the window. Fixture as in [FolderListErrorStaysOnScreenTest].
 */
@RunWith(AndroidJUnit4::class)
class NewFolderCardStaysAboveTheKeyboardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

    @BeforeTest
    fun setUp() = runBlocking {
        cleanupFiles()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context))
        lockManager.unlock(password())
        repository = VaultRepository(lockManager)
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    @Test
    fun theParentChoiceAndCreateStayVisibleWithTheKeyboardUp() {
        val folders = List(FOLDERS) { n -> Folder(UUID.randomUUID(), name(n), updatedAt = Instant.EPOCH) }
        runBlocking { repository.mutate { it.folders += folders } }
        val viewModel = FolderViewModel(repository, lockManager, FreeEntitlement())
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            @Suppress("DEPRECATION")
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        compose.setContent {
            LocalKeepTheme { FolderScreen(viewModel = viewModel, onOpenFolder = {}, onBack = {}) }
        }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(name(0)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { viewModel.showNewFolderInput() }
        val create = context.getString(R.string.vaultlist_folders_new_create)
        val parent = context.getString(R.string.vaultlist_folders_new_in_root)
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText(create).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        // The control: before the keyboard, the whole card is on screen.
        assertWhollyVisible(create)

        compose.onNode(hasSetTextAction()).performClick()
        awaitKeyboardSettled()

        val keyboardTop = keyboardTopDp()
        for (label in listOf(parent, create)) {
            val bounds = assertWhollyVisible(label)
            assertTrue(
                bounds.bottom.value <= keyboardTop + 0.5f,
                "'$label' ends at ${bounds.bottom} with the keyboard's top edge at ${keyboardTop}dp",
            )
        }
    }

    /**
     * The card stays open after a folder is made (2026-10-12: "What if I want to create 5 or 10
     * folders or 20?"), so the name field has to still be there for the next one.
     *
     * A control, not a regression test: it passed before the Folders screen changed, because the
     * lazy list keeps its anchor row and the card did not move. The picker in `core:ui` is a plain
     * scrolling column and did lose its field — `LkFolderPickerFieldStaysAboveTheKeyboardAfterCreateTest`.
     * This pins that the two screens still agree.
     */
    @Test
    fun theNameFieldIsStillAboveTheKeyboardOnceAFolderHasBeenMade() {
        val folders = List(FOLDERS) { n -> Folder(UUID.randomUUID(), name(n), updatedAt = Instant.EPOCH) }
        runBlocking { repository.mutate { it.folders += folders } }
        val viewModel = FolderViewModel(repository, lockManager, FreeEntitlement())
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            @Suppress("DEPRECATION")
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent {
            LocalKeepTheme { FolderScreen(viewModel = viewModel, onOpenFolder = {}, onBack = {}) }
        }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(name(0)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { viewModel.showNewFolderInput() }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performClick()
        awaitKeyboardSettled()
        compose.onNode(hasSetTextAction()).performTextInput("Fresh")
        assertFieldWhollyAboveTheKeyboard("before the create")

        compose.onNodeWithText(context.getString(R.string.vaultlist_folders_new_create)).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            viewModel.state.value.newName.isEmpty() && viewModel.state.value.folders.any { it.folder.name == "Fresh" }
        }
        awaitKeyboardSettled()

        assertFieldWhollyAboveTheKeyboard("after the create")
    }

    private fun assertFieldWhollyAboveTheKeyboard(moment: String) {
        val field = compose.onNode(hasSetTextAction())
        val visible = field.getBoundsInRoot()
        assertTrue(visible.bottom > visible.top, "$moment: the name field is entirely outside the list's viewport")
        assertEquals(field.getUnclippedBoundsInRoot(), visible, "$moment: the name field is cut off by the list's viewport")
        assertTrue(
            visible.bottom.value <= keyboardTopDp() + 0.5f,
            "$moment: the name field ends at ${visible.bottom} with the keyboard's top edge at ${keyboardTopDp()}dp",
        )
    }

    /** The node's visible bounds — asserted to be the whole node, not a sliver the viewport left. */
    private fun assertWhollyVisible(text: String): DpRect {
        val node = compose.onNodeWithText(text)
        val visible = node.getBoundsInRoot()
        assertTrue(visible.bottom > visible.top, "'$text' is entirely outside the list's viewport")
        assertEquals(node.getUnclippedBoundsInRoot(), visible, "'$text' is cut off by the list's viewport")
        return visible
    }

    private fun imeBottomPx(): Int {
        var bottom = 0
        compose.runOnUiThread {
            bottom = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        return bottom
    }

    /** Up, and finished moving: the same height twice, half a second apart. */
    private fun awaitKeyboardSettled() {
        compose.waitUntil(TIMEOUT_MS) { imeBottomPx() > 0 }
        var last = -1
        compose.waitUntil(TIMEOUT_MS) {
            val now = imeBottomPx()
            val settled = now == last
            last = now
            if (!settled) SystemClock.sleep(SETTLE_STEP_MS)
            settled
        }
        compose.waitForIdle()
    }

    private fun keyboardTopDp(): Float {
        var heightPx = 0
        compose.runOnUiThread { heightPx = compose.activity.window.decorView.height }
        return (heightPx - imeBottomPx()) / context.resources.displayMetrics.density
    }

    private fun name(n: Int) = "Folder %02d".format(n)

    private class FreeEntitlement : Entitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(false)
        override val canPurchase: Boolean = false
        override val displayPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun purchase(activity: Activity): PurchaseOutcome = PurchaseOutcome.Failed
        override suspend fun restore(): Boolean = false
    }

    private fun password() = "correct horse battery staple".toCharArray()

    private fun createFreshVault() {
        val password = password()
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kek = EnvelopeCrypto.deriveKek(password, salt, KdfParams.FLOOR)
        try {
            AtomicVaultWriter.write(
                context,
                VaultFileCodec.create(
                    dek = dek,
                    salt = salt,
                    kdfParams = KdfParams.FLOOR,
                    wrappedDek = EnvelopeCrypto.wrapDek(kek, dek),
                    commitment = EnvelopeCrypto.slotCommitment(dek),
                ),
            )
        } finally {
            Zeroizer.wipe(password)
            Zeroizer.wipe(dek)
            Zeroizer.wipe(kek)
        }
    }

    private fun cleanupFiles() {
        context.filesDir.listFiles()?.forEach(File::deleteRecursively)
    }

    private companion object {
        /** Enough that the card opens below the first screenful, as on the device's 34-folder vault. */
        const val FOLDERS = 18
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_STEP_MS = 500L
    }
}
