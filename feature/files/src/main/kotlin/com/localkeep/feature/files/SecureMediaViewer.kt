package com.localkeep.feature.files

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.localkeep.core.model.Attachment
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.theme.LkActionEmphasis
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkEmptyState
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkInlineNote
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSkeleton
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkSurface
import com.localkeep.core.ui.theme.lkTween
import com.localkeep.core.vault.SessionKey
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Decoded bitmap along with original dimensions. */
private data class DecodedImageData(
    val bitmap: ImageBitmap,
    val dimensions: String?,
)

/**
 * Immersive, swipeable media viewer over the current item's attachments (§4).
 *
 * Replaces the single-file viewer with a horizontal pager that lets the user swipe between media,
 * tap to toggle chrome, toggle 1.55x zoom, swipe up for provenance details, and jump via the filmstrip.
 *
 * Each frame is decrypted into memory for as long as it is viewed. Decryption latency is shown
 * honestly via a skeleton + jade decrypting badge.
 */
@Composable
fun SecureMediaViewer(
    attachments: List<Attachment>,
    startId: String,
    sessionKey: SessionKey,
    fileBlobStore: FileBlobStore,
    onClose: () -> Unit,
    onDelete: ((Attachment) -> Unit)? = null,
    // Named by the caller, because the three screens that mount this viewer mean three different
    // things by the same button. The standalone viewer moves the item to the trash and it comes
    // back; the attachment picker drops an attachment from the stored item for good. One shared
    // "Delete" was telling the user those are the same act, and the glossary is explicit that
    // "trash" is the recoverable area and is NOT "delete". Defaulting to the blunt word is the safe
    // direction: a caller that forgets to say "recoverable" understates what it can undo, which is
    // the harmless way round.
    //
    // It names the act, not just the button: [viewerDeleteCopy] derives the confirmation dialog's
    // title, body and confirm label from it too. Until 2026-08-31 only the button read from this,
    // so the trash mount raised the permanent-removal dialog next to its own "Trash" button.
    @StringRes deleteLabel: Int = R.string.files_viewer_delete,
    folderPath: String? = null,
    preferredPageId: String? = null,
    onPageIdChanged: (String) -> Unit = {},
    pageDetailsById: Map<String, String?> = emptyMap(),
    /**
     * What the user called each page, when that is not its filename.
     *
     * Kept beside [Attachment.filename] rather than replacing it, because the two are not the same
     * fact and only one of them is a name. The filename is what the export writes and what the
     * details sheet reports, so it has to stay literal; the title is what the vault list, search and
     * every other screen call the item. Mounting the viewer without this map is still correct — a
     * standalone attachment has no title of its own — and it falls back to the filename.
     */
    pageTitlesById: Map<String, String?> = emptyMap(),
    /**
     * The user's own note about each page, shown on the details sheet.
     *
     * Only the standalone File route fills this: `File.notes` is a field on the vault item (schema
     * 8), while an [Attachment] hangs off another item and has no note of its own. Absent means
     * "this mount has no notes", not "this page has none".
     */
    pageNotesById: Map<String, String?> = emptyMap(),
    onSettings: ((Attachment) -> Unit)? = null,
) {
    if (attachments.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialIndex = remember(attachments, startId, preferredPageId) {
        val target = preferredPageId ?: startId
        attachments.indexOfFirst { it.id.toString() == target }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { attachments.size })
    val currentIndex = pagerState.currentPage.coerceIn(0, attachments.size - 1)
    val currentAttachment = attachments[currentIndex]
    val currentFolderPath = pageDetailsById[currentAttachment.id.toString()] ?: folderPath
    val currentNotes = pageNotesById[currentAttachment.id.toString()]
    val currentPageTitle =
        pageTitlesById[currentAttachment.id.toString()]?.takeIf { it.isNotBlank() }
            ?: currentAttachment.filename
    // What the file's own bytes turned out to be, where that disagreed with what its mime type and
    // its name claimed — keyed by blobRef, filled in by the decrypt effect below. A picker reports
    // `image/jpeg` for anything named `.jpg`, so a PDF under that name reached BitmapFactory and
    // came back as "This image … isn't in a format this device can decode. Export it to your
    // gallery" (release sweep 2026-09-20, finding 4C.1c-liar). The spec's line is that an
    // attachment is shown by what it is, never by assumption.
    var sniffedKinds by remember { mutableStateOf<Map<String, FileKind>>(emptyMap()) }
    // `remember` unconditionally, then the override: a `remember` on the right of an elvis is a
    // composable call inside a branch, and its slot is discarded the moment the sniff fills in.
    val declaredKind = remember(currentAttachment.mime, currentAttachment.filename) {
        fileKindOf(currentAttachment.mime, currentAttachment.filename)
    }
    val currentKind = sniffedKinds[currentAttachment.blobRef] ?: declaredKind

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var isZoomed by rememberSaveable { mutableStateOf(false) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var hintVisible by rememberSaveable { mutableStateOf(true) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmExport by rememberSaveable { mutableStateOf(false) }

    var exporting by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<Int?>(null) }

    // How tall the bottom chrome bar actually measured, so the hint pill can sit on top of it
    // instead of on top of a guess. Zero until the bar has been laid out once, which is only the
    // frame before it appears; the pill rides `chromeVisible` and so is not on screen for it.
    val density = LocalDensity.current
    var chromeBarHeight by remember { mutableStateOf(0.dp) }
    // The same measurement for the top bar, which until 2026-09-20 nothing was held clear of. Every
    // non-media page drew its content from the window's top edge while the bar floated over it, so
    // the first two lines of any text, markdown, Office or PDF file were unreadable — and a 0-byte
    // file, whose entire content is the one line "This file is empty.", read as a blank black
    // screen (release sweep, findings 4C.2-chrome / 4C.2-txt / 4C.2-zero).
    var topChromeHeight by remember { mutableStateOf(0.dp) }
    // The viewport an image is fitted into, for [imageRunsUnderChrome].
    var pagerSize by remember { mutableStateOf(IntSize.Zero) }
    // The strip the scrim fades in over, held above the bar's content by a matching top padding.
    val fadeHeight = LkSpace.xl

    var decodedImages by remember { mutableStateOf<Map<String, DecodedImageData>>(emptyMap()) }
    // Filmstrip thumbnails, keyed by blobRef. Separate from [decodedImages] on purpose: that map
    // holds full-screen decodes under a ±1 window, and a strip that only ever showed the current
    // page and its two neighbours as pictures would still be mostly lettering.
    var thumbnails by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var pageErrors by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // Pages whose failure could plausibly go away on a second attempt — an IO hiccup, a read that
    // lost the file descriptor. Not a failed authentication tag and not "too large": those are
    // properties of the bytes, and offering to try again would be offering nothing.
    var retryablePages by remember { mutableStateOf<Set<String>>(emptySet()) }
    // The decrypt effect is keyed on the page, so clearing the error alone would not re-run it.
    var retryNonce by remember { mutableStateOf(0) }
    var decryptedText by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var decryptedOffice by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }

    // Intercept Back gesture inside viewer: sheet -> zoom -> close
    BackHandler(enabled = true) {
        when {
            sheetOpen -> sheetOpen = false
            isZoomed -> isZoomed = false
            else -> onClose()
        }
    }

    // Auto-dismiss hint pill after 4.2s (§3.9: one-shot fade, no looping animations)
    LaunchedEffect(Unit) {
        delay(4200L)
        hintVisible = false
    }

    // Reset zoom when swiping to another page
    LaunchedEffect(pagerState.currentPage) {
        isZoomed = false
    }

    LaunchedEffect(attachments.map { it.id }, preferredPageId) {
        val target = preferredPageId ?: return@LaunchedEffect
        val targetIndex = attachments.indexOfFirst { it.id.toString() == target }
        if (targetIndex >= 0 && targetIndex != pagerState.currentPage) {
            pagerState.scrollToPage(targetIndex)
        }
    }

    LaunchedEffect(currentAttachment.id) {
        onPageIdChanged(currentAttachment.id.toString())
    }

    // Keep only the pages the pager can show without another decode: the current one and its two
    // neighbours. Everything decoded here was additive and never released, so paging through a
    // gallery held one decoded bitmap per *visited* page for the whole life of the viewer — the
    // downsample bound caps each one, but nothing capped how many. Twenty pages in, that is twenty
    // bitmaps live in the native heap with no route to reclaim them, and the process dies on a
    // device with less headroom than the one this was written on.
    //
    // The window is ±1 rather than 0 because the pager renders the neighbour during a swipe;
    // evicting it would decode it again on every drag. Text and Office pages are evicted on the
    // same window — they are cheaper each, but they are just as unbounded in count.
    //
    // Computed by a plain function rather than inline, so the bound itself — three entries, however
    // many pages the gallery holds — has a unit test independent of Compose. Nothing here recycles
    // the evicted `Bitmap`s explicitly: a bitmap still on screen is always inside this same window
    // (the pager never renders further than one page past the current one), so dropping a key here
    // never touches one mid-draw, and the native memory behind the rest is reclaimed by the ordinary
    // GC path once nothing holds the `ImageBitmap` wrapping it.
    LaunchedEffect(currentIndex, attachments) {
        val keep = viewerKeepWindow(attachments, currentIndex)
        decodedImages = decodedImages.filterKeys { it in keep }
        decryptedText = decryptedText.filterKeys { it in keep }
        decryptedOffice = decryptedOffice.filterKeys { it in keep }
    }

    // Filmstrip thumbnails.
    //
    // The strip used to draw the file extension — "JPG", "JPG", "JPG" — as lettering laid over the
    // photograph the viewer was showing, which is what got it reported (2026-09-02): *"it overlaps
    // with the actual image, it's within the image itself"*. A thumbnail strip over a photo is a
    // pattern every gallery on the phone already uses and it reads as intentional; three text badges
    // over the same photo read as placeholders that failed to load, which is very nearly what they
    // were. Only images get one — a PDF, a video or a document has no picture to show, so those keep
    // the extension badge (and the video keeps its play glyph over it).
    //
    // Bounded the same way [decodedImages] is, and for the same reason: additive caching here is how
    // a forty-page gallery ends up holding forty bitmaps. The window is wider — a strip shows six or
    // so at once and scrolls to the current page — but it is still a window, and each entry is
    // capped at [THUMB_MAX_DIM] per edge, so the whole cache is bounded by a constant rather than by
    // how long the user browses. Decoded one at a time rather than in parallel: this runs beside the
    // full-size decode of the page the user is actually looking at, and that one comes first.
    LaunchedEffect(currentIndex, attachments) {
        if (attachments.size <= 1) return@LaunchedEffect
        val keep = filmstripKeepWindow(attachments, currentIndex)
        thumbnails = thumbnails.filterKeys { it in keep }
        for (att in attachments) {
            if (att.blobRef !in keep) continue
            if (thumbnails[att.blobRef] != null) continue
            // A video joins the images here once it has a stored frame to show, which every import
            // now writes (3C-3). It is admitted on `thumbnailRef` rather than on its kind because
            // the fallback below cannot serve it: the full blob of a video decodes to no bitmap at
            // all, so a video without a stored frame would cost a whole decrypt to arrive back at
            // the `MP4` badge it already had.
            val kind = fileKindOf(att.mime, att.filename)
            if (kind != FileKind.IMAGE && !(kind == FileKind.PLAYABLE && att.thumbnailRef != null)) continue
            // A thumbnail is decoration. Anything that goes wrong reading or decoding one leaves the
            // extension badge in place, which is exactly what the strip drew before; it must never
            // become an error the user is told about, and it must never take the viewer down.
            // `CancellationException` is rethrown rather than swallowed — this effect is cancelled
            // every time the page changes, and a catch-all here would keep the loop decoding pages
            // for a window that has already moved.
            val bitmap = try {
                // The stored thumbnail first, the full photograph only if there isn't one.
                //
                // Every photo and video import already writes a downsampled JPEG beside the
                // original and records it as `thumbnailRef` — `BlobReKey` re-keys it, the schema
                // reserves a field number for it — and until now nothing on any screen read it
                // back. The strip decrypted and decoded the *full* blob instead, which for a
                // seventeen-entry window on a modern camera roll is tens of megabytes of AEAD and
                // JPEG work to draw pictures 128px on a side.
                //
                // Falls back rather than requires: items imported before thumbnails were written,
                // and any whose thumbnail blob has gone missing, must keep showing a picture.
                val bytes = withContext(Dispatchers.IO) {
                    sessionKey.use { key ->
                        thumbnailBytesOrNull(fileBlobStore, att.thumbnailRef, key)
                            ?: if (kind == FileKind.IMAGE) fileBlobStore.decryptToBytes(att.blobRef, key) else null
                    }
                }
                if (bytes == null) null else withContext(Dispatchers.Default) { decodeThumbnailBitmap(bytes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: an OutOfMemoryError decoding one oversized thumbnail is
                // the case most worth surviving, and it is an Error.
                null
            }
            if (bitmap != null) {
                thumbnails = thumbnails + (att.blobRef to bitmap.asImageBitmap())
            }
        }
    }

    // Decrypt content off the main thread for the current page. Keyed on the kind as well as the
    // page, so a page the sniff below reclassifies is re-run under its real kind rather than left
    // on the branch its filename put it on.
    LaunchedEffect(pagerState.currentPage, attachments, retryNonce, currentKind) {
        val att = currentAttachment
        val kind = currentKind
        when (kind) {
            FileKind.IMAGE -> {
                if (decodedImages[att.blobRef] == null && pageErrors[att.blobRef] == null) {
                    try {
                        // Null means the session ended between the check above and the read, and
                        // the lock is already routing this screen away — there is nothing to show
                        // and nothing to report.
                        val decrypted = withContext(Dispatchers.IO) {
                            sessionKey.use { fileBlobStore.decryptToBytes(att.blobRef, it) }
                        } ?: return@LaunchedEffect
                        // The bytes get the last word before any decoder sees them. Free here: this
                        // read has already happened, and the check is a handful of leading bytes.
                        val actual = sniffFileKind(decrypted)
                        if (actual != null && actual != FileKind.IMAGE) {
                            sniffedKinds = sniffedKinds + (att.blobRef to actual)
                            return@LaunchedEffect
                        }
                        val (bmp, dims) = withContext(Dispatchers.Default) {
                            decodeViewerBitmapWithDimensions(decrypted)
                        }
                        if (bmp != null) {
                            decodedImages = decodedImages + (att.blobRef to DecodedImageData(bmp.asImageBitmap(), dims))
                        } else {
                            // A null bitmap out of a decrypt that threw nothing means the codec, not
                            // the bytes — see MediaErrors.imageDecodeFailed for why that is a
                            // different sentence from the out-of-memory arm below.
                            pageErrors = pageErrors + (att.blobRef to MediaErrors.imageDecodeFailed(null))
                        }
                    } catch (e: OutOfMemoryError) {
                        // `OutOfMemoryError` is an Error, not an Exception, so the arm below cannot
                        // catch it — the same trap this file already documents for GalleryExporter,
                        // and that Argon2Calibrator and VaultLockManager both handle deliberately.
                        // One page too big to decode should fail as a page, not take the process
                        // and the unlocked session with it. This is not a substitute for the
                        // eviction above: from API 26 bitmap pixels live in the native heap, so
                        // memory pressure can arrive as a low-memory kill with no Error ever thrown.
                        pageErrors = pageErrors + (att.blobRef to MediaErrors.imageDecodeFailed(e))
                    } catch (e: Exception) {
                        pageErrors = pageErrors + (att.blobRef to MediaErrors.openFailed(e))
                        if (MediaErrors.isRetryable(e)) retryablePages = retryablePages + att.blobRef
                    }
                }
            }
            FileKind.TEXT -> {
                if (decryptedText[att.blobRef] == null && pageErrors[att.blobRef] == null) {
                    try {
                        if (fileBlobStore.blobSizeBytes(att.blobRef) > MAX_TEXT_BYTES) {
                            pageErrors = pageErrors + (att.blobRef to MediaErrors.TEXT_TOO_LARGE)
                        } else {
                            val decrypted = withContext(Dispatchers.IO) {
                                sessionKey.use { fileBlobStore.decryptToBytes(att.blobRef, it) }
                            } ?: return@LaunchedEffect
                            decryptedText = decryptedText + (att.blobRef to decrypted.decodeToString())
                        }
                    } catch (e: Exception) {
                        pageErrors = pageErrors + (att.blobRef to MediaErrors.openFailed(e))
                        if (MediaErrors.isRetryable(e)) retryablePages = retryablePages + att.blobRef
                    }
                }
            }
            FileKind.OFFICE -> {
                if (decryptedOffice[att.blobRef] == null && pageErrors[att.blobRef] == null) {
                    try {
                        // The same pre-check the TEXT branch above makes, and for the same reason:
                        // `decryptToBytes` buffers the whole blob, and attachment import streams
                        // non-image files in with no cap at all. Without this, opening a large
                        // spreadsheet allocated a ByteArray the size of the file and then had
                        // OfficeTextExtractor build strings on top of it. The cap is higher than
                        // TEXT's because a legitimate .xlsx or .pptx is routinely larger than a
                        // plain text file anyone would read on a phone.
                        if (fileBlobStore.blobSizeBytes(att.blobRef) > MAX_OFFICE_BYTES) {
                            pageErrors = pageErrors + (att.blobRef to MediaErrors.TEXT_TOO_LARGE)
                        } else {
                            val decrypted = withContext(Dispatchers.IO) {
                                sessionKey.use { fileBlobStore.decryptToBytes(att.blobRef, it) }
                            } ?: return@LaunchedEffect
                            decryptedOffice = decryptedOffice + (att.blobRef to decrypted)
                        }
                    } catch (e: Exception) {
                        pageErrors = pageErrors + (att.blobRef to MediaErrors.openFailed(e))
                        if (MediaErrors.isRetryable(e)) retryablePages = retryablePages + att.blobRef
                    }
                }
            }
            FileKind.PLAYABLE, FileKind.PDF, FileKind.OTHER -> {
                // Handled in place via streaming / lazy components
            }
        }
    }

    val c = LkTheme.colors
    val shown = chromeVisible && !isZoomed

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Horizontal Media Pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { pagerSize = it },
        ) { pageIndex ->
            val pageAtt = attachments[pageIndex]
            val declaredPageKind = remember(pageAtt.mime, pageAtt.filename) {
                fileKindOf(pageAtt.mime, pageAtt.filename)
            }
            val pageKind = sniffedKinds[pageAtt.blobRef] ?: declaredPageKind
            val errorRes = pageErrors[pageAtt.blobRef]

            // Inset for the floating chrome on every kind. IMAGE is inset only while the chrome is
            // shown and only when the picture would otherwise run under it ([imageRunsUnderChrome]);
            // it is the one kind whose tap takes the chrome away and leaves it edge to edge.
            //
            // Without the bottom inset a page taller than the viewport is centred *behind* the
            // action bar, and that bar is opaque rather than dim — `scrim` below is 0xE8, 91%.
            // A portrait video is exactly that case: the surface derives its height from the page
            // width through `aspectRatio`, so a 9:16 clip is 1.78x the page width tall and pushes
            // the player's transport row underneath the bar. The result is a black page, viewer
            // chrome, and no reachable Play button — reported from a phone 2026-09-08 as "black
            // screen and 3 icons Export details Trash but the video is not playing", and confirmed
            // on emulator-5560: the Play control measured [42,2076][168,2202] against a filmstrip
            // starting at y=1974. A 16:9 clip clears the bar, which is why this survived review.
            //
            // `chromeBarHeight` is the bar's own measured height — see its `onSizeChanged` below.
            val pageBitmap = if (pageKind == FileKind.IMAGE) decodedImages[pageAtt.blobRef]?.bitmap else null
            val imageUnderChrome = shown && pageBitmap != null && with(density) {
                imageRunsUnderChrome(
                    imageWidth = pageBitmap.width,
                    imageHeight = pageBitmap.height,
                    viewportWidthPx = pagerSize.width.toFloat(),
                    viewportHeightPx = pagerSize.height.toFloat(),
                    topBarPx = topChromeHeight.toPx(),
                    bottomBarPx = chromeBarHeight.toPx(),
                )
            }
            val insets = viewerPageInsets(pageKind, topChromeHeight, chromeBarHeight, imageUnderChrome)
            // An image's inset follows the chrome — it opens as the bars return and closes as a tap
            // takes them away — so it travels with them rather than jumping. Every other kind keeps
            // its inset fixed and unanimated, which is what it always was: animating it would slide
            // a document's first line down the screen the first time the bars are measured.
            val animatedTop by animateDpAsState(insets.top, lkTween(LkMotion.Base), label = "viewer-image-top")
            val animatedBottom by animateDpAsState(insets.bottom, lkTween(LkMotion.Base), label = "viewer-image-bottom")
            val padTop = if (pageKind == FileKind.IMAGE) animatedTop else insets.top
            val padBottom = if (pageKind == FileKind.IMAGE) animatedBottom else insets.bottom
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = insets.horizontal)
                    .padding(top = padTop, bottom = padBottom),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    // Before the kind switch and the error branch: a payload of no bytes has
                    // nothing to decode, so no kind can say anything true about it.
                    viewerShowsEmptyState(pageAtt.sizeBytes, text = null) -> EmptyFileState()
                    errorRes != null -> {
                        val message = stringResource(errorRes)
                        val retryable = pageAtt.blobRef in retryablePages
                        LkInlineError(
                            message,
                            Modifier.announce(message),
                            actionLabel = stringResource(R.string.files_viewer_retry).takeIf { retryable },
                            onAction = if (retryable) {
                                {
                                    // Both halves matter: the cached failure is what the decrypt
                                    // effect checks before doing anything, and the nonce is what
                                    // makes the effect run again on a page it has already visited.
                                    pageErrors = pageErrors - pageAtt.blobRef
                                    retryablePages = retryablePages - pageAtt.blobRef
                                    retryNonce++
                                }
                            } else {
                                null
                            },
                        )
                    }
                    pageKind == FileKind.IMAGE -> {
                        val decoded = decodedImages[pageAtt.blobRef]
                        if (decoded != null) {
                            ZoomableImage(
                                bitmap = decoded.bitmap,
                                isZoomShortcut = (pageIndex == currentIndex && isZoomed),
                                onToggleZoomShortcut = { isZoomed = !isZoomed },
                                onToggleChrome = {
                                    chromeVisible = !chromeVisible
                                    hintVisible = false
                                },
                                onSwipeUp = {
                                    sheetOpen = true
                                    hintVisible = false
                                },
                            )
                        } else {
                            // Decrypting shimmer placeholder
                            DecryptingPlaceholder()
                        }
                    }
                    pageKind == FileKind.PLAYABLE -> {
                        EncryptedVideoPlayer(
                            blobRef = pageAtt.blobRef,
                            sessionKey = sessionKey,
                            fileBlobStore = fileBlobStore,
                            // fillMaxSize, not fillMaxWidth: the player's Column now gives its
                            // transport row first claim on the height and lets the video surface
                            // take what is left, which it can only do inside a bounded height.
                            modifier = Modifier.fillMaxSize(),
                            onToggleChrome = {
                                chromeVisible = !chromeVisible
                                hintVisible = false
                            },
                            posterRef = pageAtt.thumbnailRef,
                        )
                    }
                    pageKind == FileKind.PDF -> {
                        EncryptedPdfViewer(
                            blobRef = pageAtt.blobRef,
                            sessionKey = sessionKey,
                            fileBlobStore = fileBlobStore,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    pageKind == FileKind.TEXT -> {
                        val text = decryptedText[pageAtt.blobRef]
                        if (text != null) {
                            PlainTextViewer(text)
                        } else {
                            LkSkeleton(Modifier.fillMaxSize())
                        }
                    }
                    pageKind == FileKind.OFFICE -> {
                        val bytes = decryptedOffice[pageAtt.blobRef]
                        if (bytes != null) {
                            OfficeTextViewer(bytes)
                        } else {
                            LkSkeleton(Modifier.fillMaxSize())
                        }
                    }
                    else -> {
                        val ext = pageAtt.filename.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.lowercase()
                        LkEmptyState(
                            icon = LkIcons.Attachment,
                            title = stringResource(R.string.files_viewer_no_preview_title),
                            body = if (ext != null) {
                                stringResource(R.string.files_viewer_no_preview_body_ext, ext)
                            } else {
                                stringResource(R.string.files_viewer_no_preview_body_generic)
                            },
                        )
                    }
                }
            }
        }

        // Top Chrome Bar
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(lkTween(LkMotion.Base)) + slideInVertically(lkTween(LkMotion.Base)) { -it },
            exit = fadeOut(lkTween(LkMotion.Quick)) + slideOutVertically(lkTween(LkMotion.Quick)) { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { topChromeHeight = with(density) { it.height.toDp() } }
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xE0040506),
                                Color(0x60040506),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LkIconButton(
                        icon = LkIcons.ChevronLeft,
                        label = stringResource(R.string.files_close),
                        onClick = onClose,
                        modifier = Modifier.lkTag("file-viewer-close"),
                        plateAlignment = Alignment.CenterStart,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // The item's own name, not its filename. Naming an item `GreenPhoto` and
                        // opening it to a header reading `1000000162.png` made the viewer the one
                        // screen that disagreed with the vault list, the search results and the row
                        // the user had just tapped. The filename is still one tap away in Details.
                        Text(
                            text = currentPageTitle,
                            style = LkText.rowTitle,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.lkTag("file-viewer-page-${currentAttachment.id}"),
                        )
                        val counterText = if (currentFolderPath.isNullOrBlank()) {
                            stringResource(
                                R.string.files_viewer_counter,
                                currentIndex + 1,
                                attachments.size,
                            )
                        } else {
                            stringResource(
                                R.string.files_viewer_counter_with_folder,
                                currentIndex + 1,
                                attachments.size,
                                currentFolderPath,
                            )
                        }
                        Text(
                            text = counterText,
                            style = LkText.caption,
                            color = c.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Zoom framing shortcut toggle
                    if (currentKind == FileKind.IMAGE) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .lkTag("file-viewer-zoom")
                                .clip(LkShape.md)
                                .clickable {
                                    isZoomed = !isZoomed
                                    hintVisible = false
                                }
                                .background(if (isZoomed) c.jade.copy(alpha = 0.16f) else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = LkIcons.Zoom,
                                contentDescription = stringResource(R.string.files_viewer_zoom_toggle),
                                tint = if (isZoomed) c.jade else c.textPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    if (onSettings != null) {
                        LkIconButton(
                            icon = LkIcons.Settings,
                            // "File settings" and "Details" were two unlabelled controls whose
                            // destinations overlap — both surface the file's name and folder — so
                            // the only thing distinguishing them was which one you had tried
                            // before. This one opens the editor, so it says so; the dots below
                            // open the read-only sheet and keep "Details".
                            label = stringResource(R.string.files_viewer_edit),
                            onClick = { onSettings(currentAttachment) },
                            modifier = Modifier.lkTag("file-viewer-settings"),
                            plateAlignment = Alignment.CenterEnd,
                        )
                    }
                    // No Details control here. There was one, and it left the viewer with two
                    // controls called "Details" on one screen — these dots and the bottom action
                    // bar's Info button — opening the same sheet, which is the same confusion the
                    // Edit rename above was meant to end rather than a second instance of it. The
                    // sheet keeps two routes, both of them unambiguous: the bottom bar, where it
                    // sits beside the other things you can do to this file, and the swipe-up
                    // gesture the hint advertises.
                }
            }
        }

        // Hint Pill. Positioned off the chrome bar's measured height rather than off a constant:
        // 190.dp was tuned against one particular bar — three-button navigation, more than one
        // attachment — and the bar is not that height in general. The filmstrip below is gated on
        // `attachments.size > 1` and the pill is gated on nothing, so a single-attachment file left
        // the pill floating some 70dp clear of the bar, untethered in the middle of the photo, for
        // the first 4.2s of every open. Reading the height keeps the two together on any device and
        // through any bar content, and it does not move the bar when the pill dismisses itself.
        //
        // Since 2026-10-09 it sits *inside* the bar's fade strip, centred in it, rather than a gap
        // above the bar. Above the bar it was drawn over the picture — over a stored screenshot's own
        // Save button on the device pass — and an image is now held clear of the bars exactly to stop
        // the viewer's chrome landing on its content.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = hintPillStripBottom(chromeBarHeight, fadeHeight))
                .height(fadeHeight),
            contentAlignment = Alignment.Center,
        ) {
            HintPill(visible = hintVisible && !sheetOpen && shown)
        }

        // Bottom Chrome Bar
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(lkTween(LkMotion.Base)) + slideInVertically(lkTween(LkMotion.Base)) { it },
            exit = fadeOut(lkTween(LkMotion.Quick)) + slideOutVertically(lkTween(LkMotion.Quick)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { chromeBarHeight = with(density) { it.height.toDp() } }
                    // Flat scrim behind the content, with the fade confined to a fixed strip above
                    // it. A single `verticalGradient` across this Column put `Color.Transparent`
                    // exactly at the filmstrip's top edge — the filmstrip is the Column's first
                    // child — so the ramp was spent on the action bar, which is icons and labels
                    // and reads on almost nothing, while the thumbnails got none of it. Measured on
                    // a device: 6% darkening at the top of the strip, 33% at its bottom, opaque
                    // below. Thumbnails are the one thing here that cannot live on a partial scrim,
                    // being pictures drawn on pictures: an inactive tile is `cardTop` at 0.62 layer
                    // alpha, so its ground is whatever the photo is, and its only edge is `ring` at
                    // 7% white cut to ~4.4% by that same alpha. Against a dark photo edge the tile
                    // measured 1.06:1 — legibility became a function of the user's picture. Drawing
                    // it this way also stops the ramp re-weighting itself when the filmstrip or an
                    // export notice changes the bar's height.
                    .drawBehind {
                        val scrim = Color(0xE8040506)
                        val fade = fadeHeight.toPx().coerceAtMost(size.height)
                        drawRect(
                            brush = Brush.verticalGradient(listOf(Color.Transparent, scrim), endY = fade),
                            size = Size(size.width, fade),
                        )
                        drawRect(
                            color = scrim,
                            topLeft = Offset(0f, fade),
                            size = Size(size.width, size.height - fade),
                        )
                    }
                    .padding(top = fadeHeight)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Filmstrip (shown when there are attachments)
                if (attachments.size > 1) {
                    FilmstripThumbnailRow(
                        attachments = attachments,
                        currentIndex = currentIndex,
                        thumbnails = thumbnails,
                        onSelectIndex = { idx ->
                            scope.launch { pagerState.animateScrollToPage(idx) }
                            hintVisible = false
                        },
                    )
                }

                // Export progress / confirmation notices
                if (exporting) {
                    LkSkeleton(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .height(18.dp),
                    )
                }
                exported?.let {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        LkInlineNote(stringResource(R.string.files_viewer_exported_to, it))
                    }
                }
                exportError?.let {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        LkInlineError(stringResource(it))
                    }
                }

                // Action Bar (Export, Details, Delete)
                BottomActionBar(
                    // Dimmed only while an export is actually running, which the skeleton above
                    // explains on its own. It used to be dimmed on `GalleryExporter.isSupported()`
                    // too — permanently, and with no copy anywhere, on every device running the
                    // app's own `minSdk` of 28. A control greyed out for a reason the app never
                    // states is worse than one that answers when pressed, so the OS check moved
                    // into the tap below and speaks.
                    exportEnabled = !exporting,
                    // Asks first. This is the one control in the viewer that moves vault content
                    // out from behind the vault's protection — a decrypted copy, into shared
                    // storage, readable by anything with file access — and it was a single tap
                    // next to Details. The encrypted `.vault` export re-verifies identity before
                    // it writes; this is the cheaper equivalent, proportional to a copy the user
                    // can delete again.
                    onExport = {
                        val blocked = viewerExportBlockedReason(GalleryExporter.isSupported())
                        if (blocked == null) confirmExport = true else exportError = blocked
                    },
                    onDetails = {
                        sheetOpen = true
                        hintVisible = false
                    },
                    onDelete = if (onDelete != null) {
                        { confirmDelete = true }
                    } else null,
                    deleteLabel = deleteLabel,
                )
            }
        }

        // Details Bottom Sheet Overlay
        AnimatedVisibility(
            visible = sheetOpen,
            enter = fadeIn(lkTween(LkMotion.Base)) + slideInVertically(lkTween(LkMotion.Base)) { it },
            exit = fadeOut(lkTween(LkMotion.Quick)) + slideOutVertically(lkTween(LkMotion.Quick)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            MediaDetailsSheet(
                attachment = currentAttachment,
                fileKind = currentKind,
                dimensions = decodedImages[currentAttachment.blobRef]?.dimensions,
                folderPath = currentFolderPath,
                notes = currentNotes,
                onDismiss = { sheetOpen = false },
            )
        }

        // Export confirmation. Not destructive, so not styled as such — the cost of an accepted
        // export is a readable copy the user then has to find and remove, not a lost file.
        if (confirmExport) {
            LkDialog(
                onDismiss = { confirmExport = false },
                title = stringResource(R.string.files_viewer_export_dialog_title),
                body = stringResource(R.string.files_viewer_export_dialog_body),
                icon = LkIcons.Export,
                accent = c.amber,
                confirm = LkDialogAction(
                    label = stringResource(R.string.files_viewer_export),
                    onClick = {
                        confirmExport = false
                        // The SDK check is repeated here, and must be. The block above only opens
                        // this dialog when `viewerExportBlockedReason` passed, and that reads
                        // `GalleryExporter.isSupported()`, which carries `@ChecksSdkIntAtLeast` —
                        // but the result crosses a `remember`ed flag and this `onClick` lambda
                        // before it is used, and lint cannot carry the narrowing across either.
                        // Dropping this line as redundant is what turned `GalleryExporter.restore`
                        // (`@RequiresApi(Q)`) into a `NewApi` error against `minSdk` 28.
                        if (!exporting && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            exporting = true
                            exported = null
                            exportError = null
                            scope.launch {
                                try {
                                    val uri = withContext(Dispatchers.IO) {
                                        GalleryExporter.restore(
                                            context,
                                            currentAttachment.filename,
                                            currentAttachment.mime,
                                        ) { out ->
                                            // A null borrow throws rather than silently writing a
                                            // short file: `GalleryExporter.restore` is mid-write
                                            // into an IS_PENDING entry, and the only correct end
                                            // for it is the failure path that discards the entry.
                                            checkNotNull(
                                                sessionKey.use {
                                                    fileBlobStore.decryptToStream(currentAttachment.blobRef, it, out)
                                                },
                                            ) { "vault locked during export" }
                                        }
                                    }
                                    exported = withContext(Dispatchers.IO) {
                                        GalleryExporter.destinationOf(
                                            context,
                                            uri,
                                            currentAttachment.filename,
                                            currentAttachment.mime,
                                        )
                                    }
                                } catch (e: Exception) {
                                    exportError = MediaErrors.restoreFailed(e)
                                } finally {
                                    exporting = false
                                }
                            }
                        }
                    },
                ),
            )
        }

        // Deletion confirmation dialog. Its copy follows `deleteLabel`, because the three mounts
        // mean three different acts by the same button — see [viewerDeleteCopy].
        if (confirmDelete && onDelete != null) {
            val deleteCopy = viewerDeleteCopy(deleteLabel)
            LkDialog(
                onDismiss = { confirmDelete = false },
                title = stringResource(deleteCopy.title, currentAttachment.filename),
                body = stringResource(deleteCopy.body),
                icon = LkIcons.Trash,
                accent = c.coral,
                confirm = LkDialogAction(
                    label = stringResource(deleteCopy.confirm),
                    onClick = {
                        confirmDelete = false
                        onDelete.invoke(currentAttachment)
                    },
                    emphasis = LkActionEmphasis.Destructive,
                ),
            )
        }
    }
}

