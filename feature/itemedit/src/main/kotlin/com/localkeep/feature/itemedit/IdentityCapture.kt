package com.localkeep.feature.itemedit

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.localkeep.core.ui.PermissionDenial
import com.localkeep.core.ui.PermissionDenialSaver
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.decorative
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.openAppSettings
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkEncryptingScreen
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkInlineNote
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkSavedScreen
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextButton
import com.localkeep.core.ui.theme.LkTextField
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.lkPressScale
import com.localkeep.core.ui.theme.lkRing
import com.localkeep.core.ui.theme.lkScrollEdges
import com.localkeep.core.ui.theme.lkSurface
import com.localkeep.core.ui.theme.lkTween
import com.localkeep.core.vault.AttachmentQuota
import com.localkeep.feature.files.CameraPreview
import com.localkeep.feature.files.rememberCameraCaptureController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable as foundationClickable

// =================================================================================================
// Motion. The design specifies its own durations and its own curve, so this file uses them rather
// than `lkEnter`/`LkMotion.Standard`: `idRise` is 380ms on cubic-bezier(.16,1,.3,1), where lkEnter
// is 460ms on (.2,.8,.2,1). Close, and not the same — the design's curve settles harder, which is
// what makes the confirm buttons read as arriving rather than easing in. Everything here collapses
// to a single frame under reduced motion, via [lkTween].
// =================================================================================================

/**
 * cubic-bezier(.16,1,.3,1) — the design's one easing curve, now [LkMotion.Settle].
 *
 * It lived here as a private value until 2026-08-19, when `Item Flows.dc.html` turned out to use
 * the same curve for six more flows. Six private copies of one number is how a motion vocabulary
 * stops being one, so it moved to [LkMotion]. The alias stays so this file still reads as the
 * design document it transcribes.
 */
private val IdEase: Easing = LkMotion.Settle

/**
 * `idRise` / `idScreen` / `idFan`: fade up over [travel] after [delayMillis].
 *
 * [durationMillis] because the design uses 380ms for a rise and 460ms for the page-stack fan, and
 * the fan is slower on purpose — three cards landing at 380ms read as one card with a shadow.
 */
/**
 * The string ids that make up the identity-photo quota refusal, in order.
 *
 * The count sentence is true everywhere; its last clause is only true where a purchase exists.
 * `foss` has none (`Entitlement.canPurchase` is permanently false there), so naming one sends the
 * reader looking for a button that does not exist -- it says where Pro can be bought instead, as the
 * vault list's dialog does.
 */
internal fun identityPhotoQuotaStringIds(canPurchase: Boolean): List<Int> = listOf(
    R.string.itemedit_error_identity_photo_quota,
    if (canPurchase) {
        R.string.itemedit_error_identity_photo_quota_suffix_purchase
    } else {
        R.string.itemedit_error_identity_photo_quota_suffix_no_purchase
    },
)

/** Renders [identityPhotoQuotaStringIds]; both format arguments are read by the count sentence. */
@Composable
private fun identityPhotoQuotaText(mustRemove: Int, canPurchase: Boolean): String {
    // A loop rather than joinToString: stringResource is composable and joinToString's lambda is not
    // inline.
    val out = StringBuilder()
    for (id in identityPhotoQuotaStringIds(canPurchase)) {
        out.append(stringResource(id, AttachmentQuota.FREE_LIMIT, mustRemove))
    }
    return out.toString()
}

@Composable
private fun Modifier.idRise(
    delayMillis: Int = 0,
    travel: Dp = 14.dp,
    durationMillis: Int = IdRiseMillis,
): Modifier {
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = lkTween(durationMillis, delayMillis, IdEase),
        label = "id-rise",
    )
    val travelPx = with(LocalDensity.current) { travel.toPx() }
    return this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * travelPx
    }
}

/**
 * How a new Identity opens: photograph the document, or type it out.
 *
 * Identity is the type where the flat form is worst — fourteen fields, all of them copied by eye
 * off a piece of paper the user is holding, in front of a camera. "What if the person just wants to
 * take a picture instead of inputting manually" (principal, 2026-08-19).
 *
 * **New identities only**, the same rule [CardWizard] follows and for the same reason: someone
 * correcting a phone number must not be asked how they would like to begin.
 *
 * The visual treatment through this whole file is the `Identity Capture.dc.html` design, imported
 * 2026-08-19. Where a number here looks arbitrary it is that document's, and the four deliberately
 * off-scale values it names — the 1.585:1 document guide, the 80dp shutter, the 66dp route tiles
 * and the 64×40 rail slot — are called out at their own definitions.
 */
@Composable
internal fun IdentityStart(
    onPhoto: () -> Unit,
    onType: () -> Unit,
    onLeave: () -> Boolean,
    // Non-null means the photo route would be refused before the first frame ever lands — the
    // same "remove N" count `ItemEditViewModel.captureIdentityPhoto` computes, asked here instead
    // so the refusal shows on this chooser and the camera never opens on a full vault. The manual
    // route needs no attachment slot and is never gated by this.
    photoBlockedReason: Int? = null,
) {
    val c = LkTheme.colors
    BackHandler(enabled = true) { onLeave() }

    val scroll = rememberScrollState()
    LkScreen(accent = c.jade, auraAlpha = 0.07f, auraBias = 0.5f) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = LkFrame.horizontal)
                .padding(top = LkFrame.top, bottom = LkFrame.bottom),
            verticalArrangement = Arrangement.spacedBy(LkSpace.lg),
        ) {
            // The route choice scrolls; Cancel does not. This screen used to be a plain Column with
            // a `Spacer(weight(1f))` holding Cancel against the bottom, which works only while the
            // content above fits: at the largest font size on the narrowest supported window both
            // tiles grow to four lines each, the weight resolves to zero, and Cancel was pushed off
            // the bottom of a screen with nothing to scroll. Measured on a device — the button was
            // absent from the hierarchy entirely, not merely clipped.
            //
            // Cancel stays outside the scroller rather than inside it: the way out of a flow should
            // not be something the user has to go looking for. `heightIn(min = viewport)` keeps the
            // default layout identical, since at ordinary sizes the column still fills the box.
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .lkScrollEdges(
                        moreAbove = scroll.canScrollBackward,
                        moreBelow = scroll.canScrollForward,
                        color = c.ground,
                    ),
            ) {
                val viewport = maxHeight
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .heightIn(min = viewport),
                    verticalArrangement = Arrangement.spacedBy(LkSpace.lg),
                ) {
                    Box(
                        Modifier
                            .size(60.dp)
                            .idRise()
                            .lkSurface(LkShape.xxl, c.jade.copy(alpha = 0.13f), c.jade.copy(alpha = 0.24f))
                            .decorative(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(LkIcons.Identity, null, tint = c.jade, modifier = Modifier.size(27.dp))
                    }
                    Column(Modifier.idRise(delayMillis = 60)) {
                        Text(
                            stringResource(R.string.itemedit_identity_start_title),
                            // This file never calls LkScreenHeader (which supplies heading() on
                            // every other screen), yet reuses its title token (LkText.screenTitle)
                            // at all three of its own titles without the semantic -- so TalkBack's
                            // navigate-by-heading gesture had no stop anywhere in the identity
                            // capture flow. Same defect/fix as unlock (W9) and item detail (V20)
                            // (fix-I, 21-3, 2026-09-13).
                            modifier = Modifier.semantics { heading() },
                            style = LkText.screenTitle,
                            color = c.textStrong,
                        )
                        Spacer(Modifier.height(LkSpace.sm))
                        Text(
                            stringResource(R.string.itemedit_identity_start_body),
                            style = LkText.body,
                            color = c.textMuted,
                        )
                    }
                    Column(
                        Modifier.idRise(delayMillis = 130),
                        verticalArrangement = Arrangement.spacedBy(LkSpace.md),
                    ) {
                        IdentityRouteTile(
                            icon = LkIcons.Camera,
                            title = stringResource(R.string.itemedit_identity_start_photo),
                            subtitle = if (photoBlockedReason != null) {
                                stringResource(
                                    R.string.itemedit_identity_start_photo_blocked,
                                    AttachmentQuota.FREE_LIMIT,
                                    photoBlockedReason,
                                )
                            } else {
                                stringResource(R.string.itemedit_identity_start_photo_hint)
                            },
                            primary = photoBlockedReason == null,
                            enabled = photoBlockedReason == null,
                            onClick = onPhoto,
                        )
                        IdentityRouteTile(
                            icon = LkIcons.Plus,
                            title = stringResource(R.string.itemedit_identity_start_type),
                            primary = false,
                            onClick = onType,
                        )
                    }
                }
            }
            LkTextButton(
                text = stringResource(R.string.itemedit_identity_start_cancel),
                color = c.textMuted,
                onClick = { onLeave() },
            )
        }
    }
}

