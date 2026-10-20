package com.localkeep.feature.backup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A wrong password on an import must not promise a retry the screen cannot honour.
 *
 * The message said "Wrong password for this file. Enter it again to retry." — which is exactly true
 * of the *export* form, where retyping the password and pressing Export is the whole retry. On the
 * import form it is not: `stageImport` stores neither the picked `Uri` nor the bytes behind it, and
 * the screen clears the password well the moment the pick is launched, so following the instruction
 * literally — retype, press the button — reopens the system document picker rather than re-trying
 * the file. The screen's own comment on the Choose-file gate has said so for a while ("the failure
 * path caches neither the vault nor the picked Uri, so 'retry' means walking the whole SAF picker
 * again"); the string was the half that had not caught up.
 *
 * Copy, not caching, because the alternative is worse than it looks: the `Uri` is granted to that
 * activity result and is not taken persistably, so a cached one is a handle that works until it
 * quietly does not. Saying what the user has to do costs nothing and is true in every case.
 *
 * The five locales are checked for the *second* instruction specifically, not merely for being
 * different, so a translation that drops it fails here rather than at the next sweep.
 */
class ImportRetryCopyTest {

    private fun stringIn(locale: String): String =
        File("src/main/res/$locale/strings.xml").readText()
            .substringAfter("<string name=\"backup_error_import_wrong_password\">")
            .substringBefore("</string>")

    @Test
    fun `the wrong-password message says the file has to be chosen again`() {
        // The token that carries the second instruction in each language. Checked rather than the
        // whole sentence so ordinary rewording stays possible and dropping the instruction does not.
        mapOf(
            "values" to "choose the file again",
            "values-fr" to "resélectionnez le fichier",
            "values-de" to "wählen Sie die Datei erneut aus",
            "values-es" to "vuelva a elegir el archivo",
            "values-pt" to "escolha o arquivo de novo",
        ).forEach { (locale, token) ->
            val message = stringIn(locale)
            assertTrue(
                message.contains(token),
                "$locale promises a retry that only re-typing the password cannot deliver: " +
                    "\"$message\"",
            )
        }
    }

    @Test
    fun `the export message is deliberately left alone`() {
        // Control. The same wording is correct on the export form — nothing is picked there, the
        // password well is the only input, and the button re-arms as soon as it is refilled. This
        // change is about the one place where the promise was false.
        val export = File("src/main/res/values/strings.xml").readText()
            .substringAfter("<string name=\"backup_error_export_wrong_password\">")
            .substringBefore("</string>")
        assertTrue(
            export.contains("Enter it again"),
            "found: $export",
        )
    }
}