/**
 * Centered decrypting shimmer plate with jade lock icon and DECRYPTING label.
 *
 * Matches mock Note 04 ("Honest latency reads as security work").
 */
@Composable
private fun DecryptingPlaceholder() {
    val c = LkTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LkSkeleton(
                Modifier
                    .size(width = 240.dp, height = 300.dp)
                    .clip(LkShape.card),
            )
            Row(
                modifier = Modifier
                    .lkSurface(
                        shape = RoundedCornerShape(999.dp),
                        color = c.surface.copy(alpha = 0.85f),
                        ring = c.jade.copy(alpha = 0.35f),
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = LkIcons.ShieldCheck,
                    contentDescription = null,
                    tint = c.jade,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(R.string.files_viewer_decrypting),
                    style = LkText.monoCaption,
                    color = c.jade,
                )
            }
        }
    }
}

/** Max pixels per edge for on-screen decode — bounds decode memory on large images. */
private const val VIEWER_MAX_DIM = 2048

/** Decodes [bytes] downsampled to <= [VIEWER_MAX_DIM] and extracts original image dimensions. */
internal fun decodeViewerBitmapWithDimensions(bytes: ByteArray): Pair<android.graphics.Bitmap?, String?> {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Pair(null, null)
    // As the picture is shown, so the portrait photo of a landscape sensor reads "3024 × 4032".
    // `BitmapFactory` ignores the Exif orientation and a File item keeps its original bytes, so the
    // viewer turns the bitmap upright itself (S3-01, 2026-10-02).
    val swapped = ExifStripper.orientationSwapsAxes(ExifStripper.displayOrientation(bytes))
    val dimensions = if (swapped) {
        "${bounds.outHeight} × ${bounds.outWidth}"
    } else {
        "${bounds.outWidth} × ${bounds.outHeight}"
    }
    // Divide by the sample being tested, not by the next one up. Peeking ahead — `/ (sample * 2)` —
    // stopped one doubling early, so the decoded edge landed anywhere in [VIEWER_MAX_DIM, 2 ×
    // VIEWER_MAX_DIM) rather than under it: four times the pixels, four times the bytes, and the
    // ordinary case was the worst one. A stock 4032 × 3024 phone photo tested 4032 / 2 = 2016 and
    // 3024 / 2 = 1512, both under 2048, so `sample` never left 1 and the image decoded at full
    // resolution — ~48 MB of ARGB_8888 for a bound that promised ~16 MB. The sibling thumbnail
    // decoder in PhotoPickerImport already compares against `sample` for this reason.
    var sample = 1
    while (bounds.outWidth / sample > VIEWER_MAX_DIM || bounds.outHeight / sample > VIEWER_MAX_DIM) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        ?.let { ExifStripper.uprightBitmap(it, bytes) }
    return Pair(bitmap, dimensions)
}