/**
 * One of the two ways in, drawn as a tile rather than a button.
 *
 * 66dp and two lines, which is off [LkSpace]'s rungs on purpose: this is the only screen in the app
 * that asks the user to pick a *route* rather than confirm an action, and the second line is what
 * makes the choice answerable without trying one. A pair of ordinary 54dp buttons reading
 * "Photograph the document" / "Enter the details by hand" says nothing about which is faster.
 */
@Composable
internal fun IdentityRouteTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    primary: Boolean,
    onClick: () -> Unit,
    // Null draws a one-line tile. Only the photo route has a second line to say: it is the one with
    // a reason to choose it ("Fastest") or a reason it cannot be chosen (a full vault). Typing the
    // details in used to advertise its own length ("Fourteen fields, all optional"), which argued
    // against the route it labelled.
    subtitle: String? = null,
    // False draws the tile as refused rather than merely secondary — dimmed and unclickable, the
    // subtitle carrying the reason instead of the ordinary hint. See `IdentityStart`'s
    // `photoBlockedReason`: a full vault must say so here, before the shutter, not after it.
    enabled: Boolean = true,
) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val base = Modifier
        .fillMaxWidth()
        // A minimum, not a height. The tile stacks a title over a subtitle, and at the largest font
        // size the two line boxes plus their gap already fill 66dp with the subtitle held to one
        // line — so a subtitle that wraps ("Fastest — nothing to
        // type") was clipped against the tile edge, at the moment that line is what tells the user
        // which route to take.
        .heightIn(min = 66.dp)
        .lkPressScale(interaction, pressedScale = 0.985f)
    Row(
        modifier = (
            if (primary) {
                base.background(c.jadeFill, LkShape.xl)
            } else {
                base.lkSurface(LkShape.xl, c.field, c.ringStrong)
            }
            )
            .foundationClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .minTouchTarget()
            // Vertical air as well, so a tile that outgrows its 66dp floor at large text keeps the
            // text off the ring (SCL-030). Inert at ordinary sizes: two lines plus this stay under
            // 66dp, so the floor still decides the height there.
            .padding(horizontal = 20.dp, vertical = LkSpace.sm)
            .semanticsTile(title, subtitle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LkSpace.md),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (!enabled) c.textFaint else if (primary) c.onJade else c.textMuted,
            modifier = Modifier.size(22.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.xs)) {
            Text(
                title,
                style = LkText.button,
                color = if (!enabled) c.textFaint else if (primary) c.onJade else c.textPrimary,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = LkText.subtitle,
                    color = if (!enabled) {
                        c.textFaint
                    } else if (primary) {
                        c.onJade.copy(alpha = 0.72f)
                    } else {
                        c.textFaint
                    },
                )
            }
        }
    }
}

/**
 * One node, so a screen reader reads the route and its hint together rather than as two rows.
 *
 * Assembled with `buildString` rather than a template, matching `AddTypeRow` — the one other place
 * in the app that merges a label and its description into a single announcement.
 */
private fun Modifier.semanticsTile(title: String, subtitle: String?): Modifier =
    semantics(mergeDescendants = true) {
        contentDescription = buildString {
            append(title)
            if (subtitle != null) {
                append(". ")
                append(subtitle)
            }
        }
    }

/**
 * Photograph the document, front then back, then name it. That is the entire flow.
 *
 * The shape is the principal's, given 2026-08-19 after the first attempt made him tap "photograph"
 * three times before a shutter appeared: *"a nice popup live camera saying Photograph the front, it
 * asks you if you want a retake or continue, then photograph the back, then if needed one more
 * picture, then optionally save identity Title and that's it no more no less"*.
 *
 * Two rules fall out of that and are worth stating because they are what the first attempt broke:
 *
 * - **The camera is the first thing, not a destination.** Choosing "photograph" opens a viewfinder.
 *   Nothing is typed, saved or navigated first. That is possible because a blob needs no item —
 *   see `ItemEditViewModel.captureIdentityPhoto`.
 * - **Only the first page is required.** Back and third page are offered and skippable, so the
 *   common case — one side of one card — is shutter, Continue, Save.
 *
 * The title step prefills a default rather than validating an empty field. `save()` refuses a blank
 * title, and rightly so, but that refusal must never be something this flow can walk the user into:
 * they came to take a photograph, and naming it is the optional part.
 *
 * [onSavedDone] rather than the editor's ordinary `onSaved`: this route holds the user on a
 * confirmation screen after the write, so the navigation must happen when *they* leave it, not when
 * the ViewModel reports success. `ItemEditScreen` suppresses its own auto-navigation for this route
 * for the same reason.
 */
