package com.localkeep.autofill

import android.app.slice.Slice
import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.service.autofill.Dataset
import android.util.Size
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The response the unlock gate hands back can be shown on a keyboard's suggestion strip.
 *
 * Device pass, release build, API 36, 2026-10-05 (lane D3, finding 5): vault locked, the helper's
 * two-step username page, tap the field, tap the "Unlock LocalKeep" chip, enter the master password.
 * Back in the helper the keyboard was up with nothing on its strip and nothing filled; only tapping
 * the field again brought up the login. Another tester saw the logins appear straight after the
 * unlock on the one-page form, and so did seven runs here on emulator-5562 — on the dropdown.
 *
 * Which of the two the platform uses for the response `AuthActivity` returns is not ours to choose.
 * When the keyboard is up and asking for inline suggestions at the moment the result lands, the
 * platform renders inline, and a dataset with no inline presentation is dropped from the strip — the
 * defect `InlineChipIntentsTest` records for the 2FA row. Only when the keyboard is down does it fall
 * back to the dropdown, which is what every passing run showed. `AuthActivity` rebuilt the response
 * with `inlineSpec = null`, so every row it returned was dropdown-only.
 *
 * The spec is the keyboard's, from the original fill request. The platform offers it to the gate as
 * `AutofillManager.EXTRA_INLINE_SUGGESTIONS_REQUEST`, but only by filling in the launching intent,
 * and this gate's PendingIntent is immutable — deliberately, since the app being filled is the one
 * that launches it — so a fill-in never arrives. The service carries the spec in the intent itself
 * instead, the way it already carries the field ids.
 *
 * `checkNoApi30Signatures` reads test sources too, so the spec is built by reflection, as in
 * [InlineChipIntentsTest]. Robolectric at API 34 only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnlockGateKeepsKeyboardChipsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newId() = View(context).autofillId!!

    private fun inlineSpec(): Any {
        val builder = Class.forName("android.widget.inline.Inline" + "PresentationSpec\$Builder")
            .getConstructor(Size::class.java, Size::class.java)
            .newInstance(Size(100, 40), Size(800, 120))
        return builder.javaClass.getMethod("build").invoke(builder)!!
    }

    private fun sliceOf(dataset: Dataset): Slice? {
        val field = Dataset::class.java.getDeclaredField("mInline" + "Presentation")
        field.isAccessible = true
        val presentation = field.get(dataset) ?: return null
        return presentation.javaClass.getMethod("getSlice").invoke(presentation) as Slice
    }

    /** What the gate actually receives: the intent after it has crossed a parcel, as a PendingIntent's does. */
    private fun delivered(intent: Intent): Intent {
        val parcel = Parcel.obtain()
        try {
            intent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private val usernamePage get() = listOf(FillableField(newId(), FieldType.Username))

    private fun login() = VaultItem(
        id = UUID.randomUUID(),
        title = "LocalKeep Autofill Test",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = "newuser.d2", password = "pw"),
    )

    @Test
    fun `the keyboard's spec reaches the far side of the unlock`() {
        val fields = usernamePage
        val intent = delivered(
            AuthActivity.unlockIntent(context, fields, null, "com.localkeep.autofilltest", inlineSpec = inlineSpec()),
        )

        val carried = assertNotNull(
            AuthActivity.inlineSpecFromExtras(intent),
            "the unlock gate lost the keyboard's spec, so every row it returns is dropdown-only",
        )

        // And it is a spec the row builder can use: the login comes back as a keyboard chip.
        val row = DatasetBuilder.build(context, login(), fields, null, carried).single()
        assertNotNull(sliceOf(row), "the unlocked login row has no inline presentation")
    }

    /** The control: a request with no keyboard spec (a dropdown-only keyboard, API 28/29) invents none. */
    @Test
    fun `no spec in, no spec out`() {
        val intent = delivered(AuthActivity.unlockIntent(context, usernamePage, null, "com.localkeep.autofilltest"))
        assertNull(AuthActivity.inlineSpecFromExtras(intent))
    }

    /**
     * Both ends wired: the service hands the spec to the gate, and the gate builds with it. Read from
     * source because both sit in framework-driven entry points — the service's locked branch and the
     * gate's post-unlock step — that a JVM test cannot drive without a vault behind them.
     */
    @Test
    fun `the service passes the spec and the gate builds with it`() {
        val service = code("LocalAutofillService.kt")
        val lockedIntent = service.substringAfter("AuthActivity.unlockIntent(").substringBefore(")")
        assertTrue("inlineSpec" in lockedIntent, "the locked response must hand the spec to the gate: $lockedIntent")

        val gate = code("AuthActivity.kt")
        val build = gate.substringAfter("FillResponseFactory.buildUnlockedResponse(").substringBefore(")\n")
        assertFalse("inlineSpec = null" in build, "the gate still rebuilds the response without the spec: $build")
        assertTrue("inlineSpecFromExtras(intent)" in build, "the gate must build with the carried spec: $build")
    }

    private fun code(name: String): String {
        val file = File("src/main/kotlin/com/localkeep/autofill/$name")
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readLines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }
}