/**
 * Max pixels per edge for a filmstrip thumbnail.
 *
 * The strip draws at 46–56dp, so 128 covers the largest of those at a 4x density with room to spare
 * and costs ~64 KB of ARGB_8888 per entry. Sized once here rather than from the composable's density:
 * the cache is keyed by blobRef and shared across every strip size, and a per-density decode would
 * re-read and re-decrypt the whole blob on a font-scale change.
 */
private const val THUMB_MAX_DIM = 128

/**
 * Decodes [bytes] down to at most [THUMB_MAX_DIM] per edge, or null if it is not a decodable image.
 *
 * Compares against `sample` rather than `sample * 2`, for the reason spelled out in
 * [decodeViewerBitmapWithDimensions]: peeking ahead stops one doubling early and returns an image
 * up to twice the intended edge — four times the pixels — which on a per-thumbnail cache is the
 * difference between a bounded strip and a bitmap-per-page leak wearing a bound's name.
 */
internal fun decodeThumbnailBitmap(bytes: ByteArray): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > THUMB_MAX_DIM || bounds.outHeight / sample > THUMB_MAX_DIM) {
        sample *= 2
    }
    // Upright per Exif orientation, for the same reason as the viewer's decode above (S3-01).
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.let { ExifStripper.uprightBitmap(it, bytes) }
}