@Composable
internal fun IdentityCaptureFlow(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    onLeave: () -> Boolean,
    onSavedDone: () -> Unit,
    /**
     * Switches this new identity to the type-it-in route.
     *
     * Only the camera-denied state uses it, and it exists because that state used to be a dead end:
     * declining the permission replaced the chooser — title, subtitle and *both* routes — with two
     * notices, "Allow camera access" and "Cancel". The user who says no to the camera is exactly the
     * user this app is built for, and the route that needs no camera had just disappeared from the
     * screen they were standing on; the only way to it was Cancel, which leaves the editor, then
     * Add > Identity again.
     */
    onEnterByHand: () -> Unit,
) {
    val c = LkTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduce = LocalLkReduceMotion.current

    // Which page we are on: 0 front, 1 back, 2 extra, 3 name-and-save.
    var page by rememberSaveable(state.itemId) { mutableIntStateOf(0) }
    // A frame has been taken for this page and is waiting on Retake/Continue.
    var awaitingConfirm by rememberSaveable(state.itemId) { mutableStateOf(false) }
    var captureFailed by rememberSaveable(state.itemId) { mutableStateOf(false) }

    var granted by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var showDisclosure by rememberSaveable { mutableStateOf(!granted) }
    var denial by rememberSaveable(stateSaver = PermissionDenialSaver) {
        mutableStateOf<PermissionDenial?>(null)
    }
    val deniedSentence = stringResource(R.string.itemedit_identity_capture_denied)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        denial = if (ok) {
            null
        } else {
            PermissionDenial.after(context, Manifest.permission.CAMERA, deniedSentence)
        }
    }

    // Back walks the flow rather than abandoning it, exactly as CardWizard's does — and for the
    // same reason: without this the editor's unconditional handler offers to discard everything,
    // so one gesture from the naming step throws away three photographs.
    BackHandler(enabled = !state.isSaving && !state.saved) {
        when {
            awaitingConfirm -> { viewModel.retakeLastIdentityPhoto(); awaitingConfirm = false }
            page > 0 -> {
                // A refusal belongs to the page it was raised on. Walking back to a page that is
                // already stored with the "to store this page" line still up told the user the
                // page they were looking at had been refused.
                captureFailed = false
                viewModel.clearIdentityPhotoFailure()
                page--
            }
            else -> onLeave()
        }
    }

    // The write, and then the confirmation. Both are screens of their own in the design because
    // sealing several full-resolution photographs into the vault file is the one save in this app
    // long enough to look like nothing happening.
    if (state.saved) {
        IdentitySaved(title = state.title, pages = state.identityPhotos.size, onDone = onSavedDone)
        return
    }
    if (state.isSaving) {
        IdentitySaving()
        return
    }
    // Thumbnails for the rail and the page stack. Plain `remember`, never saveable: these are
    // decoration, and the real state — which pages exist — comes from the ViewModel, so a rotation
    // costs the pictures and nothing else. Declared above the early returns because the naming
    // step shows the same set fanned out.
    val thumbnails = remember(state.itemId) { mutableStateListOf<Bitmap>() }

    if (page >= PAGE_NAME) {
        IdentityCaptureNaming(state, viewModel, thumbnails, onBack = { page = PAGE_EXTRA })
        return
    }

    var lastFrame by remember { mutableStateOf<Bitmap?>(null) }

    // Armed at the tap, not at the write. `state.identityPhotoSaving` only flips once the first
    // capture's callback has already returned and handed its bytes to the ViewModel, so between the
    // tap and that callback the shutter was still enabled and a second tap reached
    // `CameraCaptureController.capture()`, which has no in-flight guard of its own. Whichever of
    // the pair then lost `captureIdentityPhoto`'s own `identityPhotoSaving` check got `onDone(false)`
    // — raising "that picture did not save" and bouncing back to the shutter over a photograph that
    // had in fact saved. The comment in the ViewModel already names the double tap; it resolved the
    // callback so the rail could not desync, and stopped short of preventing the second tap.
    var captureInFlight by remember { mutableStateOf(false) }

    // `awaitingConfirm` is `rememberSaveable` and `lastFrame` cannot be — a full-resolution still
    // has no business in a Bundle — so a rotation between the shutter and Retake/Continue came back
    // with the confirm controls up and nothing to judge, drawn over the live viewfinder: exactly the
    // question the confirm step exists to avoid asking (see the preview block below). Resolved as a
    // Retake rather than shown blind, so `identityPhotos` and the rail stay in step with `page`.
    // Skipped while a write is in flight: that page has not been appended yet, and a rollback here
    // would drop the previous one instead.
    // Keyed on the write rather than on `Unit`: a rotation landing *during* the encrypt found
    // `identityPhotoSaving` still true, skipped the rollback, and — firing once — never got another
    // chance. The confirm controls then sat over a live viewfinder with nothing to judge, for good.
    // Re-evaluating when the write finishes is what closes that window.
    LaunchedEffect(state.identityPhotoSaving) {
        if (awaitingConfirm && lastFrame == null && !state.identityPhotoSaving) {
            viewModel.retakeLastIdentityPhoto()
            awaitingConfirm = false
        }
    }

    // Measured, not computed: the guide's hole in the scrim and the flight's landing slot both
    // need real coordinates, and the guide is laid out by the Column below rather than placed at a
    // fixed offset — so it stays a 1.585:1 card on every screen instead of only on a 392dp one.
    var guideBounds by remember { mutableStateOf<Rect?>(null) }
    var stillBounds by remember { mutableStateOf<Rect?>(null) }
    val slotBounds = remember { mutableStateListOf<Rect?>(null, null, null) }
    val flight = remember { Animatable(0f) }
    val flashAlpha = remember { Animatable(0f) }
    val flightSpec = lkTween<Float>(520)
    val ringProgress by animateFloatAsState(
        targetValue = if (awaitingConfirm) 1f else 0f,
        animationSpec = lkTween(620, easing = LkMotion.Standard),
        label = "capture-ring",
    )

    // Where the heading ends, in this Box's own coordinates. The frozen picture is laid out below it
    // rather than centred on the whole screen: Fit-centring put a portrait document's top edge
    // through the middle of "Photograph the front", half the heading on black and half on grey.
    // Measured, because the heading wraps in other languages and at larger font scales.
    var screenTop by remember { mutableFloatStateOf(0f) }
    var headingBottom by remember { mutableFloatStateOf(0f) }
    LkScreen(accent = c.jade, auraAlpha = 0.07f, auraBias = 0.5f) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { screenTop = it.positionInRoot().y }) {
            if (granted) {
                val controller = rememberCameraCaptureController()
                CameraPreview(controller, Modifier.fillMaxSize())

                // The frame that was just taken, over the live preview. Asking "retake or continue"
                // while showing the viewfinder asks the user to judge a picture they cannot see —
                // the one question the confirm step exists to answer.
                lastFrame?.takeIf { awaitingConfirm }?.let { bmp ->
                    val target = slotBounds.getOrNull(page)
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(
                            R.string.itemedit_identity_capture_preview_a11y,
                        ),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { if (flight.value == 0f) stillBounds = it.boundsInRoot() }
                            .graphicsLayer {
                                val from = stillBounds
                                val to = target
                                val t = flight.value
                                if (t > 0f && from != null && to != null) {
                                    transformOrigin = TransformOrigin.Center
                                    val sx = (to.width / from.width).coerceAtLeast(0.05f)
                                    val sy = (to.height / from.height).coerceAtLeast(0.05f)
                                    translationX = (to.center.x - from.center.x) * t
                                    translationY = (to.center.y - from.center.y) * t
                                    scaleX = 1f + (sx - 1f) * t
                                    scaleY = 1f + (sy - 1f) * t
                                    alpha = 1f - 0.75f * t
                                }
                            }
                            .background(c.ground)
                            // After the background, so the opaque plate still covers the whole
                            // screen (the live preview must not show above the picture) while the
                            // picture itself starts under the heading.
                            .padding(
                                top = with(LocalDensity.current) {
                                    (headingBottom - screenTop).coerceAtLeast(0f).toDp()
                                } + LkSpace.sm,
                            ),
                    )
                }

                DocumentGuideScrim(guideBounds)

                // The shutter's flash. Skipped outright when motion is reduced rather than
                // shortened: a full-screen white frame is the single most aggressive thing this app
                // draws, and "briefly" is not an improvement for anyone who asked for less of it.
                if (flashAlpha.value > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = flashAlpha.value }
                            .background(Color.White)
                            .decorative(),
                    )
                }

                // The bottom scrim, so the controls stay legible over a bright document.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(230.dp)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.46f to c.ground.copy(alpha = 0.82f),
                                1f to c.ground.copy(alpha = 0.94f),
                            ),
                        )
                        .decorative(),
                )

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(top = LkFrame.top, bottom = LkFrame.bottom),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PageRail(
                        captured = state.identityPhotos.size,
                        active = page,
                        thumbnails = thumbnails,
                        onSlotBounds = { index, rect -> slotBounds[index] = rect },
                    )
                    Spacer(Modifier.height(LkSpace.lg))

                    val prompt = stringResource(
                        when (page) {
                            PAGE_FRONT -> R.string.itemedit_identity_capture_front
                            PAGE_BACK -> R.string.itemedit_identity_capture_back
                            else -> R.string.itemedit_identity_capture_more
                        },
                    )
                    Text(
                        prompt,
                        style = LkText.screenTitle,
                        color = c.textStrong,
                        textAlign = TextAlign.Center,
                        // heading() alongside announce()'s own semantics block -- see the 21-3 note
                        // on the identity start title above (fix-I, 2026-09-13): this file never
                        // uses LkScreenHeader.
                        modifier = Modifier
                            .padding(horizontal = LkFrame.horizontal)
                            .onGloballyPositioned { headingBottom = it.boundsInRoot().bottom }
                            .semantics { heading() }
                            .announce(prompt),
                    )
                    if (!awaitingConfirm) {
                        Spacer(Modifier.height(LkSpace.sm))
                        FramingHint(page)
                    }

                    Spacer(Modifier.weight(1f))
                    DocumentGuide(
                        sweeping = !awaitingConfirm,
                        ringProgress = ringProgress,
                        onBounds = { guideBounds = it },
                    )
                    Spacer(Modifier.weight(1f))

                    if (awaitingConfirm) {
                        CaptureNote(
                            modifier = Modifier
                                .padding(horizontal = LkFrame.horizontal)
                                .idRise(delayMillis = 240),
                        )
                        Spacer(Modifier.height(LkSpace.lg))
                    }
                    // `state.identityPhotoFailed` as well as the local flag: the local one is
                    // written by a callback closing over the composition that started the capture,
                    // so a failure resolving after a rotation lands in an object nothing renders.
                    if (captureFailed || state.identityPhotoFailed) {
                        // Two different refusals, and they must not wear the same sentence. "That
                        // picture did not save. Try again." is right for a strip or encrypt that
                        // fell over and wrong for a full quota, where trying again cannot work and
                        // the user needs the cause and the way out instead. Same shape and the
                        // same two arguments the file editor's quota line uses.
                        val mustRemove = state.identityPhotoQuotaMustRemove
                        val message = if (mustRemove != null) {
                            identityPhotoQuotaText(mustRemove, state.canPurchase)
                        } else {
                            stringResource(R.string.itemedit_identity_capture_failed)
                        }
                        LkInlineError(
                            message,
                            Modifier.padding(horizontal = LkFrame.horizontal).announce(message),
                        )
                        Spacer(Modifier.height(LkSpace.md))
                    }

                    if (awaitingConfirm) {
                        // Both weighted, and the Row bounded. LkSecondaryButton fills its width by
                        // default, so without this the first child took the whole row and Continue
                        // was laid out off the right edge — present in the tree, unreachable on the
                        // screen (measured on emulator-5554, 2026-08-19).
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = LkFrame.horizontal)
                                .idRise(delayMillis = 120),
                            horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
                        ) {
                            LkSecondaryButton(
                                text = stringResource(R.string.itemedit_identity_capture_retake),
                                modifier = Modifier.weight(1f),
                                height = 52.dp,
                                onClick = {
                                    // A retake is a correction, not a transition: no travel out,
                                    // the still simply drops and the sweep resumes.
                                    viewModel.retakeLastIdentityPhoto()
                                    thumbnails.removeLastOrNull()
                                    lastFrame = null
                                    awaitingConfirm = false
                                    scope.launch { flight.snapTo(0f) }
                                },
                            )
                            LkPrimaryButton(
                                text = stringResource(R.string.itemedit_identity_capture_continue),
                                modifier = Modifier.weight(1f),
                                enabled = !state.identityPhotoSaving,
                                onClick = {
                                    val advance = {
                                        lastFrame = null
                                        awaitingConfirm = false
                                        captureFailed = false
                                        viewModel.clearIdentityPhotoFailure()
                                        page++
                                    }
                                    if (reduce) {
                                        advance()
                                    } else {
                                        scope.launch {
                                            flight.snapTo(0f)
                                            flight.animateTo(1f, flightSpec)
                                            advance()
                                            flight.snapTo(0f)
                                        }
                                    }
                                },
                            )
                        }
                    } else {
                        ShutterButton(
                            enabled = controller.isReady &&
                                !captureInFlight &&
                                !state.identityPhotoSaving,
                            onClick = {
                                captureFailed = false
                                viewModel.clearIdentityPhotoFailure()
                                captureInFlight = true
                                if (!reduce) {
                                    scope.launch {
                                        flashAlpha.snapTo(0.92f)
                                        flashAlpha.animateTo(0f, tween(200, easing = LinearEasing))
                                    }
                                }
                                controller.capture { bytes ->
                                    // Disarmed once the camera has answered: the tap is spent
                                    // whichever way it answered, and a failure leaves the shutter
                                    // on screen for the retry. For a picture that is after the
                                    // decode below, so the shutter cannot fire a second time while
                                    // the first frame is still being decoded.
                                    if (bytes == null) {
                                        captureInFlight = false
                                        captureFailed = true
                                    } else {
                                        scope.launch {
                                            // Decoded off the main thread (2026-10-02): a full 12 MP
                                            // decode plus the rail thumbnail froze the viewfinder for
                                            // about a second on every shot. Everything after it stays
                                            // in this order so a fast failure below still removes the
                                            // thumbnail this shot added, not the previous one.
                                            val frame = decodeCapturedFrame(bytes)
                                            captureInFlight = false
                                            lastFrame = frame?.first
                                            frame?.let { thumbnails.add(it.second) }
                                            // Motion spec: "frame freezes at 110 ms". The freeze is
                                            // tied to having a frame, not to having encrypted it —
                                            // stripping and chunk-encrypting a 12 MP still takes as
                                            // long as it takes, and gating the confirm step on it made
                                            // the shutter feel like it had missed. Continue is
                                            // disabled while the write is in flight instead, so the
                                            // picture is always in the vault before the page advances.
                                            awaitingConfirm = true
                                            viewModel.captureIdentityPhoto(bytes) { ok ->
                                                if (!ok) {
                                                    captureFailed = true
                                                    awaitingConfirm = false
                                                    if (frame != null) thumbnails.removeLastOrNull()
                                                    lastFrame = null
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                        )
                        Spacer(Modifier.height(LkSpace.lg))
                        // Only the front is required. Past it, moving on is always one tap away and
                        // says so — "if needed one more picture" means the user decides, not the
                        // flow.
                        LkTextButton(
                            text = stringResource(
                                if (page == PAGE_FRONT) {
                                    R.string.itemedit_identity_capture_cancel
                                } else {
                                    R.string.itemedit_identity_capture_done
                                },
                            ),
                            color = c.textMuted,
                            modifier = Modifier.padding(horizontal = LkFrame.horizontal),
                            onClick = {
                                if (page == PAGE_FRONT) {
                                    onLeave()
                                } else {
                                    captureFailed = false
                                    viewModel.clearIdentityPhotoFailure()
                                    page = PAGE_NAME
                                }
                            },
                        )
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = LkFrame.horizontal)
                        .padding(top = LkFrame.top, bottom = LkFrame.bottom),
                    verticalArrangement = Arrangement.spacedBy(LkSpace.md),
                ) {
                    // The pre-permission note stops once a denial is on screen: the denial says the
                    // same thing with the reason and the way out, and the two stacked read as one
                    // sentence repeated.
                    if (denial == null) {
                        LkInlineNote(
                            stringResource(R.string.itemedit_identity_capture_needs_camera),
                            icon = LkIcons.Info,
                        )
                    }
                    denial?.let { d ->
                        LkInlineError(
                            message = d.message,
                            modifier = Modifier.announce(d.message),
                            actionLabel = d.settingsActionLabel,
                            onAction = d.settingsActionLabel?.let { { context.openAppSettings() } },
                        )
                        // Only for the denial Android will still ask about — where
                        // `settingsActionLabel` is null, the message is
                        // `core_ui_permission_denial_retry` and it ends "You can tap it again to
                        // allow access." `LkInlineError`'s own action slot is documented as being
                        // for errors that leave the user with nothing to press, on the assumption
                        // that the control which failed is still on screen: true of the two files
                        // flows, whose camera buttons survive, and false here, where the denial
                        // replaces the whole viewfinder with this column. So the copy named an
                        // action the screen did not offer, and the only way back to the system
                        // dialog was to leave the editor entirely and re-enter Add > Identity >
                        // photograph. Re-checks the permission first, because it may have been
                        // granted in Settings since.
                        if (d.settingsActionLabel == null) {
                            LkPrimaryButton(
                                text = stringResource(R.string.itemedit_identity_capture_allow),
                                icon = LkIcons.Camera,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.CAMERA,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!granted) showDisclosure = true
                                },
                            )
                        }
                    }
                    // The camera is not the only way to make an identity, and this is the screen
                    // that had stopped saying so. Secondary, under "Allow camera access": the user
                    // asked for the photo route, so retrying it stays the louder offer.
                    LkSecondaryButton(
                        text = stringResource(R.string.itemedit_identity_start_type),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onEnterByHand,
                    )
                    Spacer(Modifier.weight(1f))
                    LkTextButton(
                        text = stringResource(R.string.itemedit_identity_capture_cancel),
                        color = c.textMuted,
                        onClick = { onLeave() },
                    )
                }
            }
        }
    }

    if (showDisclosure) {
        // A dialog, the same component as the 2FA scan's and the location capture's: the three
        // camera and location consents are the same question and read as one thing only if they
        // are one thing. This one was a bottom sheet with ~10dp gutters beside the other two's
        // centred card.
        // `showDisclosure` is cleared only when the editor is NOT leaving. When it is, the pop
        // takes this dialog down with the screen — clearing the flag first would tear its window
        // down a beat before the crossing, and the camera screen behind it would be redrawn
        // undimmed in between. See `leave` in ItemEditScreen, and checkNoDismissThenNavigate.
        LkDialog(
            onDismiss = { if (!onLeave()) showDisclosure = false },
            title = stringResource(R.string.itemedit_identity_capture_dialog_title),
            body = stringResource(R.string.itemedit_identity_capture_dialog_body),
            icon = LkIcons.Camera,
            confirm = LkDialogAction(
                label = stringResource(R.string.itemedit_identity_capture_dialog_continue),
                onClick = {
                    showDisclosure = false
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
            ),
            dismissLabel = stringResource(R.string.itemedit_dismiss_not_now),
        )
    }
}

