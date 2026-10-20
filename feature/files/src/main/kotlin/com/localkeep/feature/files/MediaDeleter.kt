package com.localkeep.feature.files

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/** Wraps `MediaStore.createDeleteRequest` (API 30+, §5.5) — the system confirm-dialog that removes the
 *  original from the gallery after a successful import. Below API 30 this feature is unavailable; callers
 *  must check [isDeleteRequestSupported] and show manual-deletion guidance instead. */
object MediaDeleter {
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    fun isDeleteRequestSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * The MediaStore item [pickedUri] stands for — the only kind of URI `createDeleteRequest` takes —
     * or null when there is none this app may ask to delete. No-op below API 30.
     *
     * [mime] picks the collection: the request refuses a bare `files` URI for anything but a
     * subtitle, so a photo has to be named as an image and a video as a video.
     */
    fun resolveMediaStoreUri(context: Context, pickedUri: Uri, mime: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return pickedUri
        return resolveMediaStoreUriApi30(context, pickedUri, mime)
    }

    /**
     * A Photo Picker URI is mapped by hand; nothing else here is.
     *
     * This used to hand every URI to `MediaStore.getMediaUri`, which takes a *DocumentsProvider*
     * URI. The Photo Picker answers with `content://media/picker/…`, which is not one, so on a
     * device with the picker — every API 33+ device, and older ones through the Play system update
     * — it threw, the caller's `runCatching` swallowed it, and no gallery import ever offered to
     * delete its original (C1 device pass, API 36, 2026-10-04). Passing the picker URI straight
     * through does not help either: measured the same day, `createDeleteRequest` refuses it with
     * "All requested items must be referenced by specific ID", and accepts
     * `content://media/external/images/media/<id>` with no media permission held.
     *
     * The id is only trusted where it means the same thing on both sides: the *local* picker
     * provider, whose ids are this device's MediaStore ids, and this profile. A cloud item has no
     * MediaStore row here, and another profile's id would name a different photo in this one's
     * MediaStore — so both answer null, and the caller simply offers nothing. The system dialog
     * shows the photo it is about to delete either way; this is what makes it the right photo.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun resolveMediaStoreUriApi30(context: Context, pickedUri: Uri, mime: String): Uri? {
        val segments = pickedUri.pathSegments
        if (pickedUri.authority != MediaStore.AUTHORITY) return MediaStore.getMediaUri(context, pickedUri)
        // `content://media/external/images/media/20` and friends are already MediaStore items.
        if (segments.firstOrNull() !in PICKER_ROOTS) return pickedUri
        // picker/<user>/<provider authority>/media/<id>
        if (segments.size != 5 || segments[3] != "media") return null
        if (segments[1].toIntOrNull() != Process.myUid() / PER_USER_RANGE) return null
        if (segments[2] != LOCAL_PICKER_AUTHORITY) return null
        val id = segments[4].toLongOrNull() ?: return null
        val collection = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else -> return null
        }
        return ContentUris.withAppendedId(collection, id)
    }

    /** Builds the [IntentSender] for the system delete-confirmation dialog. Callers must check
     *  [isDeleteRequestSupported] first — this throws on API < 30. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun createDeleteRequest(context: Context, uris: List<Uri>): IntentSender =
        MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender

    /** `ACTION_PICK_IMAGES` answers under `picker`, the picker's `GET_CONTENT` route under the other. */
    private val PICKER_ROOTS = setOf("picker", "picker_get_content")

    /** The on-device provider behind the Photo Picker; its media ids are MediaStore ids. */
    private const val LOCAL_PICKER_AUTHORITY = "com.android.providers.media.photopicker"

    /** `UserHandle.PER_USER_RANGE`, which is not public: a uid is `userId * 100000 + appId`. */
    private const val PER_USER_RANGE = 100_000
}
