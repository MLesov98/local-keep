package com.localkeep.feature.files

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the post-save "delete the original?" offer hands `MediaStore.createDeleteRequest`.
 *
 * Device pass 2026-10-04 (C1 finding 1, API 36): a photo and a video imported From gallery and
 * saved raised no delete prompt at all, and both originals stayed in /sdcard/Pictures. The offer is
 * specified (spec §5.5) and wired; what failed was the URI. The Photo Picker answers with
 * `content://media/picker/…`, and measured on emulator-5554 the same day:
 *
 * - `MediaStore.getMediaUri(picker uri)` throws — it takes a DocumentsProvider URI, which a picker
 *   URI is not — and that is what `resolveMediaStoreUri` called;
 * - `createDeleteRequest(picker uri)` throws "All requested items must be referenced by specific ID";
 * - `createDeleteRequest(content://media/external/images/media/20)` returns a request, with this
 *   app holding no media permission at all.
 *
 * Both failures were swallowed by the `runCatching` around the request, which then left the
 * editor as if there were simply no original to offer. Silently, on every gallery import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryOriginalDeleteRequestUriTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun resolve(uri: String, mime: String): Uri? =
        MediaDeleter.resolveMediaStoreUri(context, Uri.parse(uri), mime)

    @Test
    fun `a photo picked from the gallery is offered as the MediaStore image it stands for`() {
        assertEquals(
            Uri.parse("content://media/external/images/media/20"),
            resolve("content://media/picker/0/com.android.providers.media.photopicker/media/20", "image/png"),
            "a picker URI went to createDeleteRequest, which refuses every one of them",
        )
    }

    @Test
    fun `a video is offered as the MediaStore video, from either picker URI shape`() {
        assertEquals(
            Uri.parse("content://media/external/video/media/19"),
            resolve("content://media/picker_get_content/0/com.android.providers.media.photopicker/media/19", "video/mp4"),
        )
    }

    /** A cloud item has no copy in this device's MediaStore, so there is nothing here to delete. */
    @Test
    fun `an item from a cloud provider is not offered`() {
        assertNull(resolve("content://media/picker/0/com.example.cloudpicker/media/20", "image/jpeg"))
    }

    /**
     * Another profile's pick carries that profile's id, and its number would name a different
     * photo — or none — in this profile's MediaStore. Nothing is offered rather than the wrong one.
     */
    @Test
    fun `an item from another profile is not offered`() {
        assertNull(resolve("content://media/picker/10/com.android.providers.media.photopicker/media/20", "image/jpeg"))
    }
}