/**
 * Front / Back / More, visible from the first frame.
 *
 * This replaces a page counter, and the difference is the point: "one more if needed" is legible
 * before it is asked, so nobody photographs the front of a passport wondering how long this goes on
 * for. A completed page carries its own thumbnail, so the rail shows what the flow is holding
 * rather than only how much of it there is.
 *
 * 64×40 is the ID-1 card ratio at thumbnail size — off the spacing scale because it is a picture of
 * a document, not a container.
 */
@Composable
private fun PageRail(
    captured: Int,
    active: Int,
    thumbnails: List<Bitmap>,
    onSlotBounds: (Int, Rect) -> Unit,
) {
    val c = LkTheme.colors
    val labels = listOf(
        stringResource(R.string.itemedit_identity_capture_rail_front),
        stringResource(R.string.itemedit_identity_capture_rail_back),
        stringResource(R.string.itemedit_identity_capture_rail_more),
    )
    val capturedSuffix = stringResource(R.string.itemedit_identity_capture_rail_captured)
    val infinite = rememberInfiniteTransition(label = "rail-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(IdPulseMillis, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "rail-pulse",
    )
    val reduce = LocalLkReduceMotion.current

    Row(horizontalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        repeat(RAIL_SLOTS) { index ->
            val done = index < captured
            val isActive = index == active && !done
            Column(
                modifier = Modifier
                    .width(74.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (done) labels[index] + capturedSuffix else labels[index]
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
            ) {
                Box(
                    Modifier
                        .size(width = 64.dp, height = 40.dp)
                        .onGloballyPositioned { onSlotBounds(index, it.boundsInRoot()) }
                        .background(
                            if (done) c.jade.copy(alpha = 0.10f) else c.ground.copy(alpha = 0.55f),
                            LkShape.sm,
                        )
                        .lkRing(
                            LkShape.sm,
                            when {
                                done -> c.jade.copy(alpha = 0.55f)
                                isActive -> c.jade.copy(alpha = 0.42f)
                                else -> c.ringStrong
                            },
                            1.5.dp,
                        ),
                ) {
                    if (done) {
                        val thumb = thumbnails.getOrNull(index)
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(5.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(c.cardFill),
                        ) {
                            thumb?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp)
                                .size(17.dp)
                                .background(c.jade, androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                LkIcons.Check,
                                contentDescription = null,
                                tint = c.onJade,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    } else if (isActive) {
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .size(7.dp)
                                // idPulse: opacity .35→1 and scale 1→1.35 on the same 1.8s cycle.
                                // Continuous, and allowed to be: the rule bans looping motion
                                // that only asks for attention, and this is the `isActive`
                                // branch — it says "this is the step the camera is on right
                                // now", which is in-progress state, the same licence the
                                // loading indicators have. It stops the moment the step is
                                // done (the `Check` branch above) and never runs under reduced
                                // motion. Written down because `guide-sweep` justified itself
                                // and these two did not (V29, 2026-09-12).
                                .graphicsLayer {
                                    if (reduce) return@graphicsLayer
                                    alpha = 0.35f + 0.65f * pulse
                                    val s = 1f + 0.35f * pulse
                                    scaleX = s
                                    scaleY = s
                                }
                                .background(c.jade, androidx.compose.foundation.shape.CircleShape),
                        )
                    }
                }
                Text(
                    labels[index],
                    style = LkText.fieldLabel,
                    // On an opaque ground plate, not on the picture: once the live camera fills the
                    // screen a bare caption took whatever the document was - a light page measured
                    // 1.35:1 for the pending ones. Against the ground itself `textMuted` is ~6.5:1
                    // and `jade`/`jadeLit` clear it by more, so the plate is what guarantees the
                    // ratio rather than the preview.
                    modifier = Modifier
                        .background(c.ground, LkShape.sm)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    color = when {
                        done -> c.jadeLit
                        isActive -> c.jade
                        else -> c.textMuted
                    },
                )
            }
        }
    }
}

/**
 * "Fill the frame · hold steady", and then out of the way.
 *
 * The one instruction worth giving, given once. It leaves on its own after a few seconds because
 * the person reading it is holding a phone over a document with both hands, and a permanent hint is
 * a permanent obstruction of the thing they are trying to see.
 */
@Composable
private fun FramingHint(page: Int) {
    val c = LkTheme.colors
    val reduce = LocalLkReduceMotion.current
    var visible by remember(page) { mutableStateOf(true) }
    LaunchedEffect(page) {
        delay(HintDwellMillis)
        visible = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = lkTween(if (reduce) 0 else 1_800, easing = LinearEasing),
        label = "hint-fade",
    )
    if (alpha <= 0.01f) return
    Row(
        modifier = Modifier
            .graphicsLayer { this.alpha = alpha }
            // A minimum, not a height. This pill floats over the live camera preview, so a wrapped
            // second line does not spill onto a neutral surface — it spills onto the frame the user
            // is trying to line their document up in. "Fill the frame · hold steady" stops fitting
            // one line once the font grows in the narrowest supported window.
            .heightIn(min = 28.dp)
            .background(c.ground.copy(alpha = 0.55f), LkShape.sm)
            .lkRing(LkShape.sm, c.ring)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LkSpace.xs),
    ) {
        Box(
            Modifier
                .size(5.dp)
                .background(c.jade, androidx.compose.foundation.shape.CircleShape)
                .decorative(),
        )
        Text(
            stringResource(R.string.itemedit_identity_capture_hint),
            style = LkText.chip,
            color = c.textSecondary,
        )
    }
}

