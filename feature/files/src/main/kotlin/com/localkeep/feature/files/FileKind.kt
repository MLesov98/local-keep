package com.localkeep.feature.files

import androidx.annotation.StringRes

/**
 * What kind of thing an attachment is, and therefore which viewer can honestly show it.
 *
 * The attachment picker accepts every mime type, but the viewer only ever asked "is this an image?" and sent
 * everything else to the video player. A PDF therefore reached a media decoder, which answered
 * "Playback failed (decoder error 1/-2147483648)" under a header reading "Video" (2026-07-29
 * hand-test). Every branch below now exists because something real can be done with it, and
 * [OTHER] exists so the app can say so plainly instead of guessing.
 *
 * Both the mime type and the filename are consulted. A document picker frequently reports
 * `application/octet-stream` for a file whose name ends `.md`, and the extension is then the only
 * honest signal available.
 */
internal enum class FileKind {
    IMAGE,
    /** Anything a media player can decode — video and audio alike. */
    PLAYABLE,
    PDF,
    /** Plain text in some dialect: .txt, .md, .csv, .json, .xml, .log. Shown as written. */
    TEXT,
    /** Office Open XML — a zip of XML. Text can be extracted; layout cannot be reproduced. */
    OFFICE,
    /** Nothing in this app can render it. Said out loud rather than guessed at. */
    OTHER,
    ;

    /**
     * What the viewer's header calls it.
     *
     * A resource id rather than a String: an enum property is invisible to
     * `checkNoHardcodedUiStrings`, which is how these six words sat untranslated inside a fully
     * translated screen -- the same blind spot that shipped `NewItemType`'s labels in English.
     */
    @get:StringRes
    val screenTitleRes: Int
        get() = when (this) {
            IMAGE -> R.string.files_kind_image
            PLAYABLE -> R.string.files_kind_playable
            PDF -> R.string.files_kind_pdf
            TEXT -> R.string.files_kind_text
            OFFICE -> R.string.files_kind_office
            OTHER -> R.string.files_kind_other
        }

    /**
     * The three-or-four-character token the filmstrip draws on a tile that has no picture and whose
     * stored filename carries no extension.
     *
     * Its own resource rather than a slice of [screenTitleRes]: `take(3)` turned "Text" into "Tex"
     * next to another plain-text file's honest "TXT", and would have cut every localised word the
     * same way ("Document" -> "Doc", "Fichier" -> "Fic").
     */
    @get:StringRes
    val badgeRes: Int
        get() = when (this) {
            IMAGE -> R.string.files_badge_image
            PLAYABLE -> R.string.files_badge_playable
            PDF -> R.string.files_badge_pdf
            TEXT -> R.string.files_badge_text
            OFFICE -> R.string.files_badge_office
            OTHER -> R.string.files_badge_other
        }
}

private val TEXT_MIMES = setOf(
    "application/json",
    "application/xml",
    "application/csv",
    "application/x-yaml",
    "application/yaml",
)

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "csv", "tsv", "json", "xml", "log", "yml", "yaml", "ini", "conf",
    "kt", "java", "py", "js", "ts", "sh", "sql", "html", "htm", "css", "rtf",
)

private val OFFICE_EXTENSIONS = setOf("docx", "xlsx", "pptx")

private val OFFICE_MIMES = setOf(
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)

/**
 * Vector image types no bitmap decoder on this device will ever open.
 *
 * They are images by mime and not by anything `BitmapFactory` can act on, so routing them by the
 * `image/` prefix produced "This image decrypted correctly but isn't in a format this device can
 * decode. Export it to your gallery to open it in another app." — an offer to put a vector drawing
 * into the photo library. [FileKind.OTHER] says the true thing instead.
 */
private val UNDECODABLE_IMAGE_MIMES = setOf("image/svg+xml", "image/svg")

private val UNDECODABLE_IMAGE_EXTENSIONS = setOf("svg", "svgz")

/**
 * Classifies by mime first, then by filename extension when the mime is absent or the generic
 * `application/octet-stream` a picker falls back to.
 *
 * Both are hearsay — see [sniffFileKind], which reads the file's own leading bytes and overrules
 * this when the two disagree.
 */
internal fun fileKindOf(mime: String, filename: String): FileKind {
    val m = mime.lowercase().substringBefore(';').trim()
    val extension = filename.substringAfterLast('.', "").lowercase()

    if (m in UNDECODABLE_IMAGE_MIMES || extension in UNDECODABLE_IMAGE_EXTENSIONS) return FileKind.OTHER

    when {
        m.startsWith("image/") -> return FileKind.IMAGE
        m.startsWith("video/") || m.startsWith("audio/") -> return FileKind.PLAYABLE
        m == "application/pdf" -> return FileKind.PDF
        m in OFFICE_MIMES -> return FileKind.OFFICE
        m.startsWith("text/") || m in TEXT_MIMES -> return FileKind.TEXT
        // "+json" / "+xml" structured suffixes: application/vnd.api+json and friends.
        m.endsWith("+json") || m.endsWith("+xml") -> return FileKind.TEXT
    }

    return when (extension) {
        "pdf" -> FileKind.PDF
        in OFFICE_EXTENSIONS -> FileKind.OFFICE
        in TEXT_EXTENSIONS -> FileKind.TEXT
        else -> FileKind.OTHER
    }
}
