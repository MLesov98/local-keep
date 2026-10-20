package com.localkeep.feature.itemedit

import android.app.Activity
import android.content.Context
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
import com.localkeep.core.model.Attachment
import com.localkeep.core.model.Card
import com.localkeep.core.model.CardBrand
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.MatchStrategy
import com.localkeep.core.model.PHRASE_FIELD
import com.localkeep.core.model.SeedPhrase
import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.LockState
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * The first tests `:feature:itemedit` has ever had.
 *
 * This ViewModel owns three things nothing else in the build checks — reveal gating, unsaved-change
 * tracking, and the state↔content mapping every save goes through — and it is exactly the shape of
 * code this project has shipped broken twice before. `panicLock()` compiled and passed with no
 * caller; autofill save compiled and passed while discarding every credential. Both were logic a
 * green build had no opinion about.
 *
 * **Why instrumented rather than Robolectric.** Not preference: `ItemEditViewModel` needs a real
 * `VaultRepository`, which needs a real `VaultLockManager`, whose `KeystoreManager` opens
 * AndroidKeyStore in its constructor. That is absent on a JVM and unimplemented under Robolectric.
 * `:core:vault` reached the same wall and answered it the same way, so this follows the precedent
 * already in the repo rather than inventing a second one.
 */
@RunWith(AndroidJUnit4::class)
class ItemEditViewModelTest {

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
        viewModel = ItemEditViewModel(repository, lockManager, FreeEntitlement(), SavedStateHandle())
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    // ── reveal gating (§5.2) ──────────────────────────────────────────────

    @Test
    fun ordinaryFieldsRevealWithoutReauth() {
        viewModel.toggleFieldRevealed("password")

        assertTrue("password" in viewModel.state.value.revealedFields)
        assertNull(viewModel.state.value.pendingRevealField, "an ordinary field must not prompt")
    }

    @Test
    fun highValueFieldsAlwaysDemandReauthEvenWithoutReprompt() {
        // The invariant: these three are gated by *what they are*, not by the item's reprompt flag.
        // A CVV, a card PIN and a TOTP seed are each enough on their own to use or clone the
        // credential, so an item with reprompt off must not hand them over on a tap.
        assertFalse(viewModel.state.value.reprompt, "the fixture must start with reprompt off")

        listOf("totpSecret", "cardCvv", "cardPin").forEach { field ->
            viewModel.toggleFieldRevealed(field)

            assertEquals(field, viewModel.state.value.pendingRevealField, "$field did not gate")
            assertFalse(field in viewModel.state.value.revealedFields, "$field was shown ungated")
            viewModel.onRevealDenied()
        }
    }

    @Test
    fun repromptGatesEveryFieldNotJustTheHighValueOnes() {
        viewModel.onRepromptChanged(true)

        viewModel.toggleFieldRevealed("password")

        assertEquals("password", viewModel.state.value.pendingRevealField)
        assertFalse("password" in viewModel.state.value.revealedFields)
    }

    @Test
    fun aDeniedPromptLeavesTheSecretMasked() {
        viewModel.toggleFieldRevealed("cardCvv")
        viewModel.onRevealDenied(R.string.itemedit_reveal_no_activity)

        assertFalse("cardCvv" in viewModel.state.value.revealedFields)
        assertNull(viewModel.state.value.pendingRevealField)
        assertEquals(R.string.itemedit_reveal_no_activity, viewModel.state.value.error)
    }

    @Test
    fun aPassedPromptRevealsExactlyTheFieldThatWasAskedFor() {
        viewModel.toggleFieldRevealed("cardCvv")
        viewModel.onRevealAuthenticated()

        assertTrue("cardCvv" in viewModel.state.value.revealedFields)
        assertFalse("cardPin" in viewModel.state.value.revealedFields, "authenticated too much")
        assertNull(viewModel.state.value.pendingRevealField)
    }

    @Test
    fun hidingAGatedFieldNeverPrompts() {
        // Only exposing a secret is a privileged act. Requiring a fingerprint to *re-mask* one would
        // mean the safe action is the expensive one, which is how a user learns to leave it revealed.
        viewModel.toggleFieldRevealed("cardCvv")
        viewModel.onRevealAuthenticated()

        viewModel.toggleFieldRevealed("cardCvv")

        assertFalse("cardCvv" in viewModel.state.value.revealedFields)
        assertNull(viewModel.state.value.pendingRevealField)
    }

    // ── a stored recovery phrase (§5.2) ───────────────────────────────────

    /**
     * The editor is a disclosure surface for a phrase it loaded, and it did not act like one.
     *
     * `PHRASE_FIELD` is in `ALWAYS_REAUTH_FIELDS` — a recovery phrase is the whole key to whatever
     * it opens and cannot be changed after it leaks — and the detail screen gates it. But `Edit`
     * navigates to `item/{id}/edit` with no challenge, `load()` puts the stored words into the grid,
     * and the Reveal pill used to flip a plain boolean. One ungated tap, twelve words.
     */
    @Test
    fun theRevealPillOnAStoredPhraseDemandsReauth() = runBlocking {
        val id = addPhrase()
        viewModel.loadItem(id.toString())
        waitUntil { viewModel.state.value.itemId == id }

        viewModel.togglePhraseReveal()

        assertEquals(PHRASE_FIELD, viewModel.state.value.pendingRevealField, "the pill did not gate")
        assertFalse(viewModel.state.value.phraseRevealAll, "a stored phrase was uncovered ungated")
    }

    /**
     * Gating the pill alone would have been theatre: focusing a slot loads its stored word into the
     * draft and the entry field draws the draft in clear, so the phrase stayed readable one word per
     * tap. The first tap is what is gated — after that the user must see what they are editing.
     */
    @Test
    fun theFirstSlotTapOnAStoredPhraseDemandsReauth() = runBlocking {
        val id = addPhrase()
        viewModel.loadItem(id.toString())
        waitUntil { viewModel.state.value.itemId == id }

        viewModel.focusPhraseSlot(0)

        assertEquals(PHRASE_FIELD, viewModel.state.value.pendingRevealField, "the slot tap did not gate")
        assertEquals("", viewModel.state.value.phraseDraft, "the stored word was loaded into the draft ungated")
        assertEquals(-1, viewModel.state.value.phraseFocus, "the slot was focused ungated")
    }

    /** The tap is parked, not thrown away — the prompt passes and the slot the user aimed at opens. */
    @Test
    fun aPassedPromptOpensTheSlotThatWasTapped() = runBlocking {
        val id = addPhrase()
        viewModel.loadItem(id.toString())
        waitUntil { viewModel.state.value.itemId == id }

        viewModel.focusPhraseSlot(1)
        viewModel.onRevealAuthenticated()

        assertTrue(viewModel.state.value.phraseRevealAll)
        assertEquals(1, viewModel.state.value.phraseFocus)
        assertEquals("ability", viewModel.state.value.phraseDraft)
    }

    /** A refused prompt leaves the whole phrase where it was: masked, unfocused, nothing queued. */
    @Test
    fun aDeniedPromptLeavesTheStoredPhraseMasked() = runBlocking {
        val id = addPhrase()
        viewModel.loadItem(id.toString())
        waitUntil { viewModel.state.value.itemId == id }

        viewModel.focusPhraseSlot(0)
        viewModel.onRevealDenied(R.string.itemedit_reveal_no_auth_available)

        assertFalse(viewModel.state.value.phraseRevealAll)
        assertEquals(-1, viewModel.state.value.phraseFocus)
        assertEquals("", viewModel.state.value.phraseDraft)
        assertNull(viewModel.state.value.pendingRevealField)
    }