/**
 * The stored thumbnail's plaintext, or null if there isn't one this call can use.
 *
 * Null rather than throwing on every way it can be unusable — absent, missing from the blob store,
 * or failing its authentication tag — because the caller's answer to all three is the same: decode
 * the full blob instead. `decryptToBytes` throws `FileNotFoundException` on a blob that is not
 * there, so a `?.let` around it would send a dangling `thumbnailRef` to the viewer's catch-all and
 * leave an extension badge over a photograph the app can perfectly well draw.
 *
 * No `CancellationException` special case: `decryptToBytes` is blocking IO with no suspension point
 * inside it, so it cannot be the thing that raises one.
 */
private fun thumbnailBytesOrNull(
    fileBlobStore: FileBlobStore,
    thumbnailRef: String?,
    dek: ByteArray,
): ByteArray? = thumbnailRef
    ?.takeIf { fileBlobStore.hasBlob(it) }
    ?.let { runCatching { fileBlobStore.decryptToBytes(it, dek) }.getOrNull() }

/** How far either side of the current page the filmstrip keeps decoded thumbnails. */
internal const val FILMSTRIP_THUMB_RADIUS = 8

/**
 * The `blobRef`s the filmstrip thumbnail cache may hold: [currentIndex] and [FILMSTRIP_THUMB_RADIUS]
 * pages either side of it.
 *
 * Wider than [viewerKeepWindow] because these are ~64 KB thumbnails rather than multi-megabyte
 * full-screen decodes, and because the strip shows roughly six at once and scrolls to track the
 * current page — a ±1 window would leave every thumbnail but three as lettering. It is still a
 * window: the cache is bounded by a constant, not by how far the user browses, which is the property
 * the ±1 window on the full-size cache exists to guarantee.
 */
