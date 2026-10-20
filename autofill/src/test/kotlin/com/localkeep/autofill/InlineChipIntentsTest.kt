package com.localkeep.autofill

import android.app.PendingIntent
import android.app.slice.Slice
import android.app.slice.SliceItem
import android.content.Context
import android.service.autofill.Dataset
import android.util.Size
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.model.Card
import com.localkeep.core.model.Login
import com.localkeep.core.model.Totp
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the inline-suggestion chips carry (API 30+ keyboards), found 2026-10-02.
 *
 * Two defects. The 2FA code row was the one dataset built with no inline presentation, so an
 * inline keyboard dropped it and the code page offered nothing. And the gate chips — reprompt,
 * card, generate/search — passed their real gate as the chip's *attribution* intent, which is what
 * a long-press launches: the gate then opened outside the fill flow, with no one to hand its result
 * to. The direct-value login row had already been fixed to open LocalKeep instead; that is the
 * shape every chip is held to here.
 *
 * `checkNoApi30Signatures` reads test sources too and allows the inline types to be named only in
 * InlineSuggestions.kt, so the spec and the presentation are reached by reflection below. This test
 * runs under Robolectric at API 34 only, never on an API 28/29 device the guard protects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InlineChipIntentsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newId() = View(context).autofillId!!

    private fun inlineSpec(): Any {
        val builder = Class.forName("android.widget.inline.Inline" + "PresentationSpec\$Builder")
            .getConstructor(Size::class.java, Size::class.java)
            .newInstance(Size(100, 40), Size(800, 120))
        return builder.javaClass.getMethod("build").invoke(builder)!!
    }

    /** The dataset-wide inline presentation `setInlinePresentation` stores, or null. */
    private fun sliceOf(dataset: Dataset): Slice? {
        val field = Dataset::class.java.getDeclaredField("mInline" + "Presentation")
        field.isAccessible = true
        val presentation = field.get(dataset) ?: return null
        return presentation.javaClass.getMethod("getSlice").invoke(presentation) as Slice
    }

    private fun pendingIntentsIn(slice: Slice): List<PendingIntent> = slice.items.flatMap { item ->
        when (item.format) {
            SliceItem.FORMAT_ACTION -> listOfNotNull(item.action) + pendingIntentsIn(item.slice)
            SliceItem.FORMAT_SLICE -> pendingIntentsIn(item.slice)
            else -> emptyList()
        }
    }

    /** The host of every intent a chip carries: "attribution" for the LocalKeep-opening one. */
    private fun chipIntentHosts(dataset: Dataset): List<String?> {
        val slice = assertNotNull(sliceOf(dataset), "the row has no inline presentation at all")
        val intents = pendingIntentsIn(slice)
        assertTrue(intents.isNotEmpty(), "found no intent inside the chip")
        return intents.map { shadowOf(it).savedIntent.data?.host }
    }

    private fun login(
        totp: Totp? = null,
        reprompt: Boolean = false,
    ) = VaultItem(
        id = UUID.randomUUID(),
        title = "Example",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        reprompt = reprompt,
        content = Login(username = "me@example.com", password = "pw", totp = totp),
    )

    @Test
    fun `the direct-value login chip opens LocalKeep on a long-press`() {
        // The control: the row that was already right, proving the chip can be read at all.
        val fields = listOf(FillableField(newId(), FieldType.Username))

        val row = DatasetBuilder.build(context, login(), fields, null, inlineSpec()).single()

        assertEquals(listOf<String?>("attribution"), chipIntentHosts(row).distinct())
    }

    @Test
    fun `the 2FA code row reaches an inline keyboard`() {
        val fields = listOf(FillableField(newId(), FieldType.Totp))

        val rows = DatasetBuilder.build(
            context,
            login(totp = Totp(secretBase32 = "JBSWY3DPEHPK3PXP")),
            fields,
            null,
            inlineSpec(),
        )

        val codeRow = rows.single()
        assertNotNull(sliceOf(codeRow), "the code row has no inline presentation, so Gboard drops it")
        assertEquals(listOf<String?>("attribution"), chipIntentHosts(codeRow).distinct())
    }

    @Test
    fun `a long-press on a Confirm-to-fill chip does not launch the reprompt gate`() {
        val fields = listOf(FillableField(newId(), FieldType.Username), FillableField(newId(), FieldType.Password))
        val item = login(reprompt = true)
        val gate = PendingIntent.getActivity(
            context,
            1,
            AuthActivity.repromptIntent(context, item, fields, "example.com", "com.example"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val row = DatasetBuilder.build(context, item, fields, gate, inlineSpec()).single()

        assertEquals(listOf<String?>("attribution"), chipIntentHosts(row).distinct())
    }

    @Test
    fun `a long-press on a card chip does not launch the card gate`() {
        val fields = listOf(FillableField(newId(), FieldType.CardNumber))
        val card = VaultItem(
            id = UUID.randomUUID(),
            title = "Bank",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            content = Card(holder = "A Person", number = "4111111111111111"),
        )
        val gate = PendingIntent.getActivity(
            context,
            2,
            AuthActivity.repromptIntent(context, card, fields, "shop.example", "com.example"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val row = assertNotNull(DatasetBuilder.buildCardAuthDataset(context, card, fields, gate, inlineSpec()))

        assertEquals(listOf<String?>("attribution"), chipIntentHosts(row).distinct())
    }

    @Test
    fun `a long-press on the generate and search chips does not open the picker`() {
        val fields = listOf(FillableField(newId(), FieldType.Password))
        for (mode in listOf(PickerActivity.MODE_GENERATE, PickerActivity.MODE_SEARCH)) {
            val row = assertNotNull(
                FillResponseFactory.actionRow(
                    context = context,
                    mode = mode,
                    title = "Title",
                    subtitle = "Subtitle",
                    targetIds = fields.map { it.id },
                    fields = fields,
                    webDomain = "example.com",
                    packageName = "com.example",
                    inlineSpec = inlineSpec(),
                ),
            )

            assertEquals(listOf<String?>("attribution"), chipIntentHosts(row).distinct(), mode)
        }
    }
}
