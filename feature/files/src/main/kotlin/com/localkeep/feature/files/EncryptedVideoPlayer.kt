package com.localkeep.feature.files

import android.util.Log
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.localkeep.core.crypto.StreamingAeadReader
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSkeleton
import com.localkeep.core.ui.theme.LkSlider
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.vault.SessionKey
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Feeds a [StreamingAeadReader] to the platform decoder.
 *
 * `MediaDataSource` is the reason in-app playback is possible at all here: it is the one
 * `MediaPlayer` input that is neither a file path nor a URL, so the video can be decoded without a
 * decrypted copy existing anywhere for the decoder to open. The platform calls [readAt] on its own
 * decoder threads, at arbitrary offsets, which is exactly the access pattern the reader was built
 * for.
 */
internal class EncryptedMediaDataSource(
    private val reader: StreamingAeadReader,
) : MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        reader.readAt(position, buffer, offset, size)

    override fun getSize(): Long = reader.plaintextLength

    override fun close() = reader.close()
}

/** How often the scrubber catches up with the decoder while playing. */
private const val POSITION_POLL_MS = 250L

/**
 * Holds the display awake for as long as the decoder is actually running.
 *
 * Without this a video longer than the screen timeout could not be watched at all. The display would
 * sleep part-way through, the platform would broadcast `ACTION_SCREEN_OFF`, and the receiver
 * `LocalKeepApplication` registers for it would lock the vault out from under the player — so a long
 * clip ended not in a paused frame but on the unlock screen. That receiver is a deliberate control
 * and is not the thing to weaken: it exists because something drawing over the app without
 * backgrounding it never fires `onStop`, which left the vault open on a pocketed phone. The screen
 * simply must not be allowed to sleep while the user is watching, which is what an ordinary player
 * does and what this app did not do anywhere.
 *
 * Scoped to [playing], not to the viewer being on screen. Pinning the display on for the whole time
 * a video item is open would keep it lit through a paused frame, through the end of playback, and
 * through the user reading the file's metadata — draining the battery of someone who is not looking
 * at moving pictures. `View.keepScreenOn` is the narrowest form of this: it is scoped to one view,
 * needs no permission and no wake lock to release, and the platform drops it the moment the view
 * detaches, so a process death or a torn-down surface cannot leak it.
 */
internal fun applyPlaybackScreenAwake(view: View, playing: Boolean) {
    view.keepScreenOn = playing
}

/**
 * Puts the window's screen-security decision onto the video surface's own composited layer.
 *
 * Not redundant with the activity's `FLAG_SECURE`: a `SurfaceView` is composited as its own layer
 * rather than drawn into the window, so the window's flag does not cover it — without this the video
 * would be the one thing in the app that *does* appear in a screenshot and in the app-switcher
 * thumbnail. Must be called before the surface is created.
 *
 * Gated on the same bool as the window flag, and for the same reason. It was unconditional until
 * 2026-09-09, which made the video layer black in *every* capture including debug — so a QA
 * screenshot of a playing clip was indistinguishable from a broken player, and 150 sweeps read that
 * black rectangle as normal. The bool is `true` in core/ui's own values and `false` only in
 * `app/src/debug`, which release variants do not compile, so this fails closed exactly as
 * `SecureActivity` does: a missing or mis-merged override resolves to secure.
 *
 * [obscure] is a parameter with the resource as its default purely so a test can drive both
 * answers: `SurfaceView.setSecure` has no getter, and the flag is only readable off the view.
 */
internal fun SurfaceView.applyScreenSecurity(
    obscure: Boolean = context.resources.getBoolean(com.localkeep.core.ui.R.bool.lk_obscure_screen),
) {
    setSecure(obscure)
}

/**
 * Plays an encrypted video in place, decrypting frame by frame — the vault's copy is never decrypted
 * to disk, and the only bytes in the clear at any moment are the 64 KiB frame the decoder just asked
 * for (see [StreamingAeadReader]).
 *
 * Playback is paused, not just left running, when the app goes to the background: the window carries
 * `FLAG_SECURE` so nothing renders into the app-switcher thumbnail, but audio would otherwise keep
 * playing out of a vault the user thought they had put away.
 */
