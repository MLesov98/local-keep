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
 * The response the browser domain-confirmation gate hands back after Allow can be shown on a
 * keyboard's suggestion strip.
 *
 * The same defect as the unlock gate's ([UnlockGateKeepsKeyboardChipsTest], device pass 2026-10-05):
 * `ConfirmDomainActivity` rebuilt the response with `inlineSpec = null`, so every row it returned was
 * dropdown-only. The platform renders a returned response inline whenever the keyboard is up and
 * asking at that moment, and drops rows with no inline presentation there — so Allow on a site the
 * vault holds a login for could leave the user with an empty strip and nothing filled. Found by
 * reading, as the lead the unlock-gate fix left; not driven on a device.
 *
 * The spec is carried in the gate's own intent, as for the unlock gate: the platform's
 * `EXTRA_INLINE_SUGGESTIONS_REQUEST` arrives only as a fill-in, and this gate's PendingIntent is
 * immutable. Spec built by reflection because `checkNoApi30Signatures` reads test sources too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfirmDomainGateKeepsKeyboardChipsTest {

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

    /** The intent after it has crossed a parcel, as a PendingIntent's does. */
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

    private val signInForm
        get() = listOf(FillableField(newId(), FieldType.Username), FillableField(newId(), FieldType.Password))

    private fun login() = VaultItem(
        id = UUID.randomUUID(),
        title = "Example",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = "me@example.com", password = "pw"),
    )

    @Test
    fun `the keyboard's spec reaches the far side of Allow`() {
        val fields = signInForm
        val intent = delivered(
            ConfirmDomainActivity.intent(context, fields, "example.com", "com.example.browser", inlineSpec = inlineSpec()),
        )

        val carried = assertNotNull(
            ConfirmDomainActivity.inlineSpecFromExtras(intent),
            "the domain gate lost the keyboard's spec, so every row it returns after Allow is dropdown-only",
        )
        val row = DatasetBuilder.build(context, login(), fields, null, carried).first()
        assertNotNull(sliceOf(row), "the login row returned after Allow has no inline presentation")
    }

    /** The control: no keyboard spec on the request (a dropdown-only keyboard, API 28/29), none invented. */
    @Test
    fun `no spec in, no spec out`() {
        val intent = delivered(ConfirmDomainActivity.intent(context, signInForm, "example.com", "com.example.browser"))
        assertNull(ConfirmDomainActivity.inlineSpecFromExtras(intent))
    }

    /** Both ends wired: the factory hands the spec to the gate, and the gate builds with it after Allow. */
    @Test
    fun `the factory passes the spec and the gate builds with it`() {
        val factory = code("FillResponseFactory.kt")
        val gateIntent = factory.substringAfter("ConfirmDomainActivity.intent(").substringBefore(")")
        assertTrue("inlineSpec" in gateIntent, "the confirmation gate must be handed the spec: $gateIntent")

        val gate = code("ConfirmDomainActivity.kt")
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