/**
 * The document-shaped frame the user aims at.
 *
 * 1.585:1 is the ID-1 ratio every passport card, driving licence and national ID in the world is
 * cut to, and it is what makes this a guide rather than a decoration: it tells someone how close to
 * hold the phone, which a full-screen preview cannot. Everything else — the corner brackets, the
 * thirds, the sweep — hangs off that rectangle.
 *
 * The rectangle is published upward via [onBounds] rather than assumed, because the scrim that
 * masks around it and the flight that lands in the rail both need it in root coordinates.
 */
@Composable
private fun DocumentGuide(
    sweeping: Boolean,
    ringProgress: Float,
    onBounds: (Rect) -> Unit,
) {
    val c = LkTheme.colors
    val reduce = LocalLkReduceMotion.current
    val infinite = rememberInfiniteTransition(label = "guide")
    // Continuous for the same reason `rail-pulse` is: the document guide only breathes while
    // the camera is live and the user is being asked to line a document up inside it, so the
    // motion expresses an in-progress capture rather than asking to be looked at. Reduced
    // motion drops it (V29, 2026-09-12).
    // One linear phase for all four corners, read at four offsets — the design staggers them by
    // 200ms each, and four independent Reverse animations would drift apart instead of holding the
    // offset. Triangle-waved back into the .62→1 opacity the CSS specifies.
    val breathePhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(IdBreatheMillis, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "guide-breathe",
    )
    val sweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_700, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "guide-sweep",
    )
    val stagger = IdBreatheStaggerMillis.toFloat() / IdBreatheMillis
    fun bracketAlpha(corner: Int): Float {
        if (reduce) return 1f
        val phase = (breathePhase + corner * stagger) % 1f
        val triangle = 1f - kotlin.math.abs(2f * phase - 1f)
        return 0.62f + 0.38f * triangle
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = LkFrame.horizontal)
            .aspectRatio(DocumentAspect)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .decorative(),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = CornerRadius(GuideCornerRadiusDp.dp.toPx())
            val bracket = 34.dp.toPx()
            val stroke = 3.dp.toPx()
            val hairline = 1.dp.toPx()

            // Rule-of-thirds, which is what stops a document being photographed at an angle.
            for (f in listOf(1f / 3f, 2f / 3f)) {
                drawLine(c.textStrong.copy(alpha = 0.05f), Offset(size.width * f, 0f), Offset(size.width * f, size.height), hairline)
                drawLine(c.textStrong.copy(alpha = 0.05f), Offset(0f, size.height * f), Offset(size.width, size.height * f), hairline)
            }

            // Corner brackets, drawn as clipped arcs of the rounded rect so they follow its radius.
            val corners = listOf(
                Rect(Offset.Zero, Size(bracket, bracket)),
                Rect(Offset(size.width - bracket, 0f), Size(bracket, bracket)),
                Rect(Offset(0f, size.height - bracket), Size(bracket, bracket)),
                Rect(Offset(size.width - bracket, size.height - bracket), Size(bracket, bracket)),
            )
            val outline = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        Rect(Offset.Zero, size),
                        radius,
                        radius,
                        radius,
                        radius,
                    ),
                )
            }
            corners.forEachIndexed { index, corner ->
                clipRect(corner.left, corner.top, corner.right, corner.bottom) {
                    drawPath(outline, c.jade.copy(alpha = bracketAlpha(index)), style = Stroke(stroke))
                }
            }

            // The scan sweep: a band of light travelling the frame once every 2.7s. A loading
            // indicator in the literal sense — the camera is looking — and the only continuous
            // motion this app allows itself outside one.
            if (sweeping && !reduce) {
                val bandHeight = 56.dp.toPx()
                val y = -bandHeight * 0.1f + (size.height + bandHeight * 0.1f) * sweep
                val fade = when {
                    sweep < 0.12f -> sweep / 0.12f
                    sweep > 0.88f -> (1f - sweep) / 0.12f
                    else -> 1f
                }
                clipRect(0f, 0f, size.width, size.height) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to c.jade.copy(alpha = 0.16f * fade),
                            1f to Color.Transparent,
                            startY = y - bandHeight,
                            endY = y,
                        ),
                        topLeft = Offset(0f, y - bandHeight),
                        size = Size(size.width, bandHeight),
                    )
                    drawLine(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.5f to c.jadeLit.copy(alpha = fade),
                            1f to Color.Transparent,
                        ),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }

            // The capture ring, drawn around the guide the instant a frame is frozen. Progressive
            // rather than a fade so it reads as the camera closing on what it just took.
            if (ringProgress > 0f) {
                val measure = PathMeasure().apply { setPath(outline, false) }
                val segment = Path()
                measure.getSegment(0f, measure.length * ringProgress, segment, true)
                drawPath(segment, c.jadeLit, style = Stroke(stroke))
            }
        }
    }
}