internal fun filmstripKeepWindow(attachments: List<Attachment>, currentIndex: Int): Set<String> =
    if (attachments.isEmpty()) {
        emptySet()
    } else {
        val lo = (currentIndex - FILMSTRIP_THUMB_RADIUS).coerceAtLeast(0)
        val hi = (currentIndex + FILMSTRIP_THUMB_RADIUS).coerceAtMost(attachments.lastIndex)
        attachments.slice(lo..hi).mapTo(HashSet()) { it.blobRef }
    }

/**
 * Continuous 1-6x pinch-to-zoom + pan, double-tap zoom shortcut, single-tap chrome toggle, and swipe up.
 */
internal fun viewerImageModifier(): Modifier = Modifier.fillMaxSize()

/**
 * Leaves a base-scale one-finger horizontal drag for [HorizontalPager], while retaining image
 * pan and pinch handling when the image is zoomed or has multiple active pointers.
 */
internal fun shouldConsumeViewerTransformGesture(
    pointerCount: Int,
    effectiveScale: Float,
    pan: Offset,
    zoom: Float,
): Boolean = pointerCount > 1 ||
    (effectiveScale > 1.05f && (pan != Offset.Zero || zoom != 1f))

/** The icon, the gap under it and the item's own vertical padding — none of which follow the font. */
private val VIEWER_ACTION_FIXED_HEIGHT = 38.dp

/** The caption's line box at the default font scale. */
private val VIEWER_ACTION_LABEL_HEIGHT = 18.dp

/** What the bar measured before this was a function of anything, and still its floor. */
private val VIEWER_ACTION_SHIPPED_HEIGHT = 60.dp

/**
 * The floor for one of the viewer's bottom action items, as a function of the user's font scale.
 *
 * It was a flat `height(60.dp)` — a dp box holding sp text, the defect this app has now shipped a
 * fix for on the health ring, the health category badge and the card-number well. At `font_scale`
 * 2.0 the caption outgrew the box and was cut across the middle: "Export" rendered as `Fxnort` with
 * its lower third gone, measured at 57 px of bar for roughly twice-size text (release sweep
 * 2026-09-20, finding 4C.2f-2).
 *
 * Only the caption grows: the icon is 22dp, the gap 4dp and the padding 12dp whatever the font does.
 * A floor rather than a fixed height, so the text still has somewhere to go if it needs more than
 * this arithmetic predicts.
 */
internal fun viewerActionItemHeight(fontScale: Float): Dp =
    maxOf(
        VIEWER_ACTION_SHIPPED_HEIGHT,
        VIEWER_ACTION_FIXED_HEIGHT + VIEWER_ACTION_LABEL_HEIGHT * fontScale.coerceAtLeast(1f),
    )

/** What one tap on the image does, given the scale it is at. */
internal data class ViewerTapAction(val exitsZoom: Boolean, val togglesChrome: Boolean)

/**
 * A tap on a zoomed image leaves zoom. A tap on an unzoomed one toggles the chrome. It never does
 * both.
 *
 * It used to do both on the zoomed branch, and the chrome was already hidden by the zoom, so the one
 * tap that ended zoom also flipped `chromeVisible` to false — the image came back to its normal size
 * with no title, no close button and no action bar, and a second tap was needed to get them. The
 * double-tap escape did not have the bug, which is why "double-tap works, single tap does nothing"
 * was how it was reported (release sweep 2026-09-20, finding 4C.2-zoom-3).
 */
internal fun viewerSingleTapAction(effectiveScale: Float): ViewerTapAction =
    if (effectiveScale > 1.05f) {
        ViewerTapAction(exitsZoom = true, togglesChrome = false)
    } else {
        ViewerTapAction(exitsZoom = false, togglesChrome = true)
    }

/** How far a viewer page's content is held clear of the chrome that floats over it. */
internal data class ViewerPageInsets(val top: Dp, val bottom: Dp, val horizontal: Dp)

/**
 * The gap a page of [kind] leaves for the viewer's two floating bars.
 *
 * [FileKind.IMAGE] alone draws edge to edge: a photograph is meant to fill the screen, the chrome
 * fades over it on a gradient, and a tap takes the chrome away entirely. The exception is
 * [imageUnderChrome] — an image whose own content would sit under a shown bar, a screenshot being
 * the everyday case — which is held between the bars until they are hidden. Everything else — text,
 * markdown, an Office extraction, a PDF, a video surface — is content that has to be READ, and it
 * has no such escape: the tap-to-hide gesture is wired only on the image and video branches, and
 * the bottom bar is a 91% scrim rather than a dim.
 *
 * The bottom inset has been here since a portrait video pushed its own Play button under the action
 * bar (2026-09-08). The top one had not, and the same defect was live at the other end for every
 * non-media page: the first two lines of every text, markdown, Office and PDF file were drawn
 * beneath the title and the close button. The worst case is a zero-byte file, whose whole content is
 * the single line "This file is empty." — entirely hidden, so the screen read as blank black
 * (release sweep 2026-09-20, findings 4C.2-chrome / 4C.2-txt / 4C.2-zero).
 *
 * Both heights are the bars' own measured heights, never constants: they carry a status-bar inset, a
 * navigation-bar inset, a filmstrip that only appears past one attachment, and an export notice that
 * comes and goes.
 */
internal fun viewerPageInsets(
    kind: FileKind,
    topChromeHeight: Dp,
    bottomChromeHeight: Dp,
    imageUnderChrome: Boolean = false,
): ViewerPageInsets =
    when {
        kind != FileKind.IMAGE ->
            ViewerPageInsets(top = topChromeHeight, bottom = bottomChromeHeight, horizontal = LkSpace.md)
        // Held between the bars only while they are on screen and only when the picture would
        // otherwise run under one — see [imageRunsUnderChrome]. Never sideways: Fit already leaves
        // the width alone, and an image only collides with bars that span it top and bottom.
        imageUnderChrome ->
            ViewerPageInsets(top = topChromeHeight, bottom = bottomChromeHeight, horizontal = 0.dp)
        else -> ViewerPageInsets(top = 0.dp, bottom = 0.dp, horizontal = 0.dp)
    }

