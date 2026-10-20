package com.localkeep.feature.files

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSkeleton
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkSurface
import com.localkeep.core.ui.theme.lkTween
import com.localkeep.core.vault.SessionKey
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Renders an encrypted PDF page by page, with no plaintext copy anywhere.
 *
 * `PdfRenderer` needs a *seekable* file descriptor, which is why this cannot simply decrypt into a
 * byte array: the obvious shortcut — write the decrypted PDF to `cacheDir` and hand over its path —
 * puts the whole document on disk in the clear, where every other app with storage access and every
 * backup agent can reach it. That is precisely the thing this feature exists not to do.
 *
 * The descriptor it renders from is a proxy that decrypts per read — see [EncryptedPdfSession],
 * which owns that machinery so a device test can exercise it directly.
 *
 * Bitmaps are rendered lazily by [LazyColumn], one page at a time, because a 200-page document
 * rendered eagerly at screen width is hundreds of megabytes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EncryptedPdfViewer(
    blobRef: String,
    sessionKey: SessionKey,
    fileBlobStore: FileBlobStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val c = LkTheme.colors
    var pageCount by remember(blobRef) { mutableStateOf<Int?>(null) }
    var error by remember(blobRef) { mutableStateOf<Int?>(null) }
    var renderer by remember(blobRef) { mutableStateOf<PdfRenderer?>(null) }
    var session by remember(blobRef) { mutableStateOf<EncryptedPdfSession?>(null) }

    // Off the main thread, deliberately. `EncryptedPdfSession.open` is not a cheap handle grab: the
    // `PdfRenderer` constructor parses the document up front — seek to the trailer, read the xref
    // table, jump back to objects — and every one of those reads goes through the decrypting proxy
    // descriptor and waits on an AES-GCM chunk. Run in a `DisposableEffect` body it blocked the
    // frame's apply step for as long as that took, on a document whose size nothing caps (unlike
    // TEXT and OFFICE, which have viewer-side byte limits), so the skeleton below never got a frame
    // to draw in and the app simply froze between the tap and the first page. The reads themselves
    // were already off-main; the caller waiting on them was not.
    LaunchedEffect(blobRef) {
        runCatching {
            withContext(Dispatchers.IO) {
                // Borrowed across `open` only. The proxy descriptor it returns reads through a
                // `StreamingAeadReader`, which holds its own derived frame key, so scrolling a
                // document later never touches the session key again.
                checkNotNull(
                    sessionKey.use { EncryptedPdfSession.open(context, fileBlobStore, blobRef, it) },
                ) { "vault locked while opening document" }
            }
        }.onSuccess { opened ->
            // The effect can be cancelled while `open` is in flight — a scroll to the next
            // attachment, or a lock. `withContext` still returns the session, so without this it
            // leaks an open descriptor and its proxy thread.
            if (!isActive) {
                opened.close()
                return@onSuccess
            }
            session = opened
            renderer = opened.renderer
            pageCount = opened.pageCount
        }.onFailure { e ->
            // `runCatching` swallows cancellation too; rethrow it so a disposed viewer does not
            // render as a failed document.
            if (e is CancellationException) throw e
            // Never the exception's own text: it can carry a path, and a parser's complaint is not a
            // sentence anyone can act on. A PDF this renderer refuses is damaged or password
            // protected — PdfRenderer cannot open an encrypted PDF at all.
            error = MediaErrors.pdfOpenFailed(e)
        }
    }

    DisposableEffect(blobRef) {
        onDispose {
            renderer = null
            session?.close()
            session = null
        }
    }

    val pages = pageCount
    when {
        error != null -> {
            val message = stringResource(error!!)
            LkInlineError(message, Modifier.announce(message))
        }
        pages == null -> LkSkeleton(modifier.fillMaxWidth())
        else -> {
            val listState = rememberLazyListState()
            var scale by remember(blobRef) { mutableFloatStateOf(PDF_FIT_WIDTH_SCALE) }
            var panX by remember(blobRef) { mutableFloatStateOf(0f) }
            var viewportWidth by remember { mutableIntStateOf(0) }
            // The centroid is deliberately unused: this zoom is anchored at the top of the viewport
            // (see `transformOrigin` below) so that the list's own scroll stays the thing that moves
            // the document, and a pinch centred on the middle of the screen would fight it.
            val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
                val next = pdfZoomScale(scale, zoomChange)
                panX = clampPdfPan(panX + panChange.x, next, viewportWidth)
                scale = next
            }

            // Visible while the document is moving, and for a moment after it settles. Held open by
            // `collectLatest`, so a scroll that resumes inside the linger cancels the fade rather
            // than blinking the pill out and straight back in.
            var indicatorVisible by remember(blobRef) { mutableStateOf(false) }
            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
                    .collectLatest { (_, scrolling) ->
                        indicatorVisible = true
                        if (!scrolling) {
                            delay(PDF_INDICATOR_LINGER_MS)
                            indicatorVisible = false
                        }
                    }
            }

            Box(
                modifier = modifier
                    .fillMaxSize()
                    // The zoomed layer is larger than its box by definition; without this it paints
                    // over the viewer's chrome and the page beside it in the pager.
                    .clipToBounds()
                    .onSizeChanged { viewportWidth = it.width },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = panX
                            // Anchored at the top edge, not the middle: the list's own scroll is
                            // still what moves the document, and a centred origin would push the
                            // first page's head off the top of the viewport the moment you zoom.
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                        .transformable(state = transformState, canPan = { pdfPanIsHorizontal(it, scale) })
                        .pointerInput(blobRef) {
                            detectTapGestures(
                                onDoubleTap = {
                                    scale = PDF_FIT_WIDTH_SCALE
                                    panX = 0f
                                },
                            )
                        },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items((0 until pages).toList()) { index ->
                        PdfPage(index = index, renderer = renderer, pageCount = pages)
                    }
                }
                if (pages > 1) {
                    PdfPageIndicator(
                        listState = listState,
                        pageCount = pages,
                        visible = indicatorVisible,
                        // Against the right edge and vertically centred, where a fast-scroll bubble
                        // sits. The viewer owns the top and bottom of this screen — its title bar
                        // already carries an attachment counter and its action bar the buttons — so
                        // a pill at either end would land on top of one of them.
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = LkSpace.md),
                    )
                }
            }
        }
    }
}

