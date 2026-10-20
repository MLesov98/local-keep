package com.localkeep.feature.files

import android.net.Uri
import com.localkeep.core.model.File
import java.util.UUID

/** Transient metadata state; it is never serialized into the vault item schema. */
enum class MetadataCleanupState {
    NOT_APPLICABLE,
    PENDING,
    REMOVED,
    FAILED,
}

/** Encrypted, short-lived source state used by the File editor before commit. */
data class StagedFileSource(
    val blobRef: String,
    val thumbnailRef: String?,
    val filename: String = "file",
    val mime: String,
    val sizeBytes: Long,
    val generationId: UUID,
    val metadataState: MetadataCleanupState,
    val sourceUri: Uri?,
    val capturedInApp: Boolean,
    val removedFromGallery: Boolean,
    val pendingGalleryDeleteUri: Uri?,
    /**
     * True when these bytes came from the device gallery, so an original of them is still sitting
     * there.
     *
     * Distinct from [pendingGalleryDeleteUri], which is null on API 28/29 for *every* import because
     * `createDeleteRequest` is API 30 — and 28/29 is exactly the pair of releases the editor's
     * "remove it by hand" note is written for, so that field cannot answer this question.
     *
     * Never persisted: [toFile] does not carry it and `com.localkeep.core.model.File` has no such
     * field. Teaching the model one would be a vault-format change (version bump plus migration),
     * which a reminder whose whole job is to be seen in the moments after an import does not
     * justify — so a reopened item shows no note at all. Added 2026-09-13.
     */
    val hasGalleryOriginal: Boolean = false,
    /**
     * What [MetadataProbe] found in the staged bytes — whether "Delete all metadata" has anything
     * to remove. Import-session state, like every field here except those [toFile] carries.
     */
    val metadataPresence: MetadataPresence = MetadataPresence.UNKNOWN,
    /**
     * A document-picker original this app may delete after the file is saved, or null.
     *
     * Decided at import, while the picker's grant is fresh: the provider has to be a local one
     * ([OriginalDocuments.LOCAL_AUTHORITIES]), report `FLAG_SUPPORTS_DELETE`, and have granted
     * write access. A cloud document is never offered — deleting a Drive file can remove a copy
     * other people share. Never persisted, for [hasGalleryOriginal]'s reason.
     */
    val deletableOriginalDocument: Uri? = null,
)

/** The committed model shape returned by [PhotoPickerImport.commitStagedFile]. */
fun StagedFileSource.toFile(filenameOverride: String): File = File(
    filename = filenameOverride,
    mime = mime,
    sizeBytes = sizeBytes,
    blobRef = blobRef,
    thumbnailRef = thumbnailRef,
    capturedInApp = capturedInApp,
    removedFromGallery = removedFromGallery,
)

enum class AttachmentImportFailureReason {
    UNSTRIPPABLE_IMAGE,
    IMPORT_FAILED,
}

sealed interface AttachmentImportResult {
    data class Success(val attachment: com.localkeep.core.model.Attachment) : AttachmentImportResult
    data class Failure(val reason: AttachmentImportFailureReason) : AttachmentImportResult
}