/**
 * Everything outside the guide, dimmed.
 *
 * `BlendMode.Clear` into an offscreen layer, which is the only way to punch a rounded hole in a
 * full-screen fill without drawing four rectangles and getting the corners wrong. Null bounds draw
 * nothing at all rather than a full-screen dim — one frame of a blacked-out viewfinder before the
 * guide has been measured is worse than one frame of an unmasked one.
 */
@Composable
private fun DocumentGuideScrim(bounds: Rect?) {
    val c = LkTheme.colors
    if (bounds == null) return
    val radiusPx = with(LocalDensity.current) { GuideCornerRadiusDp.dp.toPx() }
    // The hole has to be punched in *this canvas's* coordinates, and [bounds] arrives in the root's.
    //
    // Those are not the same space, and the difference is exactly the inset above this Box — the
    // status bar, which `LkScreen` applies at the root. Drawing a root-space rectangle inside an
    // inset canvas puts it that far too low, so the cut-out sat below the corner brackets it is
    // supposed to be the inside of. Reported 2026-08-20: "the rectangle is not in line with the
    // white transparent rectangle box — the light box is a bit below the down part of the
    // rectangle".
    //
    // Measuring this canvas's own root offset and subtracting it makes the two spaces agree
    // whatever the insets turn out to be, which matters because the offending value is a device
    // measurement — a phone with a taller status bar or a displayCutout would have been off by more
    // than the emulator ever showed. Null until first placement, and the scrim draws nothing at all
    // until then, which is the same rule the null-bounds case already follows: one unmasked frame is
    // better than one misaligned one.
    var origin by remember { mutableStateOf<Offset?>(null) }
    Canvas(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .decorative(),
    ) {
        val here = origin ?: return@Canvas
        drawRect(c.scrim.copy(alpha = 0.58f))
        drawRoundRect(
            color = Color.Transparent,
            topLeft = bounds.topLeft - here,
            size = bounds.size,
            cornerRadius = CornerRadius(radiusPx),
            blendMode = BlendMode.Clear,
        )
    }
}