/** Fit-to-width, the default and the floor: a PDF page is laid out to the viewer's width. */
private const val PDF_FIT_WIDTH_SCALE = 1f

/** Four times fit-to-width reads the smallest footnote in a scanned A4 page on a phone. */
private const val PDF_MAX_ZOOM = 4f

/** How long the page indicator stays up once the document stops moving. */
private const val PDF_INDICATOR_LINGER_MS = 1_200L

/** The zoom a pinch of [zoomChange] leaves behind, never below fit-to-width. */
internal fun pdfZoomScale(current: Float, zoomChange: Float): Float =
    (current * zoomChange).coerceIn(PDF_FIT_WIDTH_SCALE, PDF_MAX_ZOOM)

/**
 * Horizontal pan, clamped to the page's own edges.
 *
 * At scale `s` the content is `viewportWidth * (s - 1)` wider than the window, half of it on each
 * side, so that half is exactly how far it may travel. Unclamped, a flick at the right-hand edge
 * slides the page off the screen entirely and leaves the reader looking at the background with no
 * way back except the double-tap they have not discovered.
 */
internal fun clampPdfPan(panX: Float, scale: Float, viewportWidth: Int): Float {
    val overhang = viewportWidth * (scale - PDF_FIT_WIDTH_SCALE) / 2f
    return panX.coerceIn(-overhang, overhang)
}

