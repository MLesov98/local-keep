package com.localkeep.feature.itemedit

import android.app.Activity
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The editor is headed by the thing being made, and by nothing else.
 *
 * It used to be headed "New item" with a scrolling row of six type chips underneath. Six chips
 * outrun the width of a phone and the row started at its left edge, so an item created from the add
 * sheet's **Location** row opened showing Login, Secure note, 2FA code, Card and Identity, none of
 * them lit, with its own type off the right-hand edge — which reads as "I am making a Login".
 * Bringing the selected chip into view fixed the misreading and left the real problem: the type is
 * already chosen, in the add sheet, and offering it again at the top of the form is a control for a
 * decision nobody is making. `Item Flows.dc.html` heads each of its six flows with the type's own
 * name and no chips at all (principal's instruction, 2026-08-19: *"I don't wanna see the shortcut
 * on the top, do it like the design"*).
 *
 * So this asserts both halves: the header names this item's type, and no *other* type's name is
 * anywhere on the screen. The second half is what would go red if the row came back.
 *
 * Instrumented for the reason [ItemEditViewModelTest] gives: `ItemEditViewModel` needs a real
 * `VaultRepository`, whose `KeystoreManager` opens AndroidKeyStore in its constructor. The fixture
 * below is that class's, duplicated rather than shared — the precedent the other three test classes
 * in this module already set.
 *
 * Entitled, because `COORDINATE` is a Pro type: a free session would raise the upgrade prompt
 * instead of opening the form, and the test would be measuring the paywall.
 */
@RunWith(AndroidJUnit4::class)
class EditorHeaderInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository
    private lateinit var viewModel: ItemEditViewModel

    @BeforeTest
    fun setUp() = runBlocking {
        cleanupFiles()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context))
        lockManager.unlock(password())
        repository = VaultRepository(lockManager)
        viewModel = ItemEditViewModel(
            repository,
            lockManager,
            ProEntitlement(),
            SavedStateHandle(),
        )
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    @Test
    fun theHeaderNamesThisItemsTypeAndNoOtherTypeIsOffered() {
        val location = context.getString(R.string.itemedit_type_coordinate)

        // The ViewModel runs a `while (isActive) { refreshTotpCode(); delay(1_000) }` loop from
        // `init`, so Compose's idling resource never reports idle and every assertion below would
        // time out with ComposeNotIdleException. Driving the clock by hand is the supported answer:
        // the frames still run, they just run when this test says so.
        compose.mainClock.autoAdvance = false

        compose.setContent {
            LocalKeepTheme {
                ItemEditScreen(
                    viewModel = viewModel,
                    itemId = null,
                    onSaved = {},
                    onBack = {},
                    presetType = ItemEditUiState.ItemType.COORDINATE,
                )
            }
        }

        // Enough frames for the preset type to land and the scroll to settle. `bringIntoView`
        // animates, so a single frame would assert against the row's opening position — which is
        // exactly the position the defect describes, and the test would pass while failing.
        compose.mainClock.advanceTimeBy(2_000)

        // The header, which is now the type's own name. `assertIsDisplayed`, not `assertExists`:
        // this has to be on screen at the top of the form, not merely present in the tree.
        compose.onNodeWithText(location).assertIsDisplayed()
        // And no other type is offered. `assertDoesNotExist` rather than `assertIsNotDisplayed`,
        // because the chips did exist in the tree the whole time the defect was live — they were
        // simply scrolled out of the viewport. Existence is the thing that had to change.
        listOf(
            R.string.itemedit_type_login,
            R.string.itemedit_type_note,
            R.string.itemedit_type_totp,
            R.string.itemedit_type_card,
            R.string.itemedit_type_identity,
        ).forEach { compose.onNodeWithText(context.getString(it)).assertDoesNotExist() }
    }

    /**
     * The form's vertical geometry is the comp's, and stays it.
     *
     * `Item Flows.dc.html` pads every item screen `90px 26px 32px` over a 44px status bar and then
     * puts `16px` between the title and the first field's label. Those two numbers are the whole
     * complaint the principal raised on 2026-08-19 — *"Login is slightly lower [in the design]…
     * I want it literally 1:1"* — and neither is reproducible by eye, because both are absorbed by
     * `LkScreenHeader`: the back control makes the header row 48dp tall with a 23dp title centred
     * in it, so a naive 46dp of padding lands the title at ~58 and a naive 16dp gap renders as 28.5.
     * `ItemFormTop` and `ItemFormHeaderGap` correct for exactly that, and this is what proves they
     * still do.
     *
     * Measured between the *title* and the *label*, not between layout nodes, because that is how
     * the comp states it and it is what a person looking at the screen sees.
     *
     * The first field is USERNAME again. It was TITLE (REQUIRED) from 2026-09-16 (F-15) to
     * 2026-10-05, when the title field moved below each type's own fields and fills itself from
     * them; on a Login that puts it under the websites.
     *
     * Tolerance is 2dp: the title's line box is 23dp by construction here and a hair more once a
     * real font's metrics are applied, and the constants are derived from the 23. Two dp is tight
     * enough to catch a reintroduced 12.5dp offset — the defect this exists for — and loose enough
     * to survive a font-metrics change that moves nothing anybody can see.
     */
    @Test
    fun theTitleAndTheFirstFieldSitWhereTheCompPutsThem() {
        val login = context.getString(R.string.itemedit_type_login)
        val firstLabel = context.getString(R.string.itemedit_username_label).uppercase()

        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                ItemEditScreen(
                    viewModel = viewModel,
                    itemId = null,
                    onSaved = {},
                    onBack = {},
                    presetType = ItemEditUiState.ItemType.LOGIN,
                )
            }
        }
        compose.mainClock.advanceTimeBy(2_000)

        val title = compose.onNodeWithText(login).getUnclippedBoundsInRoot()
        val label = compose.onNodeWithText(firstLabel).getUnclippedBoundsInRoot()

        // 46dp from the top of the content area to the top of the title. The test window has no
        // system-bar inset, so the root *is* the edge the comp measures from.
        val titleTop = title.top.value
        assert(titleTop in 44f..48f) {
            "title top was ${titleTop}dp, expected the comp's 46dp (±2) below the status bar"
        }
        // 16dp from the bottom of the title to the top of the first label.
        val gap = label.top.value - title.bottom.value
        assert(gap in 14f..18f) {
            "title-to-label gap was ${gap}dp, expected the comp's 16dp (±2)"
        }
        // And the frame: 26dp margins, not the 20dp list frame. Both edges, because a single-sided
        // check passes on a form that is centred by accident.
        val labelLeft = label.left.value
        assert(labelLeft in 25f..27f) {
            "left margin was ${labelLeft}dp, expected the comp's 26dp"
        }
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

    private class ProEntitlement : Entitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(true)
        override val canPurchase: Boolean = false
        override val displayPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun purchase(activity: Activity): com.localkeep.core.billing.PurchaseOutcome =
            com.localkeep.core.billing.PurchaseOutcome.Failed
        override suspend fun restore(): Boolean = false
    }
}