/**
 * The shutter — a camera control, not a button, and drawn as one.
 *
 * 80dp and circular is off every scale in this app on purpose. It is the only control on the
 * screen, it is operated by a thumb while both hands are holding a phone steady over a document,
 * and it has to be findable without looking away from the frame.
 */
@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.itemedit_identity_capture_shutter)
    Box(
        modifier = Modifier
            .size(80.dp)
            .lkPressScale(interaction, pressedScale = 0.93f)
            .foundationClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = label }
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .lkRing(androidx.compose.foundation.shape.CircleShape, c.textStrong.copy(alpha = 0.85f), 3.dp),
        )
        Box(
            Modifier
                .size(66.dp)
                .background(c.jadeFill, androidx.compose.foundation.shape.CircleShape),
        )
    }
}

/**
 * The security line, read at the moment it applies.
 *
 * `files_attachments_photo_note` verbatim, both halves — metadata removed, and photos excluded from
 * Android's backup. It used to sit under the attachment buttons on a screen this flow never
 * reaches; here it renders while the user is still looking at the page they just shot, which is the
 * only moment either half is actionable.
 *
 * Its own surface rather than [LkInlineNote]: this one sits over a camera image, so it needs an
 * opaque backdrop instead of a tint that would take its colour from whatever is behind it.
 */
@Composable
private fun CaptureNote(modifier: Modifier = Modifier) {
    val c = LkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lkSurface(LkShape.lg, c.ground.copy(alpha = 0.66f), c.jade.copy(alpha = 0.18f))
            .padding(LkSpace.md),
        horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
    ) {
        Icon(
            LkIcons.Check,
            contentDescription = null,
            tint = c.jadeLit,
            modifier = Modifier.size(16.dp).padding(top = 1.dp),
        )
        Text(
            stringResource(com.localkeep.feature.files.R.string.files_attachments_photo_note),
            style = LkText.caption,
            color = c.textTertiary,
        )
    }
}

/** The last step: a name, prefilled so Save always works, and Save. */
@Composable
private fun IdentityCaptureNaming(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    thumbnails: List<Bitmap>,
    onBack: () -> Unit,
) {
    val c = LkTheme.colors
    // Written into the ViewModel rather than held here, because `save()` reads the ViewModel. Done
    // once, and only over a blank title, so re-entering this step does not overwrite a name the
    // user typed and then went back from.
    val default = stringResource(R.string.itemedit_identity_capture_default_title)
    LaunchedEffect(Unit) {
        if (state.title.isBlank()) viewModel.onTitleChanged(default)
    }
    val pages = state.identityPhotos.size

    LkScreen(accent = c.jade, auraAlpha = 0.07f, auraBias = 0.5f) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = LkFrame.horizontal)
                .padding(top = LkFrame.top, bottom = LkFrame.bottom),
            verticalArrangement = Arrangement.spacedBy(LkSpace.lg),
        ) {
            PageStack(pages, thumbnails, Modifier.align(Alignment.CenterHorizontally))
            Column(Modifier.idRise(delayMillis = 200)) {
                Text(
                    stringResource(R.string.itemedit_identity_capture_name_title),
                    // See the 21-3 note on the identity start title above (fix-I, 2026-09-13).
                    modifier = Modifier.semantics { heading() },
                    style = LkText.screenTitle,
                    color = c.textStrong,
                )
                Spacer(Modifier.height(LkSpace.sm))
                Text(
                    pluralStringResource(
                        R.plurals.itemedit_identity_capture_name_body,
                        pages,
                        pages,
                    ),
                    style = LkText.body,
                    color = c.textMuted,
                )
            }
            LkTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChanged,
                label = stringResource(R.string.itemedit_identity_capture_name_label),
                modifier = Modifier.fillMaxWidth().idRise(delayMillis = 260),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.save() }),
            )
            Spacer(Modifier.weight(1f))
            // A refused save drops straight back here — `save()` clears `isSaving` and sets an
            // error — and this is the only screen in the route that could show it. Without this
            // the button would simply do nothing: the linear flow has no form underneath to carry
            // the message the way the ordinary editor does.
            state.error?.let { error ->
                val message = stringResource(error)
                LkInlineError(message, Modifier.announce(message))
                // No Spacer here: this column is `spacedBy`, so a Spacer is itself a spaced child and
                // costs its own height plus a gap on each side (V23, 2026-09-12). Same trap as G11.
            }
            LkPrimaryButton(
                text = stringResource(R.string.itemedit_edit_save),
                modifier = Modifier.fillMaxWidth(),
                onClick = viewModel::save,
            )
            LkTextButton(
                text = stringResource(R.string.itemedit_identity_capture_add_page),
                color = c.textMuted,
                modifier = Modifier.fillMaxWidth(),
                onClick = onBack,
            )
        }
    }
}