/**
 * Whether a one-finger drag is the zoom layer's to pan, or somebody else's.
 *
 * Vertical stays the list's at every scale. Scrolling is how a reader moves through a document, and
 * taking it over while zoomed would strand them on whichever page happened to be under their
 * fingers; it also keeps the gesture off `LazyColumn`, which would otherwise consume the drag first
 * and leave the pan dead.
 *
 * Horizontal is only the zoom layer's ONCE THERE IS SOMEWHERE TO PAN. At fit-to-width [clampPdfPan]
 * has a zero-width range, so a sideways drag moved nothing — but `transformable` claimed it anyway,
 * and the claim reached `HorizontalPager` as silence: a swipe across a PDF page did not page to the
 * next attachment the way the same swipe across a photo did (release sweep 2026-09-20, finding
 * 4C.2-paging-2). A gesture that cannot do anything must not be taken.
 */
internal fun pdfPanIsHorizontal(pan: Offset, scale: Float): Boolean =
    scale > PDF_FIT_WIDTH_SCALE && abs(pan.x) > abs(pan.y)

/** One page as the list reports it: its index, its offset in the viewport, and its height. */
internal data class PdfVisiblePage(val index: Int, val offset: Int, val size: Int)

/**
 * The index of the page the reader is actually looking at — the one showing the most of itself.
 *
 * `firstVisibleItemIndex` is the first page with any pixel on screen, so a page with 235 px left at
 * the top of the viewport outranked the page filling the other 1281 px and the pill read "1 / 5"
 * over page 2 (release sweep 2026-09-20, finding 4C.2-pdf-4). Ties go to the lower index, which is
 * the page the reader is coming from.
 *
 * Returns 0 when nothing is laid out yet, which [pdfIndicatorPage] turns into page one.
 */
internal fun pdfMostVisiblePageIndex(
    visiblePages: List<PdfVisiblePage>,
    viewportStart: Int,
    viewportEnd: Int,
): Int {
    var best = 0
    var bestVisible = Int.MIN_VALUE
    for (page in visiblePages) {
        val visible = minOf(page.offset + page.size, viewportEnd) - maxOf(page.offset, viewportStart)
        if (visible > bestVisible) {
            bestVisible = visible
            best = page.index
        }
    }
    return best
}

/** One-based page number for the indicator, clamped into the document. */
internal fun pdfIndicatorPage(firstVisibleItemIndex: Int, pageCount: Int): Int =
    (firstVisibleItemIndex + 1).coerceIn(1, pageCount.coerceAtLeast(1))

/**
 * Where you are in the document — "3 / 12" — while it is moving.
 *
 * A long PDF scrolled as one list gives no sense of position at all: every page looks like the last
 * one, and nothing says how many are left. This is the counter every reader has, and it is
 * transient for the same reason theirs are — it is an answer to scrolling, not furniture.
 */
@Composable
private fun PdfPageIndicator(
    listState: LazyListState,
    pageCount: Int,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    // Derived, so a scroll recomposes this pill and not the document behind it.
    val page by remember(listState, pageCount) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val index = pdfMostVisiblePageIndex(
                visiblePages = layout.visibleItemsInfo.map { PdfVisiblePage(it.index, it.offset, it.size) },
                viewportStart = layout.viewportStartOffset,
                viewportEnd = layout.viewportEndOffset,
            )
            pdfIndicatorPage(index, pageCount)
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(lkTween(LkMotion.Quick)),
        exit = fadeOut(lkTween(LkMotion.Base)),
        modifier = modifier,
    ) {
        // Spoken in words. "3 / 12" is a pair of numbers and a slash to a screen reader, and the
        // pages themselves already say it this way.
        val spoken = stringResource(R.string.files_pdf_page_label, page, pageCount)
        Box(
            modifier = Modifier
                .lkSurface(
                    shape = RoundedCornerShape(999.dp),
                    color = c.surface.copy(alpha = 0.85f),
                    ring = c.ring,
                )
                .padding(horizontal = LkSpace.sm, vertical = LkSpace.xs)
                .clearAndSetSemantics { contentDescription = spoken },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.files_pdf_page_indicator, page, pageCount),
                style = LkText.caption,
                color = c.textSecondary,
            )
        }
    }
}