/**
 * Where the hint pill's strip starts, measured up from the bottom of the screen: the top edge of the
 * bottom bar's content, so the strip is the bar's own [fadeHeight] fade and nothing above it.
 * Zero until the bar has been measured.
 */
internal fun hintPillStripBottom(barHeight: Dp, fadeHeight: Dp): Dp =
    (barHeight - fadeHeight).coerceAtLeast(0.dp)

/**
 * Whether an image drawn with `ContentScale.Fit`, centred in the viewport, would reach under either
 * floating bar.
 *
 * Reported from a phone 2026-10-09: *"the < and the name of the file are overlapping with the photo
 * itself"* — for a stored screenshot, never for a camera photo or a PDF. A screenshot has the
 * screen's own shape, so Fit fills the full height and the screenshot's own status bar and buttons
 * sat under the viewer's close button, title and action bar, reading as one app drawn over another.
 * A 4:3 photo letterboxes into black margins taller than either bar, which is where the bars sit and
 * why it never showed there.
 *
 * Only the overlap case is inset, so every photo that already cleared the bars is drawn exactly as
 * before. When it is, the image is held between both bars and centred there, which cannot overlap
 * either: the region it fits into ends where the bars begin. A tap that hides the chrome, or zoom,
 * still takes the picture edge to edge — the caller passes the visibility, not this function.
 *
 * Unmeasured sizes answer false: the first frame, before the bitmap or the pager has a size, draws
 * edge to edge rather than guessing.
 */
internal fun imageRunsUnderChrome(
    imageWidth: Int,
    imageHeight: Int,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    topBarPx: Float,
    bottomBarPx: Float,
): Boolean {
    if (imageWidth <= 0 || imageHeight <= 0 || viewportWidthPx <= 0f || viewportHeightPx <= 0f) return false
    val scale = minOf(viewportWidthPx / imageWidth, viewportHeightPx / imageHeight)
    val margin = (viewportHeightPx - imageHeight * scale) / 2f
    return margin < topBarPx || margin < bottomBarPx
}

/**
 * The `blobRef`s of the pages the decoded-content caches are allowed to hold onto: [currentIndex]
 * and its one neighbour on each side.
 *
 * This is the whole fix for 9-1 — the caches used to be additive and never shrank, so a 30-photo
 * gallery ended the session holding 30 decoded bitmaps in the native heap, and the process could
 * die from a low-memory kill that throws no `OutOfMemoryError` for anything downstream to catch.
 * The window is ±1 rather than 0 because [HorizontalPager] composes the neighbour during a swipe,
 * and evicting it there would force a re-decode on every drag.
 */
internal fun viewerKeepWindow(attachments: List<Attachment>, currentIndex: Int): Set<String> =
    if (attachments.isEmpty()) {
        emptySet()
    } else {
        attachments
            .slice((currentIndex - 1).coerceAtLeast(0)..(currentIndex + 1).coerceAtMost(attachments.lastIndex))
            .mapTo(HashSet()) { it.blobRef }
    }

/** Title, body and confirm label for the viewer's delete confirmation. */
internal data class ViewerDeleteCopy(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val confirm: Int,
)

/**
 * The confirmation copy that goes with a delete button labelled [deleteLabel].
 *
 * Derived from the label rather than passed separately, because the defect this exists to prevent
 * is exactly the two disagreeing: the standalone viewer named its button "Trash" and then raised
 * the tier-A removal dialog beside it, telling the user "There is no undo here" about a
 * `moveToTrash` that only sets `deletedAt` and leaves the blob referenced until a purge. One
 * parameter naming the act, all three strings following from it, and a mount cannot say two things.
 *
 * Anything that is not the trash label keeps the blunt permanent-removal copy — the same safe
 * direction [SecureMediaViewer]'s `deleteLabel` default takes, for the same reason: a caller that
 * forgets to say "recoverable" understates what it can undo.
 */
internal fun viewerDeleteCopy(@StringRes deleteLabel: Int): ViewerDeleteCopy =
    if (deleteLabel == R.string.files_viewer_trash) {
        ViewerDeleteCopy(
            title = R.string.files_viewer_trash_dialog_title,
            body = R.string.files_viewer_trash_dialog_body,
            confirm = R.string.files_viewer_trash_dialog_action,
        )
    } else {
        ViewerDeleteCopy(
            title = R.string.files_attachments_remove_dialog_title,
            body = R.string.files_attachments_remove_dialog_body,
            confirm = R.string.files_remove,
        )
    }

/**
 * Why the viewer's Export action cannot run, or null when it can.
 *
 * The control is enabled on [galleryExportSupported] — `GalleryExporter.isSupported()`, API 29+ —
 * which is always false on API 28, the oldest device this app supports. Below this function the
 * button used to just sit dim forever there, with nothing on the screen saying Android 10 was
 * the reason.
 *
 * Below API 29 there is no second route to try. Writing outside the app's own storage there means
 * `WRITE_EXTERNAL_STORAGE`, and the `foss` merged manifest is pinned by `checkFlavorPermissions` to
 * the two biometric permissions — so the honest answer is the OS version, not a workaround.
 *
 * A caller-supplied export handler that bypassed `MediaStore` entirely used to sit beside
 * [galleryExportSupported] here. None of the three screens that mount this viewer ever supplied
 * one, so the confirm dialog's branch for it could never run — an unreachable route to the same
 * decrypted bytes the gallery path already writes. Removed rather than wired, since the export
 * capability itself stays reachable through the path below.
 */
@StringRes
internal fun viewerExportBlockedReason(
    galleryExportSupported: Boolean,
): Int? = if (galleryExportSupported) {
    null
} else {
    MediaErrors.EXPORT_NEEDS_NEWER_ANDROID
}

/**
 * Whether a vertical drag over the details sheet should put it away.
 *
 * The sheet draws the app's drag handle, and until this existed that handle was a picture of an
 * affordance: the sheet had no drag gesture, and its body deliberately swallows taps, so the one
 * mark the whole app uses to say "you can pull this" did nothing on this screen while doing the
 * real thing on every [com.localkeep.core.ui.theme.LkSheet]. Downward only, and past the same slop
 * the viewer's swipe-*up* uses to open the sheet, so the two halves of one gesture are symmetric.
 */
internal fun shouldDismissDetailsSheet(dragAmount: Float): Boolean = dragAmount > VIEWER_SHEET_DRAG_SLOP

/** Matches the swipe-up threshold that opens the sheet, so opening and closing take the same pull. */
private const val VIEWER_SHEET_DRAG_SLOP = 20f

/** Transform gesture detector that does not steal the pager's base-scale horizontal swipe. */
private suspend fun PointerInputScope.detectViewerTransformGestures(
    effectiveScale: () -> Float,
    onGesture: (pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var tracking = true
        while (tracking) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) {
                tracking = false
                continue
            }

            val pan = event.calculatePan()
            val zoom = event.calculateZoom()
            if (
                shouldConsumeViewerTransformGesture(
                    pointerCount = event.changes.count { it.pressed },
                    effectiveScale = effectiveScale(),
                    pan = pan,
                    zoom = zoom,
                )
            ) {
                onGesture(pan, zoom)
                event.changes.forEach { it.consume() }
            }
        }
    }
}