    /**
     * The other half of the rule, so the gate cannot be "widened" into the capture screen by
     * accident: typing a phrase in is not disclosing a stored one, and a new item never prompts.
     */
    @Test
    fun capturingANewPhraseIsNotGated() {
        viewModel.presetType(ItemEditUiState.ItemType.PHRASE)

        viewModel.togglePhraseReveal()

        assertTrue(viewModel.state.value.phraseRevealAll, "a capture must uncover on a tap")
        assertNull(viewModel.state.value.pendingRevealField, "a capture must not prompt")
    }

    // ── hidden custom fields (§5.2) ───────────────────────────────────────

    /**
     * A hidden custom field is where the things with no dedicated box live: recovery codes, an
     * account PIN, a second password. On a reprompt item its own toggle used to flip the reveal set
     * directly, so the password demanded a fingerprint and the recovery codes beside it did not.
     */
    @Test
    fun aHiddenCustomFieldOnARepromptItemGatesLikeEveryOtherSecret() {
        viewModel.onRepromptChanged(true)
        viewModel.addCustomField()

        viewModel.toggleCustomFieldRevealed(0)

        assertEquals("custom:0", viewModel.state.value.pendingRevealField)
        assertFalse(0 in viewModel.state.value.revealedCustomFieldIndices, "revealed ungated")
    }

    @Test
    fun aPassedPromptRevealsTheCustomFieldThatWasAskedFor() {
        viewModel.onRepromptChanged(true)
        viewModel.addCustomField()
        viewModel.addCustomField()

        viewModel.toggleCustomFieldRevealed(1)
        viewModel.onRevealAuthenticated()

        assertTrue(1 in viewModel.state.value.revealedCustomFieldIndices)
        assertFalse(0 in viewModel.state.value.revealedCustomFieldIndices, "authenticated too much")
        assertNull(viewModel.state.value.pendingRevealField)
    }

    @Test
    fun aDeniedPromptLeavesTheCustomFieldMasked() {
        viewModel.onRepromptChanged(true)
        viewModel.addCustomField()

        viewModel.toggleCustomFieldRevealed(0)
        viewModel.onRevealDenied(R.string.itemedit_reveal_no_activity)

        assertFalse(0 in viewModel.state.value.revealedCustomFieldIndices)
        assertNull(viewModel.state.value.pendingRevealField)
    }

    @Test
    fun hidingACustomFieldNeverPrompts() {
        viewModel.onRepromptChanged(true)
        viewModel.addCustomField()
        viewModel.toggleCustomFieldRevealed(0)
        viewModel.onRevealAuthenticated()

        viewModel.toggleCustomFieldRevealed(0)

        assertFalse(0 in viewModel.state.value.revealedCustomFieldIndices)
        assertNull(viewModel.state.value.pendingRevealField)
    }

    /** Without reprompt a custom field is no more privileged than the password — no prompt. */
    @Test
    fun aCustomFieldOnAnUnmarkedItemRevealsWithoutAPrompt() {
        assertFalse(viewModel.state.value.reprompt, "the fixture must start with reprompt off")
        viewModel.addCustomField()

        viewModel.toggleCustomFieldRevealed(0)

        assertTrue(0 in viewModel.state.value.revealedCustomFieldIndices)
        assertNull(viewModel.state.value.pendingRevealField)
    }

    // ── copy gating (§5.2) ────────────────────────────────────────────────

    @Test
    fun copyingAnOrdinaryFieldIsGrantedWithoutReauth() {
        viewModel.requestCopy("password")

        assertEquals("password", viewModel.state.value.copyGrantedField)
        assertNull(viewModel.state.value.pendingRevealField, "an ordinary field must not prompt")
    }

    @Test
    fun copyingAHighValueFieldPromptsBeforeAnythingReachesTheClipboard() {
        // The gap this pins: reveal was gated while Copy — the same secret, one button over, and
        // the worse of the two because the value then leaves the FLAG_SECURE window — was not.
        assertFalse(viewModel.state.value.reprompt, "the fixture must start with reprompt off")

        listOf("totpSecret", "cardCvv", "cardPin").forEach { field ->
            viewModel.requestCopy(field)

            assertEquals(field, viewModel.state.value.pendingRevealField, "$field copy did not gate")
            assertNull(viewModel.state.value.copyGrantedField, "$field was copied ungated")
            viewModel.onRevealDenied()
        }
    }

    @Test
    fun repromptGatesCopyOfEveryFieldNotJustTheHighValueOnes() {
        viewModel.onRepromptChanged(true)

        viewModel.requestCopy("password")

        assertEquals("password", viewModel.state.value.pendingRevealField)
        assertNull(viewModel.state.value.copyGrantedField)
    }

    @Test
    fun aPassedPromptFinishesTheCopyTheUserAskedFor() {
        // Sending the user back to tap Copy a second time is how people learn to leave secrets
        // revealed instead, so the interrupted request is completed rather than dropped.
        viewModel.requestCopy("cardCvv")
        viewModel.onRevealAuthenticated()

        assertEquals("cardCvv", viewModel.state.value.copyGrantedField)
        assertTrue("cardCvv" in viewModel.state.value.revealedFields)

        viewModel.onCopyPerformed()
        assertNull(viewModel.state.value.copyGrantedField, "the grant outlived the copy")
    }

    @Test
    fun aDeniedPromptCopiesNothing() {
        viewModel.requestCopy("cardPin")
        viewModel.onRevealDenied(R.string.itemedit_reveal_no_activity)

        assertNull(viewModel.state.value.copyGrantedField, "a refused prompt still copied")
        assertNull(viewModel.state.value.pendingRevealField)
        assertFalse("cardPin" in viewModel.state.value.revealedFields)
    }

    @Test
    fun anAlreadyRevealedSecretCopiesWithoutAsecondPrompt() {
        // It is already on screen — demanding the fingerprint again to copy what the user can read
        // buys nothing and teaches them to reveal first and copy later.
        viewModel.toggleFieldRevealed("cardCvv")
        viewModel.onRevealAuthenticated()

        viewModel.requestCopy("cardCvv")

        assertEquals("cardCvv", viewModel.state.value.copyGrantedField)
        assertNull(viewModel.state.value.pendingRevealField)
    }

    @Test
    fun copyingASecretIsNotAnEdit() {
        // `copyGrantedField` and `copyAfterReveal` live in the same object as the user's typing, so
        // they have to be stripped by `editableOnly` too — otherwise tapping Copy on a form nobody
        // touched raises "Discard changes?" on the way out.
        viewModel.requestCopy("password")
        assertFalse(viewModel.isDirty(), "a granted copy reported as an unsaved edit")

        viewModel.onCopyPerformed()
        viewModel.requestCopy("cardCvv")
        assertFalse(viewModel.isDirty(), "a pending copy re-auth reported as an unsaved edit")

        viewModel.onRevealAuthenticated()
        assertFalse(viewModel.isDirty(), "a completed copy re-auth reported as an unsaved edit")
    }

    // ── unsaved-change tracking (§5.1) ────────────────────────────────────

    @Test
    fun aFreshFormIsNotDirty() {
        assertFalse(viewModel.isDirty())
    }

    @Test
    fun typingMakesItDirty() {
        viewModel.onTitleChanged("Bank")

        assertTrue(viewModel.isDirty())
    }

    @Test
    fun revealingASecretIsNotAnEdit() {
        // The whole reason isDirty compares snapshots through `editableOnly` — reveal state, the
        // ticking TOTP code and entitlement all live in the same object as the user's typing, and
        // any of them leaking in means "discard your changes?" on a form nobody touched.
        viewModel.toggleFieldRevealed("password")
        assertFalse(viewModel.isDirty(), "revealing a field reported as an unsaved edit")

        viewModel.toggleFieldRevealed("cardCvv")
        assertFalse(viewModel.isDirty(), "a pending re-auth reported as an unsaved edit")
        viewModel.onRevealDenied()
        assertFalse(viewModel.isDirty(), "a denied re-auth reported as an unsaved edit")
    }

