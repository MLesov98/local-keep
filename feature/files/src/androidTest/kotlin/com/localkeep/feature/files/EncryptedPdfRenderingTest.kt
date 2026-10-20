package com.localkeep.feature.files

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the platform's PDF renderer can read an encrypted blob through [EncryptedPdfSession].
 *
 * Nothing else in the build can answer this. A compile shows the types line up; `StreamingAeadReader`
 * has its own tests proving it returns the right bytes at the right offsets. Neither says whether
 * `PdfRenderer` — which seeks to a trailer at the end of the file, reads an xref table, then jumps
 * back to individual objects — gets what it needs from a proxy descriptor that is decrypting
 * underneath it. That is platform behaviour, so it is asked on a device. The video path has the same
 * test for the same reason, and the StAX incident in this repo is why: a JVM-only dependency passed
 * every unit test while every on-device parse failed.
 *
 * What this replaces: before 2026-07-29 a PDF attachment was handed to the *video* player, which
 * answered "Playback failed (decoder error 1/-2147483648)" under a header reading "Video".
 */
@RunWith(AndroidJUnit4::class)
class EncryptedPdfRenderingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dek = ByteArray(32) { (it * 11 + 3).toByte() }
    private lateinit var store: FileBlobStore
    private lateinit var blobRef: String

    @Before
    fun setUp() {
        store = FileBlobStore(context)
        blobRef = store.encryptToBlob(ByteArrayInputStream(onePagePdf()), dek)
    }

    @After
    fun tearDown() {
        store.deleteBlob(blobRef)
    }

    @Test
    fun anEncryptedPdfOpensAndReportsItsPages() {
        EncryptedPdfSession.open(context, store, blobRef, dek).use { session ->
            assertEquals(1, session.pageCount, "the renderer did not parse the document")
        }
    }

    @Test
    fun aPageRendersInkRatherThanAnEmptyBitmap() {
        EncryptedPdfSession.open(context, store, blobRef, dek).use { session ->
            session.renderer.openPage(0).use { page ->
                assertTrue(page.width > 0 && page.height > 0, "page reported no size")
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                // A document that "opens" and renders a blank page would pass a page-count check
                // while showing the user nothing. The fixture draws text, so some pixel must differ
                // from the white it was erased to.
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue(
                    pixels.any { it != Color.WHITE },
                    "the page rendered entirely blank — the decrypted bytes reached the renderer " +
                        "but nothing was drawn",
                )
                bitmap.recycle()
            }
        }
    }

    /**
     * A minimal, valid single-page PDF drawing one line of text.
     *
     * Built here rather than committed as a binary asset so the offsets in its xref table are
     * computed from the bytes actually written — a hand-edited PDF fixture with a stale xref is the
     * classic way this kind of test starts failing for a reason that has nothing to do with the code.
     */
    private fun onePagePdf(): ByteArray {
        val content = "BT /F1 24 Tf 72 700 Td (LocalKeep encrypted PDF) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R " +
                "/Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { index, body ->
            offsets += out.length
            out.append("${index + 1} 0 obj\n").append(body).append("\nendobj\n")
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append(String.format("%010d 00000 n \n", it)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
