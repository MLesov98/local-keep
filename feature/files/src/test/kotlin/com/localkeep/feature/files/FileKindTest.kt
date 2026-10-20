package com.localkeep.feature.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The attachment picker takes every mime type; the viewer used to ask only "image?" and hand everything else
 * to the video player. A PDF came back as "Playback failed (decoder error 1/-2147483648)" under a
 * header reading "Video" (2026-07-29 hand-test). Nothing may reach the player unless it is genuinely
 * playable.
 */
class FileKindTest {

    @Test
    fun `a pdf is never playable`() {
        assertEquals(FileKind.PDF, fileKindOf("application/pdf", "statement.pdf"))
        // The case that actually shipped broken: a picker that reports nothing useful.
        assertEquals(FileKind.PDF, fileKindOf("application/octet-stream", "statement.pdf"))
        assertEquals(FileKind.PDF, fileKindOf("", "statement.PDF"))
    }

    @Test
    fun `office documents are documents, by mime or by extension`() {
        assertEquals(
            FileKind.OFFICE,
            fileKindOf(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "contract.docx",
            ),
        )
        assertEquals(FileKind.OFFICE, fileKindOf("application/octet-stream", "contract.docx"))
        assertEquals(FileKind.OFFICE, fileKindOf("", "budget.xlsx"))
        assertEquals(FileKind.OFFICE, fileKindOf("", "deck.pptx"))
    }

    @Test
    fun `media still reaches the player`() {
        assertEquals(FileKind.IMAGE, fileKindOf("image/jpeg", "photo.jpg"))
        assertEquals(FileKind.PLAYABLE, fileKindOf("video/mp4", "clip.mp4"))
        assertEquals(FileKind.PLAYABLE, fileKindOf("audio/mpeg", "note.mp3"))
    }

    @Test
    fun `text dialects are text`() {
        assertEquals(FileKind.TEXT, fileKindOf("text/plain", "notes.txt"))
        assertEquals(FileKind.TEXT, fileKindOf("application/json", "export.json"))
        assertEquals(FileKind.TEXT, fileKindOf("application/vnd.api+json", "payload"))
        assertEquals(FileKind.TEXT, fileKindOf("application/octet-stream", "README.md"))
        assertEquals(FileKind.TEXT, fileKindOf("", "server.log"))
    }

    @Test
    fun `an unknown binary is admitted to be unknown`() {
        assertEquals(FileKind.OTHER, fileKindOf("application/octet-stream", "firmware.bin"))
        assertEquals(FileKind.OTHER, fileKindOf("", "no-extension"))
        // Legacy Office is NOT OfficeXML — extracting text from a .doc needs a parser this app has
        // no honest way to ship, so it must not claim it can.
        assertEquals(FileKind.OTHER, fileKindOf("application/msword", "old.doc"))
    }

    /**
     * Regression for 4C.2-txt (release sweep 2026-09-20): the filmstrip badge for a file whose
     * stored name had lost its extension was the first three characters of the kind's screen title,
     * so a plain-text file rendered as `Tex` beside another `.txt` file's honest `TXT`. A localised
     * title would have been cut the same way.
     */
    @Test
    fun `every kind has its own badge token, distinct from the header word it is not a slice of`() {
        assertEquals(
            FileKind.entries.size,
            FileKind.entries.map { it.badgeRes }.toSet().size,
            "two kinds share a badge resource, so one of them is labelled wrong",
        )
        FileKind.entries.forEach { kind ->
            assertNotEquals(
                kind.screenTitleRes,
                kind.badgeRes,
                "$kind's badge is its header word again, which is what `take(3)` used to cut",
            )
        }
    }

    @Test
    fun `every kind names itself for the header`() {
        // The header said "Video" over a PDF. Each kind now owns its own word -- and owns it as a
        // string resource rather than a Kotlin literal, because an enum property is invisible to
        // `checkNoHardcodedUiStrings` and these six words therefore sat in English inside a fully
        // translated screen.
        assertEquals(R.string.files_kind_pdf, FileKind.PDF.screenTitleRes)
        assertEquals(R.string.files_kind_office, FileKind.OFFICE.screenTitleRes)
        assertEquals(R.string.files_kind_other, FileKind.OTHER.screenTitleRes)
        assertEquals(
            FileKind.entries.size,
            FileKind.entries.map { it.screenTitleRes }.toSet().size,
            "two kinds share a title resource, so one of them is named wrong",
        )
    }
}