@Composable
private fun PdfPage(index: Int, renderer: PdfRenderer?, pageCount: Int) {
    val c = LkTheme.colors
    var bitmap by remember(index, renderer) { mutableStateOf<Bitmap?>(null) }
    // A per-page render failure used to be swallowed: the runCatching result was discarded, so a
    // page whose bytes are damaged (the decrypting proxy read can throw, or the page object can be
    // corrupt) left `bitmap` null forever and the skeleton below showed indefinitely with no error.
    var failed by remember(index, renderer) { mutableStateOf(false) }

    // Off the main thread for the same reason the document open is: `page.render` blocks until the
    // proxy descriptor has served every read it needs, and each of those decrypts. Run inline in a
    // `DisposableEffect` body it stuttered the scroll once per page — LazyColumn composes several
    // items in one frame, so the cost multiplied.
    LaunchedEffect(index, renderer) {
        val active = renderer ?: return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) {
                // Synchronised on the renderer: PdfRenderer permits exactly one open page at a
                // time, and LazyColumn composes several items in the same frame while scrolling.
                // Blocking an IO thread here is the point — the constraint is the renderer's, not
                // the dispatcher's.
                synchronized(active) {
                    active.openPage(index).use { page ->
                        // Twice the page's own points, capped: enough to read on a phone without
                        // allocating a poster-sized bitmap per page.
                        val (width, height) = pdfPageRenderSize(page.width, page.height)
                        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        // PdfRenderer draws only ink; an unpainted bitmap leaves transparent gaps
                        // that show the near-black screen through the page.
                        target.eraseColor(Color.WHITE)
                        page.render(target, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        target
                    }
                }
            }
        }.onSuccess { target ->
            // Scrolled away before the render landed: nothing will draw this, and onDispose has
            // already run, so recycle it here or the page leaks its bitmap.
            if (isActive) bitmap = target else target.recycle()
        }.onFailure { e ->
            if (e is CancellationException) throw e
            failed = true
        }
    }

    DisposableEffect(index, renderer) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }

    val rendered = bitmap
    if (failed) {
        // An inline failure for this one page, not an indefinite skeleton. The rest of the document
        // keeps rendering; only the page whose bytes could not be decoded shows the error.
        val message = stringResource(R.string.files_pdf_page_render_failed, index + 1)
        LkInlineError(message, Modifier.fillMaxWidth().announce(message))
    } else if (rendered == null) {
        LkSkeleton(Modifier.fillMaxWidth())
    } else {
        androidx.compose.foundation.Image(
            bitmap = rendered.asImageBitmap(),
            // Pages are numbered for a screen reader, which otherwise meets an unnamed graphic per
            // page. The text itself is not extractable from a rendered page — stated, not implied.
            contentDescription = stringResource(R.string.files_pdf_page_label, index + 1, pageCount),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .background(c.cardFill, LkShape.lg)
                .padding(1.dp),
        )
    }
}

/** Wide enough to read on a phone, small enough that a page bitmap stays a few megabytes. */
private const val MAX_PAGE_WIDTH_PX = 1600.0

/**
 * Most pixels one page bitmap may hold: 16 MP is 64 MB of ARGB_8888, safely under the 100 MB at
 * which the hardware canvas refuses to draw a bitmap ("trying to draw too large bitmap").
 *
 * Added 2026-10-02 (S3-12). Only the *width* was capped, so the height followed the page: a
 * 390 x 20,000 pt phone-screenshot-as-PDF rendered at 780 x 40,000 = 125 MB and either crashed the
 * canvas when it scrolled into view or ran out of memory into "could not render". An ordinary A4
 * page is about 2 MP, so nothing normal comes near this.
 */
private const val MAX_PAGE_PIXELS = 16_000_000.0

/**
 * The bitmap size to render a page of [pageWidth] x [pageHeight] points at: twice its own size,
 * narrowed to [MAX_PAGE_WIDTH_PX] wide and to [MAX_PAGE_PIXELS] in total, aspect ratio kept.
 */
internal fun pdfPageRenderSize(pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
    val w = pageWidth.coerceAtLeast(1).toDouble()
    val h = pageHeight.coerceAtLeast(1).toDouble()
    val scale = minOf(2.0, MAX_PAGE_WIDTH_PX / w, sqrt(MAX_PAGE_PIXELS / (w * h)))
    return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
}
