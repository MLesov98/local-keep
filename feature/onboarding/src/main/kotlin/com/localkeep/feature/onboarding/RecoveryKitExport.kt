package com.localkeep.feature.onboarding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Renders the Recovery Kit for offline capture — the deliberate, user-initiated export that lets a
 * forgotten master password still be recovered (design spec §8.3, UI/UX spec §3.3).
 *
 * Everything here is pure local rendering: zxing encodes the QR in-process and the PDF is drawn with
 * the platform's own [PdfDocument]. Nothing touches the network, so this stays valid in the
 * `foss` flavor, which asks for no permission that could supply one.
 */
internal object RecoveryKitExport {

    /** QR bitmap for [data], or null if it can't be encoded. */
    fun qrBitmap(data: String, sizePx: Int): Bitmap? = runCatching {
        val matrix = QRCodeWriter().encode(
            data,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(
                // High EC: this is photographed off a screen, often at an angle or in poor light.
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
                EncodeHintType.MARGIN to 2,
            ),
        )
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val row = y * matrix.width
            for (x in 0 until matrix.width) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }
    }.getOrNull()

    /** A4 at 72dpi, less the 48pt margin the page is drawn with on each side. */
    private const val PAGE_WIDTH = 595
    private const val MARGIN = 48f
    private const val TEXT_WIDTH = PAGE_WIDTH - 2 * MARGIN.toInt()

    /**
     * Draws [text] wrapped inside the page's text column and returns the baseline for what follows.
     *
     * `StaticLayout` measures against [TEXT_WIDTH] and breaks on word boundaries, so a string longer
     * than the column becomes two printed lines instead of one printed line and one lost one.
     */
    private fun drawWrapped(canvas: Canvas, text: String, paint: Paint, y: Float): Float {
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, TextPaint(paint), TEXT_WIDTH)
            .setIncludePad(false)
            .build()
        canvas.save()
        // StaticLayout draws from the top of its first line; `y` here is a baseline, as it is for
        // every other drawText on this page. Backing off by the first line's ascent keeps the two
        // coordinate systems interchangeable so the callers above and below need no adjustment.
        canvas.translate(MARGIN, y + layout.getLineAscent(0))
        layout.draw(canvas)
        canvas.restore()
        return y + layout.height
    }

    /**
     * Writes a single-page A4 PDF of the kit to [out]. Caller owns [out] and must close it.
     * Throws on I/O failure so the caller can report honestly rather than claim a save that
     * didn't happen. [context] resolves the printed strings — this is plain Canvas drawing, not
     * Compose, so there is no `stringResource` to call; it is the caller's own (Application or
     * Activity) Context, not one injected for the purpose.
     */
    fun writePdf(context: Context, out: OutputStream, mnemonic: String) {
        val doc = PdfDocument()
        try {
            // A4 at 72dpi.
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, 842, 1).create())
            val canvas = page.canvas
            // One setting for every paint on the page. Ligature suppression used to be scoped to
            // the 24 words, on the argument that prose ligatures are correct typography and
            // nothing parses them. True, but the footer then still shipped U+FB04: the PDF read
            // "o<ffl>ine" and a reader searching it for "offline" found nothing (V4,
            // 2026-09-12). This is a recovery artefact people search and retype under stress, so
            // every glyph being the character it looks like beats the typography.
            val noLigatures = "\"liga\" 0, \"clig\" 0, \"dlig\" 0"
            val title = Paint().apply {
                color = Color.BLACK; textSize = 22f; isFakeBoldText = true
                fontFeatureSettings = noLigatures
            }
            val body = Paint().apply {
                color = Color.BLACK; textSize = 11f
                fontFeatureSettings = noLigatures
            }
            // Ligatures off, and only on the paint that draws the 24 words.
            //
            // The default font substitutes a single glyph for `ff`/`fi`/`fl`/`ffi`/`ffl`, and the
            // PDF's ToUnicode map then maps that glyph to U+FB00..U+FB04 — so a viewer's "copy"
            // hands back `ﬂag` where the page plainly reads `flag`. Roughly one BIP-39 word in
            // twenty-five contains such a pair, so most kits carry at least one. Copying the words
            // out of this PDF is the workflow the recovery screen steers the user toward, and until
            // 2026-09-11 it returned words the wordlist rejected as misspelled (G1).
            //
            // `canonicalMnemonic` now NFKD-normalises, which is the fix that actually makes the
            // paste work and is the one under test. This is the other half: the artefact should not
            // contain a character the page does not show in the first place.
            val word = Paint().apply {
                color = Color.BLACK
                textSize = 13f
                fontFeatureSettings = noLigatures
            }
            val muted = Paint().apply {
                color = Color.DKGRAY; textSize = 9f
                fontFeatureSettings = noLigatures
            }

            var y = 64f
            canvas.drawText(context.getString(R.string.onboarding_recovery_pdf_title), 48f, y, title)
            y += 28f
            // Wrapped, not drawn as-is. `Canvas.drawText` never wraps: anything wider than the
            // column is painted straight off the page edge and lost, silently. That is what was
            // happening to `onboarding_recovery_pdf_warning_line2` — at 132 characters in English it
            // ran past the 595pt page and the exported kit ended mid-sentence on "...and LocalKeep
            // has no server to", dropping "send your vault to." It is a Tier G security claim (see
            // TierGClaimsTest), printed on the one artifact meant to outlive the phone, and every
            // other locale is longer than English: fr 175 characters, de 173, es 163, pt 156.
            for (line in warningLines(context)) {
                y = drawWrapped(canvas, line, body, y)
            }

            y += 18f
            // Numbered two-column grid — easier to transcribe and check than a wrapped paragraph.
            val words = mnemonic.trim().split(Regex("\\s+"))
            val rows = (words.size + 1) / 2
            val startY = y
            words.forEachIndexed { index, w ->
                val column = index / rows
                val row = index % rows
                val x = 60f + column * 250f
                canvas.drawText("${index + 1}.".padStart(3), x, startY + row * 22f, word)
                canvas.drawText(w, x + 30f, startY + row * 22f, word)
            }
            y = startY + rows * 22f + 28f

            canvas.drawText(
                context.getString(
                    R.string.onboarding_recovery_pdf_footer,
                    recoveryKitFooterDate(context.resources.configuration.locales[0]),
                ),
                48f,
                y,
                muted,
            )
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun warningLines(context: Context): List<String> = listOf(
        context.getString(R.string.onboarding_recovery_pdf_warning_line1),
        context.getString(R.string.onboarding_recovery_pdf_warning_line2),
        context.getString(R.string.onboarding_recovery_pdf_warning_line3),
    )
}

/**
 * The Recovery Kit PDF footer's "generated on" date, in [locale]'s own convention rather than
 * `DateTimeFormatter.ISO_DATE` (always `YYYY-MM-DD`). A fixed ISO date is unfamiliar outside
 * en-US's conventions, and on a document whose whole job is to look trustworthy, unfamiliar reads
 * as suspicious. `MEDIUM` rather than `SHORT`: it spells the month, so it stays unambiguous
 * between `MM/DD` and `DD/MM` locales rather than trading one ambiguity for another.
 *
 * Not `private` — extracted from [RecoveryKitExport.writePdf] so the locale behaviour has a seam
 * to test without constructing a [Context] or a [android.graphics.pdf.PdfDocument].
 */
internal fun recoveryKitFooterDate(locale: Locale): String =
    LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
