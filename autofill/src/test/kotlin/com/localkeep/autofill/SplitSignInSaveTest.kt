package com.localkeep.autofill

import android.content.Context
import android.service.autofill.SaveInfo
import android.view.View
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A sign-in split across two screens — username, then password — can be saved as one login.
 *
 * Before this, neither half could be. The username screen's `SaveInfo` fired the moment the screen
 * went away, `SaveHandler.extract` refused it for having no password, and the platform's "nothing
 * to confirm" answer was the whole of it; the password screen then opened a fresh autofill session
 * holding only a password and was refused from the other side. `SaveInfo.FLAG_DELAY_SAVE` is the
 * platform's answer and its javadoc names this exact case; with it set on the first screen, that
 * screen's `FillContext` is delivered again with the second screen's save request, and
 * `extract` — which already walks every fill context — sees both halves at once.
 *
 * The flag is read back off the built `SaveInfo` rather than asserted on the source, because the
 * bit has to survive `SaveInfo.Builder`, and `getFlags()` is `@hide` (public, but absent from
 * `android.jar`), so reflection is the only way to ask. Under Robolectric there is no hidden-API
 * enforcement to fight.
 */
@RunWith(RobolectricTestRunner::class)
class SplitSignInSaveTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun newField(type: FieldType) = FillableField(View(context()).autofillId!!, type)

    private fun flagsOf(saveInfo: SaveInfo): Int =
        SaveInfo::class.java.getMethod("getFlags").invoke(saveInfo) as Int

    private fun delaysSave(saveInfo: SaveInfo?): Boolean {
        requireNotNull(saveInfo) { "buildSaveInfo returned null — the save is not offered at all" }
        return flagsOf(saveInfo) and SaveInfo.FLAG_DELAY_SAVE != 0
    }

    @Test
    @Config(sdk = [34])
    fun `a username-only screen postpones its save so the password screen can complete it`() {
        val saveInfo = DatasetBuilder.buildSaveInfo(
            usernameField = newField(FieldType.Username),
            passwordField = null,
            urlField = null,
        )
        assertTrue(
            delaysSave(saveInfo),
            "without FLAG_DELAY_SAVE the username screen's context is committed on its own and " +
                "the password from the next screen can never join it",
        )
    }

    @Test
    @Config(sdk = [34])
    fun `the password screen does not postpone, or nothing would ever be saved`() {
        // Step two carries no username of its own; the one from step one arrives with the delayed
        // fill context. This SaveInfo is the one that has to fire.
        val saveInfo = DatasetBuilder.buildSaveInfo(
            usernameField = null,
            passwordField = newField(FieldType.Password),
            urlField = null,
        )
        assertFalse(
            delaysSave(saveInfo),
            "a delay on both halves of a split sign-in is a save that never happens",
        )
    }

    @Test
    @Config(sdk = [34])
    fun `an ordinary one-page login is unaffected`() {
        val saveInfo = DatasetBuilder.buildSaveInfo(
            usernameField = newField(FieldType.Username),
            passwordField = newField(FieldType.Password),
            urlField = newField(FieldType.Url),
        )
        assertFalse(
            delaysSave(saveInfo),
            "the common case must keep saving the instant the form goes away",
        )
    }

    @Test
    @Config(sdk = [34])
    fun `a checkout form is not postponed by a stray username field`() {
        val saveInfo = DatasetBuilder.buildSaveInfo(
            usernameField = newField(FieldType.Username),
            passwordField = null,
            urlField = null,
            cardFields = listOf(
                newField(FieldType.CardNumber),
                newField(FieldType.CardExpiry),
            ),
        )
        assertFalse(
            delaysSave(saveInfo),
            "a checkout saves in one pass — delaying it postpones a save the user just accepted",
        )
    }

    /**
     * `FLAG_DELAY_SAVE` is API 29 and `minSdk` is 28. The guard in `buildSaveInfo` is what keeps
     * this service from believing it postponed a save the platform went ahead and committed.
     */
    @Test
    @Config(sdk = [28])
    fun `API 28 sets no flag the platform there does not understand`() {
        val saveInfo = DatasetBuilder.buildSaveInfo(
            usernameField = newField(FieldType.Username),
            passwordField = null,
            urlField = null,
        )
        assertFalse(delaysSave(saveInfo))
    }

    @Test
    fun `the predicate names the first step of a split sign-in and nothing else`() {
        val username = newField(FieldType.Username)
        val password = newField(FieldType.Password)
        val cardNumber = newField(FieldType.CardNumber)

        assertTrue(DatasetBuilder.isFirstStepOfSplitSignIn(username, null, null))
        assertFalse(DatasetBuilder.isFirstStepOfSplitSignIn(username, password, null))
        assertFalse(DatasetBuilder.isFirstStepOfSplitSignIn(null, password, null))
        assertFalse(DatasetBuilder.isFirstStepOfSplitSignIn(username, null, cardNumber))
        assertFalse(DatasetBuilder.isFirstStepOfSplitSignIn(null, null, null))
    }
}