// The bitmap is non-null because the one call site only reaches here with a decoded page: a decode
// that produced nothing is recorded in `pageErrors` and drawn by the error arm above instead. This
// used to be nullable, with an empty state behind it that nothing could ever route to — and it was
// the only place in the module that told the user what to *do* about an undecodable image, which is
// why the reachable path said nothing but "Can't display this image" for so long. That text is now
// where the failure is decided, in MediaErrors.
@Composable
private fun ZoomableImage(
    bitmap: ImageBitmap,
    isZoomShortcut: Boolean,
    onToggleZoomShortcut: () -> Unit,
    onToggleChrome: () -> Unit,
    onSwipeUp: () -> Unit,
) {
    var userScale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val effectiveScale = if (isZoomShortcut && userScale <= 1.05f) 1.55f else userScale

    // Every value the gesture handlers below read has to come through `rememberUpdatedState`.
    // `pointerInput(Unit)` never restarts, so its coroutine keeps the closure it was built with on
    // the first composition — where `isZoomShortcut` was false and `effectiveScale` was 1. A single
    // tap on a zoomed image was therefore swallowed: the handler tested a scale that was still 1,
    // took the "not zoomed" branch, and did nothing. That left no way out of zoom at all, because
    // the Toggle-zoom button is itself hidden while zoomed. Keying the pointerInput on the scale
    // instead would cancel and rebuild the gesture detector mid-gesture, which is worse.
    val scaleNow by rememberUpdatedState(effectiveScale)
    val shortcutNow by rememberUpdatedState(isZoomShortcut)
    val toggleZoomNow by rememberUpdatedState(onToggleZoomShortcut)
    val toggleChromeNow by rememberUpdatedState(onToggleChrome)
    val swipeUpNow by rememberUpdatedState(onSwipeUp)
    val cornerRadius by animateDpAsState(
        targetValue = if (effectiveScale > 1.05f) 4.dp else 14.dp,
        label = "image-corner",
    )

    // A named, clickable node on the image itself, in every state including zoomed.
    //
    // Zooming hides the chrome (`shown = chromeVisible && !isZoomed`), and the image carried no
    // semantics at all, so a zoomed viewer dumped as nothing but unnamed FrameLayouts: no node to
    // focus, and therefore nothing for TalkBack's own double-tap — which is "activate the focused
    // node" — to activate. There was no way out of zoom with a screen reader on (release sweep
    // 2026-09-20, finding 4C.2-zoom-3). `semantics` rather than `clickable`: the touch gestures
    // below already handle the tap, and a second handler would take the double-tap with it.
    val chromeLabel = stringResource(R.string.files_viewer_toggle_chrome_a11y)
    val imageLabel = stringResource(R.string.files_kind_image)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics(mergeDescendants = true) {
                contentDescription = imageLabel
                onClick(label = chromeLabel) {
                    val action = viewerSingleTapAction(scaleNow)
                    if (action.exitsZoom) {
                        userScale = 1f
                        offset = Offset.Zero
                        if (shortcutNow) toggleZoomNow()
                    }
                    if (action.togglesChrome) toggleChromeNow()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scaleNow > 1.05f) {
                            userScale = 1f
                            offset = Offset.Zero
                            if (shortcutNow) toggleZoomNow()
                        } else {
                            toggleZoomNow()
                        }
                    },
                    onTap = {
                        val action = viewerSingleTapAction(scaleNow)
                        if (action.exitsZoom) {
                            userScale = 1f
                            offset = Offset.Zero
                            if (shortcutNow) toggleZoomNow()
                        }
                        if (action.togglesChrome) toggleChromeNow()
                    },
                )
            }
            .pointerInput(Unit) {
                detectViewerTransformGestures(effectiveScale = { scaleNow }) { pan, zoom ->
                    val newScale = (userScale * zoom).coerceIn(1f, 6f)
                    userScale = newScale
                    // Against the *effective* scale, not the raw one. The zoom shortcut never
                    // touches `userScale` — it is expressed entirely by `isZoomShortcut` — so on
                    // the shortcut's 1.55x this read 1, took the "not zoomed" branch, and reset the
                    // offset on every drag. A one-finger drag on a visibly enlarged image moved
                    // nothing, by the most discoverable route into zoom there is: a labelled button
                    // and the double-tap every other photo viewer uses.
                    val nextEffective = if (shortcutNow && newScale <= 1.05f) 1.55f else newScale
                    if (nextEffective > 1.05f) {
                        offset += pan
                    } else {
                        offset = Offset.Zero
                    }
                }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount < -20f && scaleNow <= 1.05f) {
                        swipeUpNow()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = viewerImageModifier()
                .clip(RoundedCornerShape(cornerRadius))
                .graphicsLayer(
                    scaleX = effectiveScale,
                    scaleY = effectiveScale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

/**
 * Filmstrip thumbnail strip with active thumbnail growth (46 -> 56px) and jade border.
 */
@Composable
private fun FilmstripThumbnailRow(
    attachments: List<Attachment>,
    currentIndex: Int,
    /** Decoded thumbnails by `blobRef`; a page absent here falls back to its extension badge. */
    thumbnails: Map<String, ImageBitmap>,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        if (currentIndex in attachments.indices) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(attachments, key = { _, it -> it.id.toString() }) { index, item ->
            val isActive = index == currentIndex
            val targetSize = if (isActive) 56.dp else 46.dp
            val size by animateDpAsState(targetValue = targetSize, label = "thumb-size")
            val kind = remember(item.mime, item.filename) { fileKindOf(item.mime, item.filename) }
            val ext = item.filename.substringAfterLast('.', "").uppercase().take(4)

            // The clickable is the outer box, and only the inner one carries `size`. An inactive
            // thumbnail rests at 46dp by design, and `.size()` fixes the constraints it hands down,
            // so a `minTouchTarget()` anywhere below it is inert — the hit rectangle really was
            // 46dp square, under the 48dp floor the rest of the app is held to. Splitting the two
            // keeps the resting look and gives the target the floor; the active thumb is already
            // 56dp and is unaffected.
            // The thumbnail's only named descendant is the extension badge, so every thumbnail in
            // the strip announced as the same three or four letters — "PNG, button", six times over
            // — with nothing saying which file it was or where in the strip it sat. The active page
            // was distinguishable too, but only by being 56dp instead of 46dp: a size difference,
            // which is exactly the kind of meaning a screen reader cannot see. `selected` is what
            // carries that state into the semantics tree; the label carries the identity.
            val thumbLabel = stringResource(
                R.string.files_viewer_thumb_a11y,
                item.filename,
                index + 1,
                attachments.size,
            )
            Box(
                modifier = Modifier
                    .clickable { onSelectIndex(index) }
                    .minTouchTarget()
                    .semantics {
                        contentDescription = thumbLabel
                        selected = isActive
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(size)
                        .clip(RoundedCornerShape(11.dp))
                        .lkSurface(
                            shape = RoundedCornerShape(11.dp),
                            color = if (isActive) c.surface else c.cardTop,
                            ring = if (isActive) c.jade else c.ring,
                        )
                        .graphicsLayer(alpha = if (isActive) 1f else 0.62f),
                    contentAlignment = Alignment.Center,
                ) {
                    val thumb = thumbnails[item.blobRef]
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = null,
                            // Crop, not Fit: a strip of pictures at different aspect ratios letterboxed
                            // into identical squares reads as a strip of broken pictures.
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(11.dp)),
                        )
                    } else {
                        Text(
                            text = ext.ifEmpty { stringResource(kind.badgeRes) },
                            style = LkText.badge,
                            color = if (isActive) c.jade else c.textMuted,
                            maxLines = 1,
                        )
                    }
                    // Only over a picture. With no decoded thumbnail the branch above draws the
                    // extension as text — "MP4" — and this badge was then centred on top of it, so
                    // the triangle landed on the P and the tile read "M(play)4". Two independent
                    // lenses of the 2026-09-09 sweep caught it with pixel crops; confirmed by zoom.
                    // A play badge over a frame is the standard affordance and stays; over a
                    // three-letter label it is just a collision, and the label already says the
                    // tile is a video.
                    if (kind == FileKind.PLAYABLE && thumb != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = LkIcons.Play,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hint pill that auto-dismisses after 4.2s.
 */
@Composable
private fun HintPill(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(lkTween(LkMotion.Base)),
        exit = fadeOut(lkTween(LkMotion.Quick)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .lkSurface(
                    shape = RoundedCornerShape(999.dp),
                    color = c.surface.copy(alpha = 0.85f),
                    ring = c.ring,
                )
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.files_viewer_hint),
                style = LkText.caption,
                color = c.textSecondary,
            )
        }
    }
}

/**
 * Bottom action bar with Export, Details, Delete (60dp height, 48dp+ touch target).
 */
@Composable
private fun BottomActionBar(
    exportEnabled: Boolean,
    onExport: () -> Unit,
    onDetails: () -> Unit,
    onDelete: (() -> Unit)?,
    @StringRes deleteLabel: Int,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomActionItem(
            icon = LkIcons.Export,
            label = stringResource(R.string.files_viewer_export),
            enabled = exportEnabled,
            onClick = onExport,
            tint = c.textPrimary,
            modifier = Modifier.lkTag("file-viewer-export"),
        )
        BottomActionItem(
            icon = LkIcons.Info,
            label = stringResource(R.string.files_viewer_details),
            onClick = onDetails,
            tint = c.textPrimary,
            modifier = Modifier.lkTag("file-viewer-details"),
        )
        if (onDelete != null) {
            BottomActionItem(
                icon = LkIcons.Trash,
                label = stringResource(deleteLabel),
                onClick = onDelete,
                tint = c.coral,
                modifier = Modifier.lkTag("file-viewer-delete"),
            )
        }
    }
}

@Composable
private fun BottomActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    Column(
        modifier = modifier
            .heightIn(min = viewerActionItemHeight(LocalDensity.current.fontScale))
            .widthIn(min = 64.dp)
            .clip(LkShape.md)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else c.textFaint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = LkText.caption,
            color = if (enabled) tint.copy(alpha = 0.85f) else c.textFaint,
            maxLines = 1,
        )
    }
}

/**
 * Details bottom sheet overlay displaying real metadata fields per §3.3.
 */