    @Test
    fun loadingAnExistingItemIsNotDirty() = runBlocking {
        val id = addLogin(username = "ada@example.com", password = "hunter2")

        viewModel.loadItem(id.toString())

        assertFalse(viewModel.isDirty(), "just opening an item warned about unsaved changes")
    }

    @Test
    fun savingClearsTheDirtyFlag() = runBlocking {
        viewModel.onTitleChanged("Bank")
        viewModel.onPasswordChanged("s3cret")
        assertTrue(viewModel.isDirty())

        viewModel.save()
        waitUntil { viewModel.state.value.saved }

        assertFalse(viewModel.isDirty(), "still dirty after a successful save")
    }

    @Test
    fun aPasswordTheAppSeededIsNotDirty() {
        // The add sheet's "Password" row opens this form with a value already in it. The user asked
        // for an item, not for that string, so backing straight out must not claim they have edits.
        // Reported from a phone, 2026-08-19: "what changes? i literally just clicked".
        viewModel.seedGeneratedPassword()

        assertTrue(
            viewModel.state.value.password.isNotEmpty(),
            "the seed produced nothing — this test would pass for the wrong reason",
        )
        assertFalse(viewModel.isDirty(), "an untouched seeded password reported as an unsaved edit")
    }

    @Test
    fun editingASeededPasswordMakesItDirtyAgain() {
        viewModel.seedGeneratedPassword()
        viewModel.onPasswordChanged(viewModel.state.value.password + "!")

        assertTrue(viewModel.isDirty(), "a seeded password the user edited was silently forgiven")
    }

    @Test
    fun regeneratingASeededPasswordMakesItDirty() {
        // `generatePassword` is the user's own regenerate tap and keeps the old semantics. Recording
        // the seed inside it — rather than in its own entry point — would have forgiven this too.
        viewModel.seedGeneratedPassword()
        viewModel.generatePassword()

        assertTrue(viewModel.isDirty(), "a regenerated password was treated as the app's own seed")
    }

    @Test
    fun undoingAGeneratedPasswordPutsTheStoredSecretBackBehindItsGate() {
        // Red before the fix: `undoGeneratedPassword` restored the value and nothing else, into a
        // field `generatePassword` had already added to `revealedFields`. On a reprompt item that
        // put the real stored password on screen in the clear, and - because `requestCopy`
        // short-circuits on `field in revealedFields` - one tap from the clipboard, with no
        // biometric or password check on either route. The dice needs no reveal to reach, so the
        // whole bypass was two taps: regenerate, undo.
        viewModel.onRepromptChanged(true)
        viewModel.onPasswordChanged("the-stored-secret")
        assertFalse("password" in viewModel.state.value.revealedFields, "fixture must start masked")

        viewModel.generatePassword()
        viewModel.undoGeneratedPassword()

        assertEquals("the-stored-secret", viewModel.state.value.password, "undo lost the value")
        assertFalse(
            "password" in viewModel.state.value.revealedFields,
            "undo put the stored secret back on screen unmasked, past the reprompt gate",
        )
    }

    @Test
    fun undoingAGeneratedPasswordLeavesAnAlreadyRevealedFieldRevealed() {
        // The control for the test above. Re-masking unconditionally would be its own defect: a
        // user who had deliberately revealed the field before regenerating would watch it slam
        // shut on undo, and would reasonably read that as the undo having failed.
        viewModel.onPasswordChanged("the-stored-secret")
        viewModel.toggleFieldRevealed("password")
        assertTrue("password" in viewModel.state.value.revealedFields, "fixture must start shown")

        viewModel.generatePassword()
        viewModel.undoGeneratedPassword()

        assertEquals("the-stored-secret", viewModel.state.value.password)
        assertTrue(
            "password" in viewModel.state.value.revealedFields,
            "undo re-masked a field the user had already chosen to reveal",
        )
    }

    @Test
    fun aPasswordHandedOverByTheGeneratorIsStillDirty() {
        // The other door into a pre-filled password field, and the one that must keep prompting:
        // the user generated this value and asked to save it, so it exists nowhere else. Forgiving
        // both doors would bin it silently on a stray Back.
        com.localkeep.core.common.PendingSecret.offer("correct-horse-battery")
        viewModel.adoptGeneratedPassword()

        assertEquals("correct-horse-battery", viewModel.state.value.password)
        assertTrue(viewModel.isDirty(), "a Generator handover was forgiven like an app-minted seed")
    }

    /**
     * The handoff must show what it just handed off.
     *
     * `generatePassword()` reveals the value it writes on the next line, because it "was made by
     * the user's own tap a moment ago -- it is not a stored secret being disclosed." That reasoning
     * is identical for a password generated on the Generator tab and handed to this form via "Save
     * as a new login" -- same user, same tap, seconds earlier -- yet only `adoptGeneratedPassword()`
     * left the field masked, until this fix (2026-09-13).
     */
    @Test
    fun aPasswordAdoptedFromTheGeneratorArrivesAlreadyRevealed() {
        com.localkeep.core.common.PendingSecret.offer("correct-horse-battery")
        viewModel.adoptGeneratedPassword()

        assertTrue(
            "password" in viewModel.state.value.revealedFields,
            "adoptGeneratedPassword() left the handed-over password masked; generatePassword() " +
                "reveals the value it writes for the identical reason",
        )
    }

    /**
     * The control for the fix above: only the just-generated handoff is granted the exception.
     * Opening a previously-saved login must still start masked -- nothing about reprompt gating
     * changes, and a new item's `reprompt` still defaults to `false` regardless of this reveal.
     */
    @Test
    fun openingAPreviouslySavedLoginDoesNotArriveRevealed() = runBlocking {
        val id = addLogin(username = "octocat", password = "correct-horse-battery")
        viewModel.loadItem(id.toString())
        waitUntil { viewModel.state.value.itemId == id }

        assertFalse(
            "password" in viewModel.state.value.revealedFields,
            "an opened, previously-saved login must still start masked",
        )
    }

    @Test
    fun aPasswordTypedIntoAFreshFormIsDirty() {
        viewModel.onPasswordChanged("correct-horse")

        assertTrue(viewModel.isDirty())
    }

    @Test
    fun editingBackToTheOriginalValueIsNotDirty() {
        // Falls out of comparing snapshots rather than setting a flag on every keystroke, and it is
        // worth pinning: a typo typed and deleted must not cost the user a confirmation dialog.
        viewModel.onTitleChanged("Bank")
        viewModel.onTitleChanged("")

        assertFalse(viewModel.isDirty())
    }

    // ── the Login ↔ TOTP round trip through toContent() ───────────────────

    @Test
    fun aLoginKeepsItsSecondFactorAcrossLoadAndSave() = runBlocking {
        // The C4 gap in one test. A login's seed was imported and persisted correctly but nothing
        // read it back, so it was invisible; making it visible then risked the opposite failure,
        // where re-saving an untouched login silently dropped it.
        val id = addLogin(
            username = "ada@example.com",
            password = "hunter2",
            totp = Totp(
                issuer = "GitHub",
                account = "ada",
                secretBase32 = "JBSWY3DPEHPK3PXP",
                algorithm = TotpAlgorithm.SHA1,
                digits = 6,
                periodSec = 30,
            ),
        )

        viewModel.loadItem(id.toString())
        assertEquals("JBSWY3DPEHPK3PXP", viewModel.state.value.totpSecret, "seed never surfaced")
        assertEquals("GitHub", viewModel.state.value.totpIssuer)

        viewModel.onTitleChanged("GitHub (work)")
        viewModel.save()
        waitUntil { viewModel.state.value.saved }

        val saved = repository.getAllItems().first { it.id == id }.content as Login
        assertNotNull(saved.totp, "re-saving an untouched login dropped its second factor")
        assertEquals("JBSWY3DPEHPK3PXP", saved.totp?.secretBase32)
        assertEquals(30, saved.totp?.periodSec)
    }