/**
 * What the flow is holding, as a fanned stack with a count.
 *
 * The count is the load-bearing part — this is the last screen before the write, and "3 pages" is
 * the only chance to notice that the back was photographed twice and the front not at all.
 */
@Composable
private fun PageStack(pages: Int, thumbnails: List<Bitmap>, modifier: Modifier = Modifier) {
    val c = LkTheme.colors
    val shown = pages.coerceIn(1, RAIL_SLOTS)
    // Back to front, so two pages fan visibly (-7° behind 0°) instead of two near-parallel cards.
    val fan = listOf(
        Triple(-7f, 0.dp, 8.dp),
        Triple(3.5f, 8.dp, 4.dp),
        Triple(0f, 6.dp, 0.dp),
    ).takeLast(shown).let { if (shown == 2) listOf(FAN_BACK, FAN_TOP) else it }
    // Each card lighter than the one behind it — flat fills would read as one card with a shadow.
    val fills = listOf(
        Brush.linearGradient(listOf(Color(0xFF2A353B), Color(0xFF1A2429))),
        Brush.linearGradient(listOf(Color(0xFF334047), Color(0xFF1E282E))),
        Brush.linearGradient(listOf(Color(0xFF3E4E57), Color(0xFF242F35))),
    ).takeLast(shown).let { if (shown == 2) listOf(it.first(), it.last()) else it }

    Box(modifier.size(width = 190.dp, height = 132.dp)) {
        Box(Modifier.align(Alignment.Center).size(width = 172.dp, height = 112.dp)) {
            fan.forEachIndexed { index, (angle, startPad, topPad) ->
                // The page this card stands for, counting from the back of the fan.
                val pageIndex = shown - 1 - index
                Box(
                    Modifier
                        .padding(start = startPad, top = topPad)
                        .size(width = 160.dp, height = 100.dp)
                        .idRise(
                            delayMillis = 60 + index * 60,
                            travel = 12.dp,
                            durationMillis = IdFanMillis,
                        )
                        .rotate(angle)
                        .clip(LkShape.sm)
                        .background(fills.getOrElse(index) { fills.last() })
                        .lkRing(LkShape.sm, c.ring)
                        .decorative(),
                ) {
                    // The mockup drew generic document art here because it had no photograph to
                    // show. This does, and the real page is the same thing said honestly — it is
                    // also the only way the user can tell the stack apart from a placeholder.
                    thumbnails.getOrNull(pageIndex)?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    // Overhangs the stack, as drawn — inside it, the badge reads as part of the
                    // top card rather than as a count of all of them.
                    .offset(x = 10.dp, y = (-8).dp)
                    .idRise(delayMillis = 260, travel = 12.dp, durationMillis = IdFanMillis)
                    .height(24.dp)
                    .background(c.jade, LkShape.sm)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    pluralStringResource(R.plurals.itemedit_identity_capture_pages, pages, pages),
                    style = LkText.chip,
                    color = c.onJade,
                )
            }
        }
    }
}

private val FAN_BACK = Triple(-7f, 0.dp, 8.dp)
private val FAN_TOP = Triple(0f, 6.dp, 0.dp)

/**
 * The write and the payoff, both now [LkEncryptingScreen] / [LkSavedScreen] in `core/ui`.
 *
 * They were drawn here first and lived here privately until `Item Flows.dc.html` asked six more
 * flows for the same two screens. The comp's rule — *"a save never looks different depending on
 * what you saved"* — cannot be kept by a copy per flow, so the drawing moved out unchanged and this
 * file kept only what is specific to an identity: its body copy, its page count, and the two notes
 * about re-authentication and the backup set.
 */
@Composable
private fun IdentitySaving() {
    LkEncryptingScreen(
        title = stringResource(R.string.itemedit_edit_encrypting),
        body = stringResource(R.string.itemedit_identity_saving_body),
    )
}

@Composable
private fun IdentitySaved(title: String, pages: Int, onDone: () -> Unit) {
    val c = LkTheme.colors
    LkSavedScreen(
        title = title,
        body = pluralStringResource(R.plurals.itemedit_identity_saved_body, pages, pages),
        actionLabel = stringResource(R.string.itemedit_identity_capture_done),
        onAction = onDone,
    ) {
        // The two properties a passport photograph has that a typed field does not. Kept as a slot
        // on the shared screen rather than baked into it: a new folder has nothing to add here, and
        // an empty pair of rows on every save would be furniture.
        Spacer(Modifier.height(LkSpace.lg))
        Row(
            Modifier
                .fillMaxWidth()
                // A minimum, not a height. The notice explaining *why* this item will demand re-auth
                // is the one line on the confirmation screen the user has to read, and at the
                // largest font size it needs two lines in the width left beside the lock glyph.
                .heightIn(min = 48.dp)
                .lkSurface(LkShape.xl, c.surface, c.ring)
                .padding(horizontal = LkSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            Icon(LkIcons.Lock, null, tint = c.jade, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.itemedit_identity_saved_reauth),
                style = LkText.body,
                color = c.textSecondary,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .lkSurface(LkShape.xl, c.surface, c.ring)
                .padding(LkSpace.md),
            horizontalArrangement = Arrangement.spacedBy(LkSpace.sm),
        ) {
            Icon(
                LkIcons.Info,
                null,
                tint = c.textMuted,
                modifier = Modifier.size(18.dp).padding(top = 1.dp),
            )
            Text(
                stringResource(com.localkeep.feature.files.R.string.files_attachments_photo_note),
                style = LkText.caption,
                color = c.textTertiary,
            )
        }
    }
}

/** The captured still and its rail thumbnail, decoded off the main thread; null if undecodable. */
internal suspend fun decodeCapturedFrame(
    bytes: ByteArray,
    decode: (ByteArray) -> Bitmap? = { BitmapFactory.decodeByteArray(it, 0, it.size) },
): Pair<Bitmap, Bitmap>? = withContext(Dispatchers.Default) {
    val full = decode(bytes) ?: return@withContext null
    full to full.thumbnail()
}

/** A rail-sized copy, so three full frames are not held in memory for decoration. */
private fun Bitmap.thumbnail(): Bitmap {
    val target = 192
    if (width <= target) return this
    val scaled = target.toFloat() / width
    return Bitmap.createScaledBitmap(this, target, (height * scaled).toInt().coerceAtLeast(1), true)
}

private const val PAGE_FRONT = 0
private const val PAGE_BACK = 1
private const val PAGE_EXTRA = 2
private const val PAGE_NAME = 3
private const val RAIL_SLOTS = 3

/** ID-1, the ratio every passport card and national ID in the world is cut to. */
private const val DocumentAspect = 1.585f
private const val GuideCornerRadiusDp = 16
private const val HintDwellMillis = 3_600L

/** `idRise` / `idScreen`, 380ms. */
private const val IdRiseMillis = 380

/** `idFan`, 460ms — the page stack. */
private const val IdFanMillis = 460

/** `idPulse`, 1.8s — the rail's active dot, opacity .35→1 and scale 1→1.35 together. */
private const val IdPulseMillis = 900

/** `idBreathe`, 3.4s, staggered 200ms per corner bracket. */
private const val IdBreatheMillis = 3_400
private const val IdBreatheStaggerMillis = 200