@Composable
private fun MediaDetailsSheet(
    attachment: Attachment,
    fileKind: FileKind,
    dimensions: String?,
    folderPath: String?,
    notes: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.scrim.copy(alpha = 0.62f))
            // Named, or the scrim is a full-screen unlabelled button to a screen reader (G15).
            // `.labelled(...)` as well as `onClickLabel`, not instead of it: the label is the
            // node's NAME and `onClickLabel` is only the "double tap to..." action hint, so the
            // hint alone left the node still unnamed — a full-screen clickable dumping with empty
            // text, desc and id (V19, 2026-09-12). LkFolderPicker.kt:610 already wrote this down.
            .labelled(stringResource(R.string.files_viewer_dismiss_details_a11y))
            .clickable(
                onClickLabel = stringResource(R.string.files_viewer_dismiss_details_a11y),
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val sheetTitle = stringResource(R.string.files_viewer_details)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Swallows taps so they do not reach the dismiss handler on the scrim behind. It
                // used to be `clickable(enabled = false) {}`, which does block the tap but also
                // leaves a semantics node announcing the sheet's whole body as *disabled* — a
                // screen reader was told the panel it had just opened could not be used.
                // `pointerInput` blocks the gesture and contributes no semantics at all.
                .pointerInput(Unit) { detectTapGestures { } }
                // And makes the drag handle below mean something. The whole sheet is the drag
                // target rather than the 38x4dp bar, which is how Material's own sheets behave and
                // the only way to have one without a control far under the 48dp touch floor.
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (shouldDismissDetailsSheet(dragAmount)) onDismiss()
                    }
                }
                // And name it, so it announces as a panel rather than as loose text appearing
                // under the viewer.
                .semantics { paneTitle = sheetTitle }
                .lkSurface(
                    shape = LkShape.sheet,
                    color = c.sheetTop,
                    ring = c.ring,
                )
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Drag handle. It is the app's mark for "pull this away", and the sheet body above now
            // honours it — it sat here inert while the same bar on every LkSheet really did drag.
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(38.dp)
                    .height(4.dp)
                    .background(c.washStrong, LkShape.xs),
            )
            // Title & kind
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = attachment.filename,
                    style = LkText.title,
                    color = c.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.files_viewer_details_subtitle,
                        stringResource(fileKind.screenTitleRes),
                        attachment.sizeBytes.readableSize(),
                    ),
                    style = LkText.subtitle,
                    color = c.textMuted,
                )
            }
            // Metadata table
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lkSurface(
                        shape = LkShape.card,
                        color = c.wash,
                        ring = c.ring,
                    )
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                if (dimensions != null) {
                    MetadataRow(
                        label = stringResource(R.string.files_viewer_meta_dimensions),
                        value = dimensions,
                    )
                    MetadataDivider()
                }
                MetadataRow(
                    label = stringResource(R.string.files_viewer_meta_size),
                    value = attachment.sizeBytes.readableSize(),
                )
                MetadataDivider()
                MetadataRow(
                    label = stringResource(R.string.files_viewer_meta_cipher),
                    value = "AES-256-GCM",
                )
                if (!folderPath.isNullOrBlank()) {
                    MetadataDivider()
                    MetadataRow(
                        label = stringResource(R.string.files_viewer_meta_folder),
                        value = folderPath,
                    )
                }
            }
            // The user's own note, when there is one. Its own block rather than a MetadataRow:
            // every row in the table above is a short, single-line fact with a right-aligned
            // value, and prose in that shape either wraps into the label or gets truncated to
            // uselessness. Capped all the same — the sheet must not grow past the screen — and the
            // full text is one tap away in the editor.
            if (!notes.isNullOrBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .lkSurface(
                            shape = LkShape.card,
                            color = c.wash,
                            ring = c.ring,
                        )
                        // Horizontally flush with the metadata table above it, whose rows sit at
                        // the same 14dp inset.
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
                ) {
                    Text(
                        text = stringResource(R.string.files_viewer_meta_notes),
                        style = LkText.body,
                        color = c.textMuted,
                    )
                    Text(
                        text = notes,
                        style = LkText.body,
                        color = c.textPrimary,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Security note
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lkSurface(
                        shape = LkShape.md,
                        color = c.jade.copy(alpha = 0.07f),
                        ring = c.jade.copy(alpha = 0.18f),
                    )
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = LkIcons.ShieldCheck,
                    contentDescription = null,
                    tint = c.jade,
                    modifier = Modifier.size(16.dp).padding(top = 2.dp),
                )
                Text(
                    text = stringResource(R.string.files_viewer_sheet_security_note),
                    style = LkText.caption,
                    color = c.textSecondary,
                )
            }
            // Close button
            LkSecondaryButton(
                text = stringResource(R.string.files_close),
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    val c = LkTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = LkText.body,
            color = c.textMuted,
        )
        Text(
            text = value,
            style = LkText.monoCaption,
            color = c.textPrimary,
        )
    }
}

@Composable
private fun MetadataDivider() {
    val c = LkTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(c.ring.copy(alpha = 0.4f)),
    )
}

/** Restores a decrypted blob to the public gallery/Downloads — the ONE deliberate, user-initiated place
 *  plaintext leaves the vault onto shared storage (§5.5). Never automatic. Requires API 29+ (scoped
 *  storage `MediaStore` insert needs no `WRITE_EXTERNAL_STORAGE`; below that this feature is unavailable). */
object GalleryExporter {
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * [write] fills the MediaStore entry. A lambda rather than a `ByteArray` because the caller
     * that matters here is restoring a video: this used to take the whole decrypted blob, so a
     * 1 GB import — deliberately uncapped, because imports stream — came back out through a single
     * in-memory array and threw `OutOfMemoryError`, which `catch (e: Exception)` does not catch, so
     * the process died rather than reporting a failed restore.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun restore(context: Context, filename: String, mime: String, write: (OutputStream) -> Unit): Uri {
        check(isSupported()) { "Restore to gallery requires Android 10+" }
        val resolver = context.contentResolver
        val collection = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        return completePendingEntry(uri, discard = { resolver.delete(it, null, null) }) {
            resolver.openOutputStream(it)?.use(write) ?: error("cannot open output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(it, values, null, null)
        }
    }

    /**
     * Where the copy landed, phrased the way the user would go and look for it — `Pictures/receipt.jpg`.
     *
     * Never the `content://` URI. It is not an answer to "where did it go", and this app does not put
     * paths or URIs into user-facing text; the export confirmation used to read
     * `Restored to content://media/external/images/media/1000000161`, which names nothing the user
     * can act on.
     *
     * Read back out of MediaStore rather than assembled from what was asked for, because MediaStore
     * renames on collision: exporting `receipt.jpg` twice really does produce `receipt (1).jpg`, and
     * echoing the requested name would send the user to the wrong file. Falls back to the collection
     * the mime chose if the row cannot be read, which still beats a URI.
     */
    fun destinationOf(context: Context, uri: Uri, filename: String, mime: String): String {
        val fallback = when {
            mime.startsWith("image/") -> "Pictures"
            mime.startsWith("video/") -> "Movies"
            else -> "Downloads"
        } + "/" + filename
        val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val path = c.getString(0).orEmpty().trim('/')
                val name = c.getString(1).orEmpty()
                if (name.isEmpty()) null else if (path.isEmpty()) name else "$path/$name"
            }
        }.getOrNull() ?: fallback
    }
}

/**
 * Text and Office extraction both build Strings several times the file's size, in memory. A cap is
 * therefore the difference between "too big to show" and the process dying — and unlike a video,
 * text has no streaming path here to fall back on.
 */
private const val MAX_TEXT_BYTES = 4L * 1024 * 1024

/**
 * Cap for an Office document opened in the viewer, above [MAX_TEXT_BYTES] because a real .xlsx or
 * .pptx is routinely bigger than any plain-text file someone would read on a phone. It bounds the
 * single buffered `ByteArray` `decryptToBytes` returns, plus whatever `OfficeTextExtractor` builds
 * on top of it, on a path where import applies no size limit of its own.
 */
private const val MAX_OFFICE_BYTES = 32L * 1024 * 1024

/**
 * Whether a page is the "This file is empty." state rather than a viewer.
 *
 * A zero-byte file is empty whatever its type: left to the kind switch, a zero-byte `.bin` or `.pdf`
 * got "No preview for this file — no viewer for .bin files", which blames the format for what is
 * really a missing payload. A text file with nothing but whitespace is the same state, so it is not
 * drawn as the file's own (blank) text. [text] is null while the page has not decrypted yet.
 */
internal fun viewerShowsEmptyState(sizeBytes: Long, text: String?): Boolean =
    sizeBytes == 0L || (text != null && text.isBlank())

/**
 * "This file is empty." as a centred state, the way "No preview for this file" is drawn. It used to
 * be the fallback text of [PlainTextViewer], so it sat top-left in the monospaced face and read as
 * the file's own contents.
 */
@Composable
private fun EmptyFileState() {
    LkEmptyState(
        icon = LkIcons.Attachment,
        title = stringResource(R.string.files_viewer_empty_text),
        body = stringResource(R.string.files_viewer_empty_body),
    )
}

/** A text file, as written. Selectable so a line can be copied out; monospaced so a log or a CSV
 *  keeps its columns. */
@Composable
private fun PlainTextViewer(text: String) {
    val c = LkTheme.colors
    if (text.isBlank()) {
        EmptyFileState()
        return
    }
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text = text,
            style = LkText.monoCaption,
            color = c.textSecondary,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        )
    }
}

/**
 * A Word/Excel/PowerPoint file's text, labelled as exactly that.
 *
 * The note above the text is not decoration. This is an extraction from Office XML — the words are
 * real, the layout, tables, images and formatting are not reproduced — and a user comparing this
 * against the document on their laptop has to know that before they trust what they are reading.
 */
@Composable
private fun OfficeTextViewer(bytes: ByteArray) {
    val c = LkTheme.colors
    // Off the main thread since 2026-09-13. This was
    // `remember(bytes) { OfficeTextExtractor.extract(bytes) }`, which inflated and tag-stripped
    // every text part of the zip synchronously *during composition*. MAX_OFFICE_BYTES bounds the
    // compressed bytes, not the work they expand into, so a legitimate near-cap .xlsx stalls the
    // viewer for seconds and a crafted deck of many small, highly-compressible parts for far longer
    // — with no progress and no way out. The decrypt that produced these bytes has always been on
    // Dispatchers.IO for exactly this reason. Dispatchers.Default, not IO: this is CPU work.
    //
    // The skeleton below is the one the pager already shows while that decrypt runs, so the wait
    // reads as the same wait rather than as a new state. A null result still lands on the same empty
    // state it always did; `remember(bytes)` is keyed the same way, so a swipe away and back
    // re-runs the extraction exactly as it re-ran it before.
    var extracted by remember(bytes) { mutableStateOf<String?>(null) }
    var extracting by remember(bytes) { mutableStateOf(true) }
    LaunchedEffect(bytes) {
        extracted = withContext(Dispatchers.Default) {
            OfficeTextExtractor.extract(bytes) { !isActive }
        }
        extracting = false
    }
    if (extracting) {
        LkSkeleton(Modifier.fillMaxSize())
        return
    }
    val text = extracted
    if (text == null) {
        LkEmptyState(
            icon = LkIcons.Attachment,
            title = stringResource(R.string.files_viewer_no_text_title),
            body = stringResource(R.string.files_viewer_no_text_body),
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        LkInlineNote(stringResource(R.string.files_viewer_office_note))
        Spacer(Modifier.height(10.dp))
        PlainTextViewer(text)
    }
}