@Composable
internal fun EncryptedVideoPlayer(
    blobRef: String,
    sessionKey: SessionKey,
    fileBlobStore: FileBlobStore,
    modifier: Modifier = Modifier,
    /**
     * Tap on the video surface, for the viewer's show/hide chrome — null where there is no chrome.
     *
     * Attached to the surface alone. The transport row underneath must keep its own taps, or the
     * gesture that reveals the controls is the same one that swallows the Play button.
     */
    onToggleChrome: (() -> Unit)? = null,
    /**
     * The attachment's stored thumbnail blob, drawn as a poster frame until playback starts.
     *
     * `MediaPlayer` renders nothing onto the surface before the first frame is decoded, so a video
     * item opened to an entirely black page with a transport bar floating in it and nothing saying
     * what the file was (release sweep 2026-09-20, finding 3C.0e-2). Video imports write a
     * downsampled frame as a second encrypted blob and record it here; the filmstrip reads it, and
     * this is the other place it belongs. Null is fine — an item imported before thumbnails were
     * written keeps the black surface it had.
     *
     * That sentence read "Every video import already writes" and was false when it was written:
     * only `PhotoPickerImport.import()` grabbed a frame, and every File item goes through
     * `stageFile()`, which did not — so this parameter was null for every video anyone could open
     * and the poster frame fixed nothing on a device (re-verify 1, 3C-3, 2026-09-21). `stageFile`
     * writes one now.
     */
    posterRef: String? = null,
) {
    val c = LkTheme.colors
    var player by remember(blobRef) { mutableStateOf<MediaPlayer?>(null) }
    var error by remember(blobRef) { mutableStateOf<Int?>(null) }
    // Format args for [error], when it takes any (only the decoder-error case does).
    var errorArgs by remember(blobRef) { mutableStateOf<List<Any>>(emptyList()) }
    var playing by remember(blobRef) { mutableStateOf(false) }
    // These two are `rememberSaveable`, unlike everything else here: no manifest in this app
    // declares `android:configChanges`, so a rotation destroys the activity and rebuilds the whole
    // composition. Everything else on this screen is re-derived from the decoder on the way back
    // up — the duration, the aspect ratio, the surface — but where the user had got to is not, and
    // it restarted a half-watched clip at 0:00 every time someone turned the phone for a bigger
    // picture. `ended` travels with it: restoring a position at the very end without it would put
    // a Play button on a finished clip, which starts and immediately completes.
    var ended by rememberSaveable(blobRef) { mutableStateOf(false) }
    // Saveable for the same reason: a rotation after playback began must not bring the poster back
    // over the frame the decoder is holding. Set once, on the first Play, and never cleared.
    var started by rememberSaveable(blobRef) { mutableStateOf(false) }
    var durationMs by remember(blobRef) { mutableIntStateOf(0) }
    var positionMs by rememberSaveable(blobRef) { mutableIntStateOf(0) }
    var aspect by remember(blobRef) { mutableStateOf(16f / 9f) }
    // Set while the user is dragging so the poll below doesn't yank the thumb back to where the
    // decoder currently is mid-gesture.
    var scrubbing by remember(blobRef) { mutableStateOf(false) }
    var holder by remember(blobRef) { mutableStateOf<SurfaceHolder?>(null) }

    var poster by remember(posterRef) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(posterRef) {
        val ref = posterRef ?: return@LaunchedEffect
        // A poster frame is decoration. Anything that goes wrong reading or decoding it leaves the
        // black surface that was there before, and it must never become an error the user is told
        // about or take the viewer down. Same contract the filmstrip's thumbnails are held to.
        poster = try {
            val bytes = withContext(Dispatchers.IO) {
                sessionKey.use { key ->
                    if (fileBlobStore.hasBlob(ref)) fileBlobStore.decryptToBytes(ref, key) else null
                }
            } ?: return@LaunchedEffect
            withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }?.asImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    // Build the player once the surface exists. Doing it in the other order gives a first frame of
    // audio with a black picture on some devices.
    DisposableEffect(blobRef, holder) {
        val surface = holder
        var created: MediaPlayer? = null
        var source: EncryptedMediaDataSource? = null
        if (surface != null) {
            try {
                // Both handles are taken before anything can throw. Building the player inside
                // `created = MediaPlayer().apply { … }` meant `created` stayed null if any call
                // after setDataSource failed — a torn-down surface, prepareAsync in a bad state —
                // so onDispose's release() was a no-op, the data source was never closed, and the
                // blob's file descriptor and the reader's derived key both survived. Repeat to
                // exhaust descriptors. The data source is held separately for the narrower case
                // where setDataSource itself throws, leaving the player without ownership of it.
                // Borrowed only for the construction of the reader, which is all it needs the DEK
                // for: `StreamingAeadReader` derives its own frame key in its constructor and never
                // reads the session key again. So playback holds no borrow, and a lock during a
                // two-hour clip is not made to wait for it.
                val dataSource = checkNotNull(
                    sessionKey.use { EncryptedMediaDataSource(fileBlobStore.openRandomAccess(blobRef, it)) },
                ) { "vault locked while opening playback" }
                source = dataSource
                val mediaPlayer = MediaPlayer()
                created = mediaPlayer
                mediaPlayer.apply {
                    // Declared before prepare, which is the only point MediaPlayer reads them.
                    // Without this the player publishes `usage=USAGE_UNKNOWN
                    // content=CONTENT_TYPE_UNKNOWN` (`dumpsys audio`, release sweep 2026-09-20,
                    // finding 4C.2-mp3-2), and an unknown usage takes no audio focus: the OS cannot
                    // duck or pause whatever else is playing, and an incoming call does not pause
                    // this. The vault's own clips are media like any other.
                    setAudioAttributes(playbackAudioAttributes())
                    setDataSource(dataSource)
                    setDisplay(surface)
                    setOnPreparedListener { mp ->
                        durationMs = mp.duration.coerceAtLeast(0)
                        // The restored position, if there is one. Seeking here rather than at the
                        // call site because this is the first moment the decoder will accept it —
                        // a seek before prepare is an IllegalStateException — and a player that
                        // has just been rebuilt after a rotation sits at 0 until told otherwise.
                        // Playback is not resumed with it: starting audio out of a vault on its
                        // own, off a gesture the user made to look at the picture, is not the
                        // decision this fix is making.
                        if (positionMs > 0) mp.runCatching { seekTo(positionMs) }
                        player = mp
                    }
                    setOnVideoSizeChangedListener { _, width, height ->
                        if (width > 0 && height > 0) aspect = width.toFloat() / height
                    }
                    setOnCompletionListener {
                        playing = false
                        ended = true
                        positionMs = durationMs
                    }
                    setOnErrorListener { _, what, extra ->
                        // A tampered frame surfaces here as a decode failure, not as an exception on
                        // this thread — readAt runs on the decoder's thread. Say what it means.
                        //
                        // `what`/`extra` are NOT shown. They used to be, as "(decoder error 1/0)":
                        // opaque MediaPlayer constants, meaningless to the person reading them and
                        // actionable by nobody. They go to the log instead, where a maintainer can
                        // actually use them — and where the rule about never echoing
                        // internals into the UI is satisfied rather than bent.
                        Log.d("EncryptedVideoPlayer", "MediaPlayer error what=$what extra=$extra")
                        error = R.string.files_video_decoder_error
                        errorArgs = emptyList()
                        playing = false
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                error = MediaErrors.videoOpenFailed(e)
                errorArgs = emptyList()
            }
        }
        onDispose {
            player = null
            playing = false
            // release() closes the data source, which closes the reader, which wipes its derived key.
            created?.runCatching { release() }
            // Belt and braces for the construction that never got as far as handing the source to
            // the player. close() is idempotent, so doing it after a successful release is harmless.
            source?.runCatching { close() }
        }
    }

    // Pause on background — see the KDoc. Not release: coming back to a lost position is worse than
    // holding a paused decoder for the moment the user is away.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player?.runCatching { if (isPlaying) pause() }
                playing = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player, playing, scrubbing) {
        while (playing && !scrubbing) {
            player?.runCatching { positionMs = currentPosition }
            delay(POSITION_POLL_MS)
        }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                // `weight(1f, fill = false)` rather than an unbounded height, and it is what keeps
                // the transport row on screen. Compose measures non-weighted children first, so the
                // Spacer and the controls below claim their ~62dp and the surface takes only what
                // is left; `aspectRatio` then fits inside that instead of deriving an unbounded
                // height from the page width. A 9:16 clip used to compute 1.78x the page width and
                // push the controls under the viewer's opaque action bar (2026-09-08).
                //
                // `fill = false` so a landscape clip stays its natural height rather than being
                // stretched down to fill the column.
                .weight(1f, fill = false)
                .aspectRatio(aspect.coerceIn(0.4f, 3f))
                .clip(LkShape.card)
                .then(
                    // The chrome toggle lives on the surface only, never on the transport row
                    // below, which has to stay tappable. Until this existed the video page was the
                    // one page whose chrome could not be dismissed: `onToggleChrome` was wired into
                    // ZoomableImage alone, and `chromeVisible` starts true.
                    if (onToggleChrome != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            // A full-surface clickable with no named descendant announces nothing:
                            // TalkBack reached the whole video surface and read it out as an
                            // unlabelled button (G15). The label says what the tap does, not what
                            // the thing is.
                            // The hint is not the name -- see V19 and LkFolderPicker.kt:610. The
                            // `.labelled(...)` below supplies the name this node otherwise lacks.
                            onClickLabel = stringResource(R.string.files_viewer_toggle_chrome_a11y),
                            onClick = onToggleChrome,
                        )
                            .labelled(stringResource(R.string.files_viewer_toggle_chrome_a11y))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                // Reads `playing`, so it re-runs on every transition into and out of playback —
                // including the ones that are not a button press: completion, the decoder error
                // listener, and the ON_STOP observer below all clear it.
                update = { view -> applyPlaybackScreenAwake(view, playing) },
                factory = { context ->
                    SurfaceView(context).apply {
                        applyScreenSecurity()
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        this.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                holder = h
                            }

                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Unit

                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                holder = null
                            }
                        })
                    }
                },
            )
            // Until the decoder reports ready there is nothing on the surface but black, which on a
            // black screen is indistinguishable from a hang.
            if (player == null && error == null) LkSkeleton(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            // The poster sits ON the surface rather than beside it: a SurfaceView punches a hole in
            // the window and renders underneath, so Compose content in the same Box composites above
            // it — which is how the skeleton above has always worked. Decoration inside a labelled,
            // clickable parent, so no contentDescription of its own.
            val shownPoster = poster
            if (shownPoster != null && shouldShowVideoPoster(playing, positionMs, error != null, started)) {
                Image(
                    bitmap = shownPoster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            val message = stringResource(error!!, *errorArgs.toTypedArray())
            LkInlineError(message, Modifier.announce(message))
            return@Column
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LkIconButton(
                icon = when {
                    ended -> LkIcons.Replay
                    playing -> LkIcons.Pause
                    else -> LkIcons.Play
                },
                label = when {
                    ended -> stringResource(R.string.files_video_play_again)
                    playing -> stringResource(R.string.files_video_pause)
                    else -> stringResource(R.string.files_video_play)
                },
                enabled = player != null,
                onClick = {
                    val mp = player ?: return@LkIconButton
                    mp.runCatching {
                        if (isPlaying) {
                            pause()
                            playing = false
                        } else {
                            if (ended) seekTo(0)
                            start()
                            ended = false
                            playing = true
                            started = true
                        }
                    }
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LkSlider(
                    // The slider works in whole seconds: a millisecond range would give TalkBack's
                    // volume-key adjustment tens of thousands of steps to walk through.
                    value = (positionMs / 1000).coerceIn(0, (durationMs / 1000).coerceAtLeast(0)),
                    range = 0..(durationMs / 1000).coerceAtLeast(1),
                    onValueChange = { seconds ->
                        scrubbing = true
                        positionMs = seconds * 1000
                        player?.runCatching { seekTo(seconds * 1000) }
                        ended = false
                        scrubbing = false
                    },
                    label = stringResource(R.string.files_video_position_label),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "${positionMs.asClock()} / ${durationMs.asClock()}",
                style = LkText.monoCaption,
                color = c.textMuted,
                modifier = Modifier
                    .padding(end = 2.dp)
                    .labelled(
                        stringResource(
                            R.string.files_video_position_a11y,
                            positionMs / 1000,
                            durationMs / 1000,
                        ),
                    ),
            )
        }
    }
}

/** `m:ss`, or `h:mm:ss` past an hour — the shortest form that is never ambiguous. */
internal fun Int.asClock(): String {
    val total = (this / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * What the vault's own clips are, as far as the audio policy is concerned: ordinary media.
 *
 * `MediaPlayer` defaults to `USAGE_UNKNOWN` / `CONTENT_TYPE_UNKNOWN`, and an unknown usage takes no
 * audio focus — nothing ducks for it, it ducks for nothing, and a call will not pause it.
 */
internal fun playbackAudioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

/**
 * Whether the poster frame still stands in for the decoder's output.
 *
 * Only before the clip has been started at all: once it has been played and paused, or resumed after
 * a rotation, the surface holds a real frame from where the viewer actually is, and covering that
 * with the first frame of the file would be a lie about the position. An error takes the whole
 * surface, so nothing is drawn over it either.
 *
 * "Not been started" is [hasStarted], not "position is 0": a clip played to the end and scrubbed
 * back to the start while paused is at 0 as well, and the decoder holds a real frame there, so the
 * poster drawn over it would be the file's cover rather than where the viewer is.
 */
internal fun shouldShowVideoPoster(
    playing: Boolean,
    positionMs: Int,
    hasError: Boolean,
    hasStarted: Boolean,
): Boolean =
    !playing && positionMs == 0 && !hasError && !hasStarted
