package com.localkeep.feature.files

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract

/**
 * The document-picker half of "delete the original after saving" — the gallery half is
 * [MediaDeleter].
 *
 * Reported from a phone 2026-10-09: the offer to remove the original existed for gallery photos and
 * videos only, so a PDF or a spreadsheet stored in the vault left its unprotected copy in Downloads
 * with nothing said. A gallery item goes through Android's own confirmation dialog; a document has
 * none — `DocumentsContract.deleteDocument` just deletes — so the app's own question is the only
 * confirmation, and its copy says the deletion is permanent.
 *
 * **Offered only when it can work and only where it is safe.** Three conditions, all checked at
 * import while the picker's grant is fresh:
 *  - a local provider ([LOCAL_AUTHORITIES]): device storage, Downloads, and the media documents
 *    root. A cloud provider is never offered, whatever its flags say — deleting a Drive or OneDrive
 *    document can remove a copy other people share, which is not this app's call to make;
 *  - the provider reports `FLAG_SUPPORTS_DELETE` for this document;
 *  - this app holds a write grant on the URI. `ACTION_OPEN_DOCUMENT` grants one where the provider
 *    allows it; where it does not, nothing is offered rather than an offer that would fail.
 */
object OriginalDocuments {

    internal val LOCAL_AUTHORITIES = setOf(
        "com.android.externalstorage.documents",
        "com.android.providers.downloads.documents",
        "com.android.providers.media.documents",
    )

    /** The decision, pure so it can be tested without a provider. */
    internal fun isOfferable(authority: String?, documentFlags: Int, canWrite: Boolean): Boolean =
        authority in LOCAL_AUTHORITIES &&
            documentFlags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0 &&
            canWrite

    /** [uri] when its original may be deleted after save, else null. Never throws. */
    fun deletableOrNull(context: Context, uri: Uri): Uri? = runCatching {
        if (!DocumentsContract.isDocumentUri(context, uri)) return@runCatching null
        val flags = context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 } ?: 0
        val canWrite = context.checkCallingOrSelfUriPermission(
            uri,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
        uri.takeIf { isOfferable(uri.authority, flags, canWrite) }
    }.getOrNull()

    /**
     * Deletes the original. False for every failure — a revoked grant, a provider that refuses, a
     * file already gone — which the caller reports as "could not be deleted" rather than as an
     * error: the vault copy is saved either way.
     */
    fun delete(context: Context, uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
}
