package com.localkeep.feature.itemedit

import android.app.Activity
import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * A copyable [SecretReadField] row names Copy on the gesture that performs it, and on no other.
 *
 * The row used to hang the "Copy <field>" label on a `Modifier.combinedClickable(onClick = {})`
 * bolted onto the row — an empty tap standing in for the long-press `combinedClickable` needs
 * beside it. TalkBack therefore offered a *click* action named "Copy password", activating it did
 * nothing at all, and the long-press that actually copies carried no label of its own. Fixed by
 * routing through [com.localkeep.core.ui.theme.LkGroupRow]'s own `onLongClick`/`onLongClickLabel`
 * instead — the parameters [LkGroupRowLongClickOnlyAccessibilityTest] already proves put the label
 * on the real action and nothing on a fake one.
 *
 * That component-level test cannot stand in for this one: its row nests a single `Text`, while
 * `SecretReadField`'s nests a Show/Hide icon button and, when copyable, a Copy icon button too —
 * both independently clickable. The bug was in the wiring at *this* call site, so this pins the
 * call site, not the component.
 *
 * A non-copyable row (`copyable = false`, [PasswordHistoryGroup]'s own use for a retired password)
 * is the control: it must carry neither action, and it always did — proving the assertions below
 * distinguish "no action" from "the wrong action" rather than passing on any row whatsoever.
 */
@RunWith(AndroidJUnit4::class)
class SecretReadFieldCopyOnlyAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository
    private lateinit var viewModel: ItemEditViewModel

    private companion object {
        const val COPYABLE_TAG = "copyableSecretRow"
        const val CONTROL_TAG = "nonCopyableSecretRow"
        const val COPYABLE_FIELD = "password"
        const val CONTROL_FIELD = "passwordHistory:0"
        const val SECRET_VALUE = "correct horse battery staple"
    }

    @BeforeTest
    fun setUp() = runBlocking {
        cleanupFiles()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context))
        lockManager.unlock(password())
        repository = VaultRepository(lockManager)
        viewModel = ItemEditViewModel(repository, lockManager, ProEntitlement(), SavedStateHandle())
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    private fun <T> SemanticsConfiguration.getOrNull(key: SemanticsPropertyKey<T>): T? =
        if (contains(key)) this[key] else null

    @Test
    fun aCopyableRowNamesCopyOnTheLongClickAndAControlRowNamesNothing() {
        val copyLabel = context.getString(R.string.itemedit_password_label)
        val controlLabel = context.getString(R.string.itemedit_detail_password_history_title)

        // ItemEditViewModel runs a `while (isActive) { refreshTotpCode(); delay(1_000) }` loop from
        // `init`, so Compose's idling resource never reports idle and every assertion below would
        // time out with ComposeNotIdleException. Driving the clock by hand is the supported fix.
        compose.mainClock.autoAdvance = false

        // Read once, outside composition. `SecretReadField` takes the state as a plain parameter,
        // and reading `StateFlow.value` inside `setContent` is a lint error here
        // (`StateFlowValueCalledInComposition`) because it subscribes to nothing — a real screen
        // collects the flow so recomposition follows it. Nothing in this test changes the state, so
        // hoisting the read is both correct and what the lint rule asks for.
        val uiState = viewModel.state.value

        compose.setContent {
            LocalKeepTheme {
                // Neither row is wired to a live vault item — `state` is the ViewModel's own
                // freshly-constructed default, exactly as an item with no reveal or copy in flight
                // would render it. Only the wiring under test (label/action semantics) depends on
                // that state; nothing here needs a loaded item.
                SecretReadField(
                    label = copyLabel,
                    value = SECRET_VALUE,
                    field = COPYABLE_FIELD,
                    state = uiState,
                    viewModel = viewModel,
                    first = true,
                    copyable = true,
                    modifier = Modifier.testTag(COPYABLE_TAG),
                )
                SecretReadField(
                    label = controlLabel,
                    value = SECRET_VALUE,
                    field = CONTROL_FIELD,
                    state = uiState,
                    viewModel = viewModel,
                    first = false,
                    copyable = false,
                    modifier = Modifier.testTag(CONTROL_TAG),
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)

        val copyableConfig = compose.onNodeWithTag(COPYABLE_TAG).fetchSemanticsNode().config
        assertNull(
            copyableConfig.getOrNull(SemanticsActions.OnClick),
            "a copyable secret row must not expose a click action — there is nothing behind it",
        )
        val longAction = copyableConfig.getOrNull(SemanticsActions.OnLongClick)
        assertNotNull(longAction, "the long-press copy action must be exposed to a screen reader")
        assertEquals(
            context.getString(R.string.itemedit_action_copy, copyLabel),
            longAction.label,
            "the copy label must sit on the long-click action, the one that actually copies",
        )
        // Confirms the semantics action reaches the real ViewModel call, not just that a label
        // happens to be attached to something. requestCopy() grants the copy immediately here
        // because "password" is not in ALWAYS_REAUTH_FIELDS and the fresh state has reprompt=false.
        assertTrue(longAction.action?.invoke() == true, "invoking the long-click action should succeed")
        assertEquals(
            COPYABLE_FIELD,
            viewModel.state.value.copyGrantedField,
            "the long-click action must call viewModel.requestCopy(field), not a no-op",
        )

        val controlConfig = compose.onNodeWithTag(CONTROL_TAG).fetchSemanticsNode().config
        assertFalse(
            controlConfig.contains(SemanticsActions.OnClick),
            "a non-copyable row must never gain a click action",
        )
        assertFalse(
            controlConfig.contains(SemanticsActions.OnLongClick),
            "a non-copyable row must never gain a long-click action",
        )
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