    @Test
    fun removingALoginsSecondFactorActuallyPersists() = runBlocking {
        // The counterpart failure. `original.copy(...)` used to carry `totp` through untouched,
        // which was correct while nothing could edit it and silently wrong the moment something
        // could — the field would vanish from the form and come straight back on reload.
        val id = addLogin(
            username = "ada@example.com",
            password = "hunter2",
            totp = Totp("GitHub", "ada", "JBSWY3DPEHPK3PXP", TotpAlgorithm.SHA1, 6, 30),
        )

        viewModel.loadItem(id.toString())
        viewModel.removeLoginTotp()
        viewModel.save()
        waitUntil { viewModel.state.value.saved }

        val saved = repository.getAllItems().first { it.id == id }.content as Login
        assertNull(saved.totp, "the seed came back after being removed")
    }

    @Test
    fun removingASecondFactorOnlyAppliesToLogins() {
        // A standalone TOTP item IS its secret; letting the same action blank it would leave an
        // item that cannot be saved and has nothing left to identify it.
        viewModel.onTypeChanged(ItemEditUiState.ItemType.TOTP)
        viewModel.onTotpSecretChanged("JBSWY3DPEHPK3PXP")

        viewModel.removeLoginTotp()

        assertEquals("JBSWY3DPEHPK3PXP", viewModel.state.value.totpSecret)
    }

