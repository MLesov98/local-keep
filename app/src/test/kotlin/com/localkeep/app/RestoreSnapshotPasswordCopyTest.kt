package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A restored on-device snapshot opens with the CURRENT master password, and the copy says so.
 *
 * Every master-password change and Recovery-Kit reset re-keys all rolling snapshots
 * (`StaleContainerSweeper` converts each backup container to the new key), so a snapshot is never
 * sealed under an older secret. The restore confirmation used to tell the user to unlock "with the
 * master password you used on <date>", and the pre-unlock restore screen to enter "the password that
 * opened your vault when that backup was made" — both read as an instruction to type a password the
 * vault no longer accepts, on the one screen a user reaches when they are already locked out.
 *
 * An exported `.vault` file is different and the pre-unlock screen takes both: the file keeps the
 * password (or separate passphrase) it was exported with, so that screen's wording has to be true
 * of both sources at once rather than collapsing to either.
 *
 * Scope is every language because the false premise travelled through the translations unchanged.
 * Both keys are security-copy Tier A; the three-vote review is separate evidence, and this is
 * the part of it that can run on every build.
 */
class RestoreSnapshotPasswordCopyTest {

    private val entry = Regex("""<string\b[^>]*\bname="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    private fun text(module: String, locale: String, key: String): String {
        val file = File(repoRoot(), "$module/src/main/res/$locale/strings.xml")
        assertTrue(file.isFile, "missing catalogue ${file.path}")
        val found = entry.findAll(file.readText()).firstOrNull { it.groupValues[1] == key }
            ?: error("$locale/$key not found in ${file.path}")
        return found.groupValues[2]
    }

    private val locales = listOf("values", "values-fr", "values-de", "values-es", "values-pt")

    /** The past-tense "the password you had then" phrasing, per language. (?U) so \w crosses accents. */
    private val bodyPastPassword = mapOf(
        "values" to Regex("""(?iU)password you used"""),
        "values-fr" to Regex("""(?iU)que vous utilisiez"""),
        "values-de" to Regex("""(?iU)das Sie am \S+ verwendet haben"""),
        "values-es" to Regex("""(?iU)que usaba"""),
        "values-pt" to Regex("""(?iU)que você usava"""),
    )

    private val subtitlePastPassword = mapOf(
        "values" to Regex("""(?iU)when that backup was made"""),
        "values-fr" to Regex("""(?iU)lorsque cette sauvegarde a été faite"""),
        "values-de" to Regex("""(?iU)zum Zeitpunkt dieser Sicherung"""),
        "values-es" to Regex("""(?iU)cuando se hizo esa copia"""),
        "values-pt" to Regex("""(?iU)quando esse backup foi feito"""),
    )

    private val currentMasterPassword = mapOf(
        "values" to "current master password",
        "values-fr" to "mot de passe principal actuel",
        "values-de" to "aktuellen Master-Passwort",
        "values-es" to "contraseña maestra actual",
        "values-pt" to "senha mestra atual",
    )

    @Test
    fun restoreDialogDoesNotPointAtAnOlderPassword() {
        val failures = locales.mapNotNull { locale ->
            val body = text("feature/backup", locale, "backup_restore_dialog_body")
            bodyPastPassword.getValue(locale).find(body)?.let { "$locale: \"${it.value}\" in: $body" }
        }
        assertTrue(failures.isEmpty(), "Restore dialog names a password other than the current one:\n" + failures.joinToString("\n"))
    }

    @Test
    fun restoreDialogNamesTheCurrentMasterPassword() {
        for (locale in locales) {
            val body = text("feature/backup", locale, "backup_restore_dialog_body")
            assertTrue(currentMasterPassword.getValue(locale) in body, "$locale body must say \"${currentMasterPassword.getValue(locale)}\": $body")
        }
    }

    @Test
    fun preUnlockRestoreDoesNotPointAtAnOlderPassword() {
        val failures = locales.mapNotNull { locale ->
            val subtitle = text("feature/unlock", locale, "unlock_restore_subtitle")
            subtitlePastPassword.getValue(locale).find(subtitle)?.let { "$locale: \"${it.value}\" in: $subtitle" }
        }
        assertTrue(failures.isEmpty(), "Pre-unlock restore subtitle ties the password to when the backup was made:\n" + failures.joinToString("\n"))
    }

    @Test
    fun preUnlockRestoreCoversBothSourcesTruthfully() {
        for (locale in locales) {
            val subtitle = text("feature/unlock", locale, "unlock_restore_subtitle")
            assertTrue(currentMasterPassword.getValue(locale) in subtitle, "$locale subtitle must name the current master password for snapshots: $subtitle")
        }
    }

    /** Control, green before and after: the dialog still names the snapshot's date in every language. */
    @Test
    fun restoreDialogStillNamesTheSnapshotDate() {
        for (locale in locales) {
            val body = text("feature/backup", locale, "backup_restore_dialog_body")
            assertTrue(Regex("""%1\${'$'}s""").containsMatchIn(body), "$locale body must carry %1\$s: $body")
        }
    }
}

private fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").isFile }
    ?: error("repo root not found above ${File("").absolutePath}")