    @Test
    fun reSavingALoginKeepsItsImportedPerUriMatchStrategy() = runBlocking {
        // Found 2026-08-06 (high severity). The editor held only the newline-
        // joined URI strings, so saving rebuilt every URI as `UriMatch(uri)` — silently flattening
        // an imported HOST/EXACT/STARTS_WITH/REGEX/NEVER rule back to DOMAIN. For a NEVER rule that
        // re-enables autofill on the exact site the user excluded it from. The strategy must ride
        // the round trip; only a genuinely new URI may take the DOMAIN default.
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = "Exact-match bank",
                createdAt = now,
                updatedAt = now,
                content = Login(
                    username = "ada",
                    password = "hunter2",
                    uris = listOf(
                        UriMatch(uri = "https://bank.example.com/login", strategy = MatchStrategy.EXACT),
                        UriMatch(uri = "https://login.example.org", strategy = MatchStrategy.NEVER),
                    ),
                ),
            ),
        )

        viewModel.loadItem(id.toString())
        // A metadata-only edit, the kind that used to destroy the strategy.
        viewModel.onTitleChanged("Exact-match bank (primary)")
        viewModel.save()
        waitUntil { viewModel.state.value.saved }

        val saved = repository.getAllItems().first { it.id == id }.content as Login
        assertEquals(
            MatchStrategy.EXACT,
            saved.uris.first { it.uri == "https://bank.example.com/login" }.strategy,
            "an imported EXACT rule was flattened back to DOMAIN on save",
        )
        assertEquals(
            MatchStrategy.NEVER,
            saved.uris.first { it.uri == "https://login.example.org" }.strategy,
            "a NEVER rule was re-enabled by being flattened to DOMAIN on save",
        )
    }

    @Test
    fun aNewUriTakesTheDefaultMatchStrategy() = runBlocking {
        // The flip side: a URI the user actually adds in the editor has no imported strategy to
        // preserve, so it must come out DOMAIN — the same as before the fix.
        val id = addLogin(username = "ada", password = "hunter2")

        viewModel.loadItem(id.toString())
        viewModel.addUri()
        viewModel.onUriChanged(0, "https://bank.example.com/login")
        viewModel.save()
        waitUntil { viewModel.state.value.saved }

        val saved = repository.getAllItems().first { it.id == id }.content as Login
        assertEquals(
            MatchStrategy.DOMAIN,
            saved.uris.single().strategy,
            "a freshly-typed URI should get the DOMAIN default, not an inherited rule",
        )
    }

    @Test
    fun aLoginWithNoSeedSavesWithNoTotpRatherThanAnEmptyOne() = runBlocking {        viewModel.onTitleChanged("Plain login")
        viewModel.onUsernameChanged("ada@example.com")
        viewModel.onPasswordChanged("hunter2")

        viewModel.save()
        waitUntil { viewModel.state.value.saved }

        val saved = repository.getAllItems().first { it.title == "Plain login" }.content as Login
        assertNull(saved.totp, "an empty seed was persisted as a Totp instead of null")
    }

    /**
     * QA 2026-10-03: a username or password of only spaces was stored as typed, while the view, the
     * list's quick copy and Health all read blank as absent — so the editor showed a password every
     * other screen denied. Stored as nothing now, as a whitespace-only website already is.
     */
    @Test
    fun aWhitespaceOnlyUsernameAndPasswordAreStoredAsNothing() = runBlocking {
        viewModel.onTitleChanged("Spaces")
        viewModel.onUsernameChanged(" ")
        viewModel.onPasswordChanged("    ")

        viewModel.save()
        waitUntil { viewModel.state.value.saved }

        val saved = repository.getAllItems().first { it.title == "Spaces" }.content as Login
        assertEquals("", saved.username)
        assertEquals("", saved.password)
    }

    /**
     * QA 2026-10-03: a login whose 2FA secret cannot be read opened the naming step anyway, and the
     * step's Save then answered only "Check the item fields" while the reason sat on the form behind
     * the sheet. The naming step is for items that can be saved; this one is refused on the form.
     */
    @Test
    fun aMalformedTwoFactorSecretIsRefusedBeforeTheNamingStep() = runBlocking {
        viewModel.onUsernameChanged("ada@example.com")
        viewModel.onTotpSecretChanged("not base32 !!")

        assertFalse(viewModel.readyToName(), "the naming step opened over an item that cannot save")
        assertEquals(R.string.itemedit_error_check_fields, viewModel.state.value.error)
    }

    /**
     * QA 2026-10-03: a note grown to ~300,000 characters by pasting froze the editor for minutes,
     * long enough to auto-lock behind the ANR dialog and lose it. A notes field stops growing at
     * [MAX_NOTE_CHARS]; a paste that would cross it is refused whole rather than cut mid-key, and a
     * note already past it (an import) can still be shortened.
     */
    @Test
    fun aNoteStopsGrowingAtItsCap() = runBlocking {
        val atCap = "a".repeat(MAX_NOTE_CHARS)
        viewModel.onLoginNotesChanged(atCap)
        assertEquals(MAX_NOTE_CHARS, viewModel.state.value.loginNotes.length, "control: the cap itself is allowed")

        viewModel.onLoginNotesChanged(atCap + "b")
        assertEquals(atCap, viewModel.state.value.loginNotes, "a change past the cap was taken")

        viewModel.onNoteBodyChanged("x".repeat(MAX_NOTE_CHARS * 2))
        assertEquals("", viewModel.state.value.noteBody, "an oversized paste was taken, whole or in part")
    }

    /**
     * QA 2026-10-03: a lock in the background dropped an unsaved new login — a generated password
     * the user had gone off to set on a website included — and nothing ever said so. The draft
     * still goes; the next unlock says it went.
     */
    @Test
    fun aLockThatDiscardsUnsavedWorkLeavesANoticeForTheNextUnlock() = runBlocking {
        com.localkeep.core.ui.LkUnlockNotices.take()
        viewModel.onTitleChanged("Half-typed")

        lockManager.lock()

        // A loop of its own rather than waitUntil: `take()` consumes, and waitUntil re-checks.
        var queued: Int? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (queued == null && System.currentTimeMillis() < deadline) {
            queued = com.localkeep.core.ui.LkUnlockNotices.take()
            if (queued == null) Thread.sleep(10)
        }
        assertEquals(R.string.itemedit_notice_discarded_on_lock, queued)
    }

    @Test
    fun aLockOverAnUntouchedFormSaysNothing() = runBlocking {
        com.localkeep.core.ui.LkUnlockNotices.take()

        lockManager.lock()
        kotlinx.coroutines.delay(1_000)

        assertNull(com.localkeep.core.ui.LkUnlockNotices.take(), "an empty form had nothing to lose")
    }

    @Test
    fun theTickerRunsForALoginsSeedAndNotJustAStandaloneTotp() = runBlocking {
        val id = addLogin(
            username = "ada@example.com",
            password = "hunter2",
            totp = Totp("GitHub", "ada", "JBSWY3DPEHPK3PXP", TotpAlgorithm.SHA1, 6, 30),
        )

        viewModel.loadItem(id.toString())

        // loadItem refreshes the code synchronously, so this needs no waiting.
        assertEquals(6, viewModel.state.value.totpCurrentCode.length, "no code generated for a login")
    }

    // ── Pro gating ────────────────────────────────────────────────────────

    // Both halves of the free-card rule, because it is a *cap* and a cap needs its open side pinned
    // too. These replace `aFreeBuildCannotSwitchTheFormToAProType`, which asserted that a free build
    // could never select CARD at all. That was true when it was written and stopped being true on
    // 2026-09-03, when one card and one identity became free and `ProTypeQuota.FREE_LIMIT` started
    // limiting *creation* rather than the type. The test had been failing at HEAD ever since —
    // found 2026-09-06 while proving an unrelated fix failed first, when it turned up red with every
    // local change reverted. A test asserting superseded behaviour is worse than no test: it is a red
    // gate that hides the next real regression behind a failure everyone has learned to expect.
    //
    // (Phrasing deliberate: a review gate searches for the literal marker fix agents leave on a
    // temporary revert, and this file is inside its search path. A comment that trips it teaches
    // people to skip the scan, which costs more than the sentence is worth.)
    //
    // Worth stating plainly: `onTypeChanged` has **no production call site** — `ItemEditViewModel`
    // says so at `blockedBeforeTitle`, and the add sheet routes through `presetType` and its own
    // quota dialog, covered by `AddSheetLockedTypesTest`. So these two are a backstop on a deep-link
    // or stale-route path, not on the journey a user takes.

    @Test
    fun aFreeVaultAtTheCardCapCannotSwitchTheFormToCard() = runBlocking {
        // One card already stored is the whole cap, so the next one is refused.
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = UUID.randomUUID(),
                title = "Everyday card",
                createdAt = now,
                updatedAt = now,
                content = Card(holder = "Jane Doe", number = "4111111111111111", brand = CardBrand.VISA),
            ),
        )

        viewModel.onTypeChanged(ItemEditUiState.ItemType.CARD)

        assertEquals(ItemEditUiState.ItemType.LOGIN, viewModel.state.value.type)
        assertTrue(viewModel.state.value.showProPrompt, "the gate refused silently")
    }

    @Test
    fun aFreeVaultBelowTheCardCapCanSwitchTheFormToCard() {
        // The open side of the cap, and the half whose absence let the old test rot unnoticed: with
        // an empty vault the free tier is entitled to its one card, so the switch must go through
        // and no Pro prompt may appear. A guard written as "free builds never get cards" passes
        // every test above while being wrong about the product.
        viewModel.onTypeChanged(ItemEditUiState.ItemType.CARD)

        assertEquals(ItemEditUiState.ItemType.CARD, viewModel.state.value.type)
        assertFalse(viewModel.state.value.showProPrompt, "the first card is free — nothing to sell")
    }

    // ── save-refusal visibility (2026-07-29 hand-test, defect 1) ──────────

    @Test
    fun savingWithABlankTitleMarksTheTitleField() {
        viewModel.save()
        assertTrue(viewModel.state.value.titleError, "the refused field must identify itself")
        assertNotNull(viewModel.state.value.error, "the sentence saying why must still exist")
        assertFalse(viewModel.state.value.isSaving)
    }

    @Test
    fun typingInTheTitleClearsItsError() {
        viewModel.save()
        assertTrue(viewModel.state.value.titleError)
        viewModel.onTitleChanged("B")
        assertFalse(viewModel.state.value.titleError, "a stale red ring is a new lie")
    }

    @Test
    fun aTotpWithoutASecretIsRefusedWithASpecificSentence() {
        viewModel.onTypeChanged(ItemEditUiState.ItemType.TOTP)
        viewModel.onTitleChanged("GitHub")
        viewModel.save()
        // 2026-07-30 i18n extraction: `error` is now a `@StringRes Int`, so "names the field, not a
        // generic parser complaint" is checked by identity against the specific resource rather than
        // a substring match on English text — R.string.itemedit_error_check_fields is the generic
        // fallback this must NOT be.
        assertEquals(R.string.itemedit_error_totp_secret_required, viewModel.state.value.error)
    }

    // ── the folder the user typed (2026-07-29 hand-test, defect 6) ────────

    @Test
    fun saveCommitsAPendingFolderNameInsteadOfDroppingIt() = runBlocking {
        viewModel.onTitleChanged("Bank")
        viewModel.showNewFolderInput()
        viewModel.onNewFolderNameChanged("Finance")

        viewModel.save()
        waitUntil { viewModel.state.value.saved }

        val state = viewModel.state.value
        val created = state.folders.firstOrNull { it.name == "Finance" }
        assertNotNull(created, "Save must commit the folder the user typed, exactly as the tick would")
        assertEquals(created.id, state.folderId, "and the item must land in it")
        assertFalse(state.showNewFolderInput)
        assertEquals(
            created.id,
            repository.getAllItems().first { it.title == "Bank" }.folderId,
            "the saved item must carry the folder, not the null it had when Save was pressed",
        )
    }

    /**
     * 2026-10-14: "Clicking create folder should hide the textbox menu ... it has to be a small reset
     * each time one adds a new folder." This reverses 2026-10-12's keep-the-card-open run: Create
     * collapses the card, clears the name and files the item in the new folder, and the next folder
     * starts again from New folder.
     */
    @Test
    fun creatingAFolderFromThePickerCollapsesTheCardAndSelectsIt() = runBlocking {
        viewModel.showNewFolderInput()
        viewModel.onNewFolderNameChanged("Alpha")
        viewModel.createFolder()
        waitUntil { viewModel.state.value.folders.any { it.name == "Alpha" } && !viewModel.state.value.showNewFolderInput }

        var state = viewModel.state.value
        assertFalse(state.showNewFolderInput, "the card must collapse after a successful create")
        assertEquals("", state.newFolderName)
        assertEquals(state.folders.first { it.name == "Alpha" }.id, state.folderId)

        // The next folder is a fresh open, not a continuation.
        viewModel.showNewFolderInput()
        assertTrue(viewModel.state.value.showNewFolderInput)
        viewModel.onNewFolderNameChanged("Beta")
        viewModel.createFolder()
        waitUntil { viewModel.state.value.folders.any { it.name == "Beta" } && !viewModel.state.value.showNewFolderInput }

        state = viewModel.state.value
        assertEquals(setOf("Alpha", "Beta"), state.folders.map { it.name }.toSet())
        assertEquals(state.folders.first { it.name == "Beta" }.id, state.folderId)
    }

    /** Control: a refused create (duplicate sibling) keeps the card open with the typed name and the error. */
    @Test
    fun aRefusedFolderCreateKeepsTheCardOpenWithTheNameAndTheError() = runBlocking {
        viewModel.showNewFolderInput()
        viewModel.onNewFolderNameChanged("Work")
        viewModel.createFolder()
        waitUntil { viewModel.state.value.folders.any { it.name == "Work" } && !viewModel.state.value.showNewFolderInput }

        viewModel.showNewFolderInput()
        viewModel.onNewFolderNameChanged("work")
        viewModel.createFolder()
        waitUntil { viewModel.state.value.error != null }

        val state = viewModel.state.value
        assertTrue(state.showNewFolderInput, "a refusal must not close the card")
        assertEquals("work", state.newFolderName)
        assertEquals(1, state.folders.count { it.name.equals("work", ignoreCase = true) })
    }

    @Test
    fun saveStillRefusesADuplicatePendingFolderName() = runBlocking {
        viewModel.onTitleChanged("Bank")
        viewModel.showNewFolderInput()
        viewModel.onNewFolderNameChanged("Work")
        viewModel.createFolder()
        waitUntil { viewModel.state.value.folders.any { it.name == "Work" } }

        viewModel.showNewFolderInput()
        viewModel.onNewFolderNameChanged("work") // case-insensitive duplicate
        viewModel.save()
        // On the outcome, not on `!isSaving`: that is also true in the instant before the save has
        // started, and the full class run on API 36 read the state there (2026-10-04).
        waitUntil { viewModel.state.value.error != null || viewModel.state.value.saved }

        assertNotNull(viewModel.state.value.error, "the duplicate refusal must surface, not vanish")
        assertFalse(viewModel.state.value.saved, "a refused folder must not carry the item through")
    }

    // ── save-path validation ──────────────────────────────────────────────

    @Test
    fun anInvalidCardExpiryIsReportedAndNothingIsWritten() = runBlocking {
        val pro = proViewModel()
        pro.onTypeChanged(ItemEditUiState.ItemType.CARD)
        pro.onTitleChanged("Bad card")
        pro.onCardExpMonthChanged("13")

        pro.save()

        // 2026-07-30 i18n extraction: toContent()'s `require()` message ("Expiration month must be
        // 1-12") is internal validation text (checkNoHardcodedUiStrings's own `internalOnly`
        // exclusion; the do-not-translate list's "never user-facing" category) and is no longer echoed via
        // `it.message` into the UI — see the comment on that catch in ItemEditViewModel.save(). The
        // user still sees a refusal; it is now the generic fallback rather than the specific reason,
        // because the inline `isError` ring already showed the specific reason before Save was
        // reachable.
        assertEquals(R.string.itemedit_error_check_fields, pro.state.value.error)
        assertFalse(pro.state.value.saved)
        assertTrue(repository.getAllItems().none { it.title == "Bad card" }, "a rejected item was written")
    }

    @Test
    fun aValidCardRoundTripsItsGatedFields() = runBlocking {
        val pro = proViewModel()
        pro.onTypeChanged(ItemEditUiState.ItemType.CARD)
        pro.onTitleChanged("Visa")
        pro.onCardNumberChanged("4111111111111111")
        pro.onCardCvvChanged("123")
        pro.onCardPinChanged("9876")
        pro.onCardExpMonthChanged("12")
        pro.onCardExpYearChanged("2030")

        pro.save()
        waitUntil { pro.state.value.saved }

        val saved = repository.getAllItems().first { it.title == "Visa" }.content as Card
        assertEquals("123", saved.cvv)
        assertEquals("9876", saved.pin)
        assertEquals(12, saved.expMonth)
        assertEquals(2030, saved.expYear)
    }

    // ── folder delete trashes the folder with its members (2026-08-18) ────
    //
    // Was "delete un-buckets its members" (QA 2026-08-06, A18 M3). That was the right answer only
    // while folder deletion was irreversible; `confirmDeleteFolder`'s own KDoc records why it stopped
    // being one. Scattering an item into the parent is now the surprising outcome, and the folder
    // trash is what makes losing nothing possible.

    @Test
    fun deletingAFolderTrashesItAndItsItemsWithoutUnfilingThem() = runBlocking {
        val folderId = UUID.randomUUID()
        repository.mutate { it.folders.add(Folder(id = folderId, name = "Finance", updatedAt = Instant.now())) }
        val now = Instant.now()
        val itemId = UUID.randomUUID()
        repository.addItem(
            VaultItem(
                id = itemId,
                title = "Filed login",
                createdAt = now,
                updatedAt = now,
                folderId = folderId,
                content = Login(username = "jane", password = "pw"),
            ),
        )

        // Straight at the repository since 2026-08-19: the item editor's chooser is the comp's, which
        // has no trash, so the ViewModel no longer wraps this call. The invariant under test was
        // never the wrapper — it is that deleting a folder trashes its members *with* it rather than
        // unfiling them — and that lives here.
        repository.deleteFolder(folderId)
        // Wait on the committed vault itself, not on view state: this VM never loaded these folders
        // (they were seeded through the repository), so its state.folders was empty all along and
        // would pass instantly, racing ahead of the async mutate. The folder no longer *leaves* the
        // vault, so what is awaited is the trash mark rather than its absence.
        waitUntil {
            val vault = (lockManager.lockState.value as? LockState.Unlocked)?.vault
            vault != null && vault.folders.any { it.id == folderId && it.deletedAt != null }
        }

        // Read from the committed vault, not through `getAllItems()`: that accessor filters out
        // exactly what this test asserts (`filterNot(VaultItem::isDeleted)`), and `getTrashItems()`
        // drops it too — an item that went down with its folder carries `trashedWithFolderId`, so it
        // is represented by the folder's row rather than one of its own. Asking either would have
        // made "trashed correctly" indistinguishable from "destroyed".
        val vault = (lockManager.lockState.value as? LockState.Unlocked)?.vault
        val item = vault?.items?.firstOrNull { it.id == itemId }
        assertNotNull(item, "the item must survive — delete trashes, it does not destroy")
        assertEquals(
            folderId,
            item.folderId,
            "it must stay filed, or restoring the folder returns an empty one",
        )
        assertTrue(item.isDeleted, "and it must be in the trash, not left live under a trashed parent")
    }

    // ── a new card needs a number, the way the wizard needs one (found 2026-09-06) ────

    @Test
    fun aNewCardWithNoNumberIsRefusedTheWayTheWizardRefusesIt() = runBlocking {
        // [CardWizard] gates its number step on `canAdvance = digits.isNotEmpty()`, so a blank card
        // cannot be walked out of it. The flat "Enter everything at once" form is the same editor
        // over the same state and had no equivalent, so it saved cards that were nothing but a
        // title. Driven on a device 2026-09-06: a second card reached the vault list carrying only
        // the number, and a form with every field empty rendered Save fully enabled.
        val pro = proViewModel()
        pro.onTypeChanged(ItemEditUiState.ItemType.CARD)
        pro.onTitleChanged("Travel card")

        pro.save()

        assertFalse(pro.state.value.saved, "a card that is only a title must not reach the vault")
        assertEquals(R.string.itemedit_error_check_fields, pro.state.value.error)
    }

    @Test
    fun aNewCardWithANumberStillSaves() = runBlocking {
        // The control for the test above. Without it, a guard that refused *every* card would pass
        // it — the same shape as the Pro-badge test from sweep 3, which guards against the wrong fix
        // rather than only against the defect.
        val pro = proViewModel()
        pro.onTypeChanged(ItemEditUiState.ItemType.CARD)
        pro.onTitleChanged("Travel card")
        pro.onCardNumberChanged("4111111111111111")

        pro.save()
        waitUntil { pro.state.value.saved }

        assertTrue(pro.state.value.saved, "a card with a number is an ordinary save")
    }

    @Test
    fun editingAStoredCardThatHasNoNumberIsStillAllowed() = runBlocking {
        // The guard is scoped to creation on purpose. A stored card with no number is an ordinary
        // record — an import that carried a cardholder and an expiry and nothing else — and refusing
        // to write to it would hold the user's own data hostage. Same line the allowance gate above
        // draws between creating and editing.
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = "Imported card",
                createdAt = now,
                updatedAt = now,
                content = Card(holder = "Jane Doe", number = "", brand = CardBrand.UNKNOWN),
            ),
        )
        val pro = proViewModel()
        pro.loadItem(id.toString())
        waitUntil { pro.state.value.itemId == id }

        pro.onCardHolderChanged("Jane A Doe")
        pro.save()
        waitUntil { pro.state.value.saved }

        val saved = repository.getAllItems().first { it.id == id }.content as Card
        assertEquals("Jane A Doe", saved.holder, "an edit to a stored numberless card must go through")
    }

    // ── an imported card brand survives an edit (QA 2026-08-06, A18 L2) ────

    @Test
    fun savingACardKeepsAnImportedBrandWhenThePanIsNotRecognised() = runBlocking {
        // A loyalty/co-brand number the IIN ranges do not match, carrying a brand the importer named.
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = "Loyalty",
                createdAt = now,
                updatedAt = now,
                content = Card(holder = "Jane Doe", number = "9700123456", brand = CardBrand.VISA),
            ),
        )
        // Cards are Pro (§11), so the save path needs an entitled VM — the free one refuses.
        val pro = proViewModel()
        pro.loadItem(id.toString())
        waitUntil { pro.state.value.itemId == id }

        pro.save()
        waitUntil { pro.state.value.saved }

        val saved = repository.getAllItems().first { it.id == id }.content as Card
        assertEquals(
            CardBrand.VISA,
            saved.brand,
            "an imported brand must not be flattened to UNKNOWN on save when the PAN is unrecognised",
        )
    }

    // ── attachments changed elsewhere survive an edit's save (O4-01, 2026-10-02) ──

    /**
     * Edit, change a field, Manage attachments, attach, Back, Save. The attachments screen writes
     * through `updateWith` without bumping `updatedAt`, and a dirty form is never reloaded, so the
     * save wrote its load-time list and dropped the new record — whose blob the next unlock's sweep
     * then deleted. JVM half: `EditKeepsAttachmentsMadeElsewhereTest`.
     */
    @Test
    fun anAttachmentAddedWhileTheFormIsDirtySurvivesItsSave() = runBlocking {
        val id = addLogin("me@example", "hunter2")
        viewModel.loadItem(id.toString())
        waitUntil { viewModel.state.value.itemId == id }
        viewModel.onTitleChanged("GitHub (work)")
        assertTrue(viewModel.isDirty(), "control: the form has unsaved work, so it is not reloaded")

        // What AttachmentPickerViewModel.persist does from the attachments screen.
        val scan = Attachment(id = UUID.randomUUID(), filename = "passport.jpg", mime = "image/jpeg", blobRef = "b1")
        repository.updateWith(id) { it.copy(attachments = it.attachments + scan) }

        viewModel.save()
        waitUntil { viewModel.state.value.saved }

        val stored = repository.getAllItems().first { it.id == id }
        assertEquals("GitHub (work)", stored.title, "control: the edit itself landed")
        assertEquals(listOf(scan), stored.attachments, "the save dropped the attachment added meanwhile")
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private suspend fun addLogin(
        username: String,
        password: String,
        totp: Totp? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = "GitHub",
                createdAt = now,
                updatedAt = now,
                content = Login(username = username, password = password, totp = totp),
            ),
        )
        return id
    }

    /** A stored twelve-word phrase. Real BIP-39 words; the checksum is irrelevant to a reveal gate. */
    private suspend fun addPhrase(): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = "Ledger",
                createdAt = now,
                updatedAt = now,
                content = SeedPhrase(
                    words = listOf(
                        "abandon", "ability", "able", "about", "above", "absent",
                        "absorb", "abstract", "absurd", "abuse", "access", "accident",
                    ),
                    passphrase = "the twenty fifth",
                ),
            ),
        )
        return id
    }

    /** Spins until [condition] holds — `save()` completes on the ViewModel's own scope. */
    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(condition(), "timed out waiting for the ViewModel to settle")
    }

    // ── the item is loaded before the first frame, not after it ──────────

    /**
     * The flicker, stated as an invariant.
     *
     * A `LaunchedEffect` runs after the frame that created it has been drawn. So while the detail
     * screen started the lookup from one, every entry painted a frame with no identity in it — a
     * placeholder tile and skeleton bars — and only then the item. Measured on an emulator with the
     * screen logging its own draws: `DRAW skeleton` 42 ms after composition, `loadItem ENTER` 2 ms
     * after that, `DRAW content` 34 ms later.
     *
     * Asserting on construction rather than on frames is deliberate, and is the call
     * `ItemDetailLoadGuardTest` and `MonogramMorphTest` both made: `loadItem` has no suspension
     * point, `StateFlow` conflates what no collector was scheduled to see, and an "assert frame one,
     * advance, assert frame two" test races straight through the state it exists to catch. If this
     * passes there is no frame in which the screen could draw the wrong thing, because there is no
     * moment at which the state holds it.
     */
    @Test
    fun theRoutesItemIsLoadedBeforeAnythingCanCompose() = runBlocking {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = "Acme Mail",
                createdAt = now,
                updatedAt = now,
                content = Login(username = "ada", password = "hunter2"),
            ),
        )

        // Nothing is called on it. Construction is the whole test.
        val fresh = ItemEditViewModel(
            repository,
            lockManager,
            FreeEntitlement(),
            SavedStateHandle(mapOf(ROUTE_ITEM_ID to id.toString())),
        )

        assertEquals(id, fresh.state.value.itemId, "the state must describe the route's item")
        assertEquals("Acme Mail", fresh.state.value.title)
        assertEquals("ada", fresh.state.value.username)
        assertFalse(fresh.state.value.isNew)
        assertFalse(
            isShowingWrongItem(fresh.state.value, id.toString()),
            "the screen must have nothing to hold back — this is the frame that used to flash",
        )
    }

    /**
     * A route naming an item that is gone is looked up once, not twice.
     *
     * `loadItem`'s not-found branch sets `error` and deliberately does not adopt the requested id —
     * adopting it would make the screen claim to be showing an item it never found. So
     * `isShowingWrongItem` stays true forever, and asking that alone the screens' fallback effect
     * fired a second full scan of the vault to reach the answer construction already had.
     *
     * Note the trace cannot be `error`: `loadFolders()` runs straight after the construction-time
     * load and every `update {}` clears `error`, so the not-found message is gone before anything
     * can read it. This test failed on exactly that first — `expected:<2131624192> but was:<null>`.
     */
    @Test
    fun anItemThatIsGoneIsLookedUpOnceAndThenLeftAlone() = runBlocking {
        val missing = UUID.randomUUID()

        val fresh = ItemEditViewModel(
            repository,
            lockManager,
            FreeEntitlement(),
            SavedStateHandle(mapOf(ROUTE_ITEM_ID to missing.toString())),
        )

        assertTrue(
            isShowingWrongItem(fresh.state.value, missing.toString()),
            "the state must not claim to be showing an item that was never found",
        )
        assertFalse(
            fresh.needsInitialLoad(missing.toString()),
            "a lookup that ran and failed leaves nothing for the screen's effect to retry",
        )
    }

    /**
     * The fallback effect is for exactly one case, and must still fire for it.
     *
     * A back stack restored while the vault is sealed: `loadItem` returns having read nothing and
     * set nothing, so there is no trace of an attempt and the screen must try again once the vault
     * is open. Getting this wrong the other way — suppressing the retry — would strand the screen
     * on its skeleton with no way out, which is worse than the double scan it replaces.
     */
    @Test
    fun aSealedVaultLeavesTheFallbackWithWorkToDo() = runBlocking {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = "Acme Mail",
                createdAt = now,
                updatedAt = now,
                content = Login(username = "ada", password = "hunter2"),
            ),
        )
        lockManager.lock()

        val fresh = ItemEditViewModel(
            repository,
            lockManager,
            FreeEntitlement(),
            SavedStateHandle(mapOf(ROUTE_ITEM_ID to id.toString())),
        )

        assertTrue(fresh.needsInitialLoad(id.toString()), "the sealed-vault entry must still retry")
        assertNull(fresh.state.value.error, "a sealed vault is not a failed lookup")
    }

    /**
     * The end-to-end half of the "not ever" case: a route naming an item the vault does not have
     * must report itself as missing straight out of construction, so the screen can say so instead
     * of holding a skeleton that will never resolve.
     */
    @Test
    fun anItemTheVaultDoesNotHaveReportsItselfMissing() = runBlocking {
        val missing = UUID.randomUUID()

        val fresh = ItemEditViewModel(
            repository,
            lockManager,
            FreeEntitlement(),
            SavedStateHandle(mapOf(ROUTE_ITEM_ID to missing.toString())),
        )

        assertTrue(fresh.itemIsMissing(missing.toString()))
    }

    @Test
    fun anItemThatIsThereIsNotReportedMissing() = runBlocking {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = "Acme Mail",
                createdAt = now,
                updatedAt = now,
                content = Login(username = "ada", password = "hunter2"),
            ),
        )

        val fresh = ItemEditViewModel(
            repository,
            lockManager,
            FreeEntitlement(),
            SavedStateHandle(mapOf(ROUTE_ITEM_ID to id.toString())),
        )

        assertFalse(fresh.itemIsMissing(id.toString()))
    }

    /**
     * A sealed vault answered nothing, so it must not be read as an answer. Getting this wrong would
     * put "Item could not be found" in front of someone whose vault simply locked — a statement
     * about their data that is not true.
     */
    @Test
    fun aSealedVaultIsNotAnItemThatIsGone() = runBlocking {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = "Acme Mail",
                createdAt = now,
                updatedAt = now,
                content = Login(username = "ada", password = "hunter2"),
            ),
        )
        lockManager.lock()

        val fresh = ItemEditViewModel(
            repository,
            lockManager,
            FreeEntitlement(),
            SavedStateHandle(mapOf(ROUTE_ITEM_ID to id.toString())),
        )

        assertFalse(fresh.itemIsMissing(id.toString()), "a sealed vault is not a verdict")
    }

    /**
     * A sealed vault is not a crash.
     *
     * Reproduced on an emulator before this existed, on the shipped build and on the one before it:
     * open an item, background the app, `adb shell am kill com.localkeep.foss`, relaunch.
     * NavController restores the back stack, `ItemDetailScreen` composes while the vault is still
     * sealed, and its effect calls straight into here —
     *
     *     FATAL EXCEPTION: main
     *     java.lang.IllegalStateException: vault is locked
     *       at com.localkeep.core.vault.VaultRepository.requireVault(VaultRepository.kt:747)
     *       at com.localkeep.core.vault.VaultRepository.getAllItems(VaultRepository.kt:352)
     *       at com.localkeep.feature.itemedit.ItemEditViewModel.loadItem(ItemEditViewModel.kt:622)
     *       at ...ItemDetailScreenKt$ItemDetailScreen$2$1.invokeSuspend(ItemDetailScreen.kt:129)
     *
     * — before `MainActivity`'s lock observer has moved the user to the unlock screen. The state
     * must also stay empty: the item's title on screen while the vault is sealed would be the
     * wrong half of a fix.
     */
    @Test
    fun loadingAnItemFromASealedVaultIsRefusedRatherThanFatal() = runBlocking {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = "Acme Mail",
                createdAt = now,
                updatedAt = now,
                content = Login(username = "ada", password = "hunter2"),
            ),
        )
        val fresh = ItemEditViewModel(repository, lockManager, FreeEntitlement(), SavedStateHandle())
        lockManager.lock()

        fresh.loadItem(id.toString())

        assertTrue(isShowingWrongItem(fresh.state.value, id.toString()), "the screen must stay held")
        assertEquals("", fresh.state.value.title, "a sealed vault must put nothing on screen")
    }

    /** The add form has no item to restore, and must not be dragged out of its new-item state. */
    @Test
    fun anAddFormWithNoRouteItemIsLeftAlone() {
        val fresh = ItemEditViewModel(repository, lockManager, FreeEntitlement(), SavedStateHandle())

        assertTrue(fresh.state.value.isNew)
        assertEquals("", fresh.state.value.title)
    }

    /**
     * `item/new?type=…` can hand the literal "new" through the `itemId` argument. It names no stored
     * item, so it must be ignored rather than looked up — a lookup fails and would leave `error` set
     * on a form the user has not touched yet.
     */
    @Test
    fun aRouteIdThatIsNotAUuidIsNotLookedUp() {
        val fresh = ItemEditViewModel(
            repository,
            lockManager,
            FreeEntitlement(),
            SavedStateHandle(mapOf(ROUTE_ITEM_ID to "new")),
        )

        assertTrue(fresh.state.value.isNew)
        assertNull(fresh.state.value.error, "the add form must not open carrying an error")
    }

    /**
     * A second ViewModel over the same unlocked vault, entitled to Pro.
     *
     * Built fresh rather than flipping a flow on the shared one: `isPro` is collected on the
     * ViewModel's own scope, so flipping it mid-test would need a wait, and a test that sometimes
     * observes the old value is worse than no test.
     */
    private fun proViewModel(): ItemEditViewModel =
        ItemEditViewModel(repository, lockManager, FreeEntitlement(pro = true), SavedStateHandle())

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

    /**
     * A new card with no number names the field it is refusing over.
     *
     * The refusal itself has been right since 2026-09-06 — a card that is nothing but a title
     * cannot be created — but it reported itself as `itemedit_error_check_fields`, "Check the item
     * fields", in a banner at the foot of a seven-field form with no field marked. Measured on
     * emulator-5554 on 2026-09-08: cardholder "John", nothing else, Save, and the only feedback is
     * that sentence. The form does not say which of the seven it means.
     *
     * `CardFields` already answers this for the expiry pair, and its comment says why in as many
     * words: "Two identical-looking boxes and a single failure message at the bottom of a filled-in
     * form left the user to work out which one it meant." The card number had the same problem and
     * not the same answer.
     *
     * The flag also takes the number field into `shouldRevealErrorBanner`'s `fieldOwnsError` arm,
     * so the banner stops competing with the field for the scroll — two reveal requests pointing
     * opposite ways is what pinned the scroll outright on 2026-07-29, and this refusal was the one
     * path still able to raise them.
     */
    @Test
    fun aNewCardWithNoNumberMarksTheNumberFieldRatherThanOnlyTheBanner() {
        viewModel.presetType(ItemEditUiState.ItemType.CARD)
        viewModel.onCardHolderChanged("John")

        assertFalse(
            viewModel.readyToName(),
            "a new card with no number must not reach the naming step",
        )
        assertTrue(
            viewModel.state.value.cardNumberError,
            "the refusal must mark the card number field, not only the form-wide banner",
        )

        viewModel.onCardNumberChanged("4111111111111111")

        assertFalse(
            viewModel.state.value.cardNumberError,
            "typing a number must clear the mark, or the ring outlives what it describes",
        )
    }

    private fun cleanupFiles() {
        context.filesDir.listFiles()?.forEach(File::deleteRecursively)
    }

    /** The `foss` posture by default — never Pro, nothing to buy — or entitled, for the Pro types. */
    private class FreeEntitlement(pro: Boolean = false) : Entitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(pro)
        override val canPurchase: Boolean = false
        // foss never knows a price — the no-price wording is the only correct render (§N2).
        override val displayPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun purchase(activity: Activity): com.localkeep.core.billing.PurchaseOutcome =
            com.localkeep.core.billing.PurchaseOutcome.Failed
        override suspend fun restore(): Boolean = false
    }
}
