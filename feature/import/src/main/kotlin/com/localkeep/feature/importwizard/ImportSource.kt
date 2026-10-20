package com.localkeep.feature.importwizard

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import com.localkeep.core.import.SourceFormat

/**
 * One instruction, and the kind of thing it asks the user to do.
 *
 * [art] is what the illustration is keyed on. It is a property of the *step*, not of its position in
 * the list, so reordering or rewording a step can never leave it illustrated as something else —
 * which is the failure mode of picking artwork by index.
 *
 * [textRes] rather than a raw String: every instruction here is user-facing and translated, and a
 * resource id is what a composable resolves with `stringResource`.
 */
data class ImportStep(@param:StringRes val textRes: Int, val art: StepArt)

/**
 * The vocabulary the export steps are drawn from (UI/UX spec §6.2, "illustrated export steps").
 *
 * A closed set rather than a bespoke picture per step, and that is the design decision worth
 * recording. Twenty-five hand-drawn scenes of *other apps' screens* would be twenty-five things that
 * go stale the next time Chrome moves a menu, and the user would be comparing our drawing against
 * what is actually in front of them. What actually helps is answering "where am I and what am I
 * looking for" — on a desktop, in a browser, in an overflow menu, at a save dialog — and there are
 * only about a dozen answers across every source here.
 */
enum class StepArt {
    /** Launch the other app on this phone. */
    OPEN_APP,

    /** Do it on a computer — this source can't export from mobile. */
    DESKTOP,

    /** Do it in a browser, not the app. */
    WEB,

    /** Find the ⋯ / ⋮ overflow. */
    MENU,

    /** Go into that app's settings. */
    SETTINGS,

    /** The export action itself. */
    EXPORT,

    /** Pick a specific file format among several offered. */
    CHOOSE_FORMAT,

    /** Confirm with a password, PIN or screen lock. */
    AUTHENTICATE,

    /** Choose where the file lands. */
    SAVE_FILE,

    /** Get the file onto this device from somewhere else. */
    TRANSFER,

    /** Locate an existing file on disk. */
    FIND_FILE,

    /** Something about the shape of the data itself — columns, header rows. */
    DATA_SHAPE,
}

/**
 * A migration source the user can pick from the grid (UI/UX spec §6.1), with the export steps for
 * that specific app.
 *
 * **On "automatic":** neither Android nor iOS lets one app read or delete another app's vault —
 * the same sandbox that protects LocalKeep. So there is no way to pull passwords out of Chrome or
 * Bitwarden directly, and this wizard is the closest honest approximation: we tell the user exactly
 * where the export button is, then do everything after that for them. [caveats] exist so the copy
 * is explicit wherever the source itself can't give us something, rather than letting the user
 * discover the gap after they've deleted the original.
 */
enum class ImportSource(
    @param:StringRes val labelRes: Int,
    /** File types the picker should offer. Kept broad — exports are frequently mislabelled. */
    val mimeTypes: Array<String>,
    val steps: List<ImportStep>,
    /** Resource ids rather than raw Strings — see [ImportStep.textRes]. */
    val caveats: List<Int> = emptyList(),
    /** True when the source file is itself encrypted and needs a password to open. */
    val needsPassword: Boolean = false,
    /**
     * Non-null when this source cannot currently work on Android, explaining why. The tile stays
     * visible — hiding it would just make users hunt for a format we do intend to support — but
     * selecting it says so plainly instead of failing later with a misleading error.
     */
    val unsupportedReason: String? = null,
    /**
     * Packages to offer to launch, most-preferred first, so the user can get to the export screen
     * without leaving the wizard to go hunting on their home screen.
     *
     * This is the only part of the export we can actually do for them, and it needs a `<queries>`
     * entry per package in the manifest — package *visibility*, not a permission, so `foss` stays
     * free of file-access permissions. The list is explicit for the same reason: a broad intent filter would let the
     * app enumerate every password manager installed, which is not something an import wizard needs
     * to know.
     */
    val appPackages: List<String> = emptyList(),
    /**
     * The page where this source's export lives, for the ones whose mobile app cannot export at all.
     *
     * Opening it hands a URL to whatever browser the user already has; LocalKeep makes no request
     * itself, and nothing from the vault is in the URL.
     */
    val webExportUrl: String? = null,
    /**
     * Set when the export genuinely cannot be done on this phone, so the wizard can say so once, at
     * the top, instead of letting the user read four steps before discovering step one needs a Mac.
     */
    val requiresComputer: Boolean = false,
) {
    CHROME(
        labelRes = R.string.import_source_label_chrome,
        mimeTypes = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"),
        steps = listOf(
            ImportStep(R.string.import_source_chrome_step_1, StepArt.MENU),
            ImportStep(R.string.import_source_chrome_step_2, StepArt.SETTINGS),
            ImportStep(R.string.import_source_chrome_step_3, StepArt.AUTHENTICATE),
            ImportStep(R.string.import_source_chrome_step_4, StepArt.SAVE_FILE),
        ),
        caveats = listOf(
            R.string.import_source_chrome_caveat_1,
            R.string.import_source_chrome_caveat_2,
        ),
        appPackages = listOf("com.android.chrome", "com.chrome.beta"),
    ),
    APPLE(
        labelRes = R.string.import_source_label_apple,
        mimeTypes = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"),
        steps = listOf(
            ImportStep(R.string.import_source_apple_step_1, StepArt.DESKTOP),
            ImportStep(R.string.import_source_apple_step_2, StepArt.MENU),
            ImportStep(R.string.import_source_apple_step_3, StepArt.TRANSFER),
        ),
        caveats = listOf(
            R.string.import_source_apple_caveat_1,
            R.string.import_source_apple_caveat_2,
        ),
        requiresComputer = true,
    ),
    BITWARDEN(
        labelRes = R.string.import_source_label_bitwarden,
        mimeTypes = arrayOf("application/json", "text/json", "text/plain", "*/*"),
        steps = listOf(
            ImportStep(R.string.import_source_bitwarden_step_1, StepArt.WEB),
            ImportStep(R.string.import_source_bitwarden_step_2, StepArt.EXPORT),
            ImportStep(R.string.import_source_bitwarden_step_3, StepArt.CHOOSE_FORMAT),
            ImportStep(R.string.import_source_bitwarden_step_4, StepArt.AUTHENTICATE),
        ),
        caveats = listOf(
            R.string.import_source_bitwarden_caveat_1,
            R.string.import_source_bitwarden_caveat_2,
            // An organisation export's `collections` are carried as tags rather than folders —
            // an item can be in several at once, which a single folder cannot record. Said here
            // because the item arrives with labels the user never typed, and nothing else on
            // screen explains where they came from.
            R.string.import_source_bitwarden_caveat_3,
            // Bitwarden's JSON export does carry passkeys, as `login.fido2Credentials` (verified
            // against bitwarden/clients' own export models, 2026-09-13), and this build drops them
            // — they cannot be mapped in `:core:import`, which has no vault key to seal a private
            // key with. Silence here would have read as "everything came across".
            R.string.import_source_bitwarden_caveat_4,
        ),
        webExportUrl = "https://vault.bitwarden.com/#/tools/export",
    ),
    ONEPASSWORD(
        labelRes = R.string.import_source_label_onepassword,
        mimeTypes = arrayOf("application/zip", "application/octet-stream", "*/*"),
        steps = listOf(
            ImportStep(R.string.import_source_onepassword_step_1, StepArt.DESKTOP),
            ImportStep(R.string.import_source_onepassword_step_2, StepArt.EXPORT),
            ImportStep(R.string.import_source_onepassword_step_3, StepArt.CHOOSE_FORMAT),
            ImportStep(R.string.import_source_onepassword_step_4, StepArt.AUTHENTICATE),
        ),
        caveats = listOf(
            R.string.import_source_onepassword_caveat_1,
            // A .1pux archive *does* carry attachments — a `files/` directory beside `export.data`,
            // with each item's `documentAttributes` naming its documentId (1Password's own format
            // documentation, checked 2026-09-13). OnePuxImporter reads neither, so a file attached
            // to an otherwise-mapped Login, Card or Identity is dropped with no skip entry, and the
            // Done step then offers to securely delete the export. The sibling KEEPASS and
            // BITWARDEN caveats have said the same thing about their own formats since 2026-08-30.
            R.string.import_source_onepassword_caveat_2,
        ),
        requiresComputer = true,
    ),
    KEEPASS(
        labelRes = R.string.import_source_label_keepass,
        mimeTypes = arrayOf("application/octet-stream", "application/x-kdbx", "*/*"),
        steps = listOf(
            ImportStep(R.string.import_source_keepass_step_1, StepArt.FIND_FILE),
            ImportStep(R.string.import_source_keepass_step_2, StepArt.TRANSFER),
            ImportStep(R.string.import_source_keepass_step_3, StepArt.AUTHENTICATE),
        ),
        caveats = listOf(
            R.string.import_source_keepass_caveat_1,
            R.string.import_source_keepass_caveat_2,
            // This used to say the seed was imported but that no screen could show a code from it,
            // and to keep the old authenticator until one could. C4 built that screen — a login
            // carrying a seed now shows its own rolling code — so the warning is no longer true and
            // would talk users out of a feature they have.
            R.string.import_source_keepass_caveat_3,
            // Entry attachments are dropped by KdbxImporter and, until 2026-08-30, nothing said so
            // — while the Done step offers to delete the source database, which may hold the only
            // remaining copy of those files. The importer's own comment claimed these caveats
            // covered it; they did not.
            R.string.import_source_keepass_caveat_4,
        ),
        needsPassword = true,
        // Re-enabled once the StAX dependency was gone. The history is worth keeping: this source
        // was disabled because KeePassJava2's `simple` flavour is backed by simple-xml, which needs
        // javax.xml.stream — an API Android has never shipped — so every parse failed on-device and
        // was reported to the user as a wrong password. :core:import now uses the `dom` flavour,
        // and `checkNoJvmOnlyXmlApis` fails the build if a StAX-referencing artifact comes back.
    ),
    AEGIS(
        labelRes = R.string.import_source_label_aegis,
        mimeTypes = arrayOf("application/json", "text/json", "text/plain", "*/*"),
        steps = listOf(
            ImportStep(R.string.import_source_aegis_step_1, StepArt.SETTINGS),
            ImportStep(R.string.import_source_aegis_step_2, StepArt.CHOOSE_FORMAT),
            ImportStep(R.string.import_source_aegis_step_3, StepArt.SAVE_FILE),
        ),
        caveats = listOf(
            R.string.import_source_aegis_caveat_1,
            R.string.import_source_aegis_caveat_2,
        ),
        appPackages = listOf("com.beemdevelopment.aegis"),
    ),
    KEEPER(
        labelRes = R.string.import_source_label_keeper,
        // Both shapes are read, so both types are offered. Keeper's JSON is the better export and
        // step 3 says so, but a user who already has the CSV should not be sent back for another.
        mimeTypes = arrayOf("application/json", "text/json", "text/csv", "text/plain", "*/*"),
        steps = listOf(
            ImportStep(R.string.import_source_keeper_step_1, StepArt.WEB),
            ImportStep(R.string.import_source_keeper_step_2, StepArt.SETTINGS),
            ImportStep(R.string.import_source_keeper_step_3, StepArt.CHOOSE_FORMAT),
            ImportStep(R.string.import_source_keeper_step_4, StepArt.SAVE_FILE),
        ),
        caveats = listOf(
            // Each of these is a measured limitation of `KeeperImporter`, not boilerplate — see
            // WP-B2's report for what was tested against which fixture.
            R.string.import_source_keeper_caveat_1,
            R.string.import_source_keeper_caveat_2,
            R.string.import_source_keeper_caveat_3,
            R.string.import_source_keeper_caveat_4,
        ),
        appPackages = listOf("com.callpod.android_apps.keeper"),
        webExportUrl = "https://keepersecurity.com/vault/",
    ),
    ENPASS(
        labelRes = R.string.import_source_label_enpass,
        mimeTypes = arrayOf("application/json", "text/json", "text/csv", "text/plain", "*/*"),
        steps = listOf(
            ImportStep(R.string.import_source_enpass_step_1, StepArt.OPEN_APP),
            ImportStep(R.string.import_source_enpass_step_2, StepArt.SETTINGS),
            ImportStep(R.string.import_source_enpass_step_3, StepArt.CHOOSE_FORMAT),
            ImportStep(R.string.import_source_enpass_step_4, StepArt.AUTHENTICATE),
        ),
        caveats = listOf(
            R.string.import_source_enpass_caveat_1,
            // The notes-only CSV really is unrecognised, and deliberately: its rows carry nothing
            // that is Enpass rather than any other two-column file, so claiming it would mean
            // claiming every two-column CSV. Said out loud rather than left to be discovered at
            // the "format not recognised" message.
            R.string.import_source_enpass_caveat_2,
            R.string.import_source_enpass_caveat_3,
        ),
        appPackages = listOf("io.enpass.app"),
    ),
    PROTONPASS(
        labelRes = R.string.import_source_label_protonpass,
        // `application/zip` is offered because that is what Proton Pass hands the user, and a picker
        // that hides the file they actually have reads as "this app cannot do it". Caveat 1 is what
        // tells them to unzip it — the importer reads the JSON inside, not the archive.
        mimeTypes = arrayOf("application/json", "text/json", "application/zip", "text/plain", "*/*"),
        steps = listOf(
            ImportStep(R.string.import_source_protonpass_step_1, StepArt.OPEN_APP),
            ImportStep(R.string.import_source_protonpass_step_2, StepArt.SETTINGS),
            ImportStep(R.string.import_source_protonpass_step_3, StepArt.CHOOSE_FORMAT),
            ImportStep(R.string.import_source_protonpass_step_4, StepArt.FIND_FILE),
        ),
        caveats = listOf(
            R.string.import_source_protonpass_caveat_1,
            R.string.import_source_protonpass_caveat_2,
            R.string.import_source_protonpass_caveat_3,
            R.string.import_source_protonpass_caveat_4,
        ),
        appPackages = listOf("proton.android.pass"),
        webExportUrl = "https://pass.proton.me/",
    ),
    DASHLANE(
        labelRes = R.string.import_source_label_dashlane,
        mimeTypes = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"),
        steps = listOf(
            ImportStep(R.string.import_source_dashlane_step_1, StepArt.DESKTOP),
            ImportStep(R.string.import_source_dashlane_step_2, StepArt.SETTINGS),
            ImportStep(R.string.import_source_dashlane_step_3, StepArt.CHOOSE_FORMAT),
            ImportStep(R.string.import_source_dashlane_step_4, StepArt.TRANSFER),
        ),
        caveats = listOf(
            // First, and it has no equivalent in any other source here: Dashlane writes one file
            // per kind of record, so a user who imports the one they happened to open gets part of
            // their vault and no indication that the rest exists.
            R.string.import_source_dashlane_caveat_1,
            R.string.import_source_dashlane_caveat_2,
            R.string.import_source_dashlane_caveat_3,
        ),
        appPackages = listOf("com.dashlane"),
        webExportUrl = "https://app.dashlane.com/",
        requiresComputer = true,
    ),
    CSV(
        labelRes = R.string.import_source_label_csv,
        mimeTypes = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"),
        steps = listOf(
            ImportStep(R.string.import_source_csv_step_1, StepArt.EXPORT),
            ImportStep(R.string.import_source_csv_step_2, StepArt.SETTINGS),
            ImportStep(R.string.import_source_csv_step_3, StepArt.DATA_SHAPE),
        ),
        caveats = listOf(
            R.string.import_source_csv_caveat_1,
        ),
    ),
}

/**
 * The format this tile's own importer reads, or null for the three tiles that are just "a CSV".
 *
 * Used to name the source in a refusal — `ImportWizardViewModel.refusalFor` — so that a file the
 * chosen tile could not read is answered in the terms of the brand the user picked, rather than the
 * one generic sentence every refusal used to share (1B-F3 / 1A-F5, 2026-09-20).
 *
 * Chrome, Apple and Other/CSV have no format of their own: their exports are read by the generic
 * CSV mapper, which answers `SourceFormat.CSV` — a name no user would recognise as their app.
 */
val ImportSource.sourceFormat: SourceFormat?
    get() = when (this) {
        ImportSource.KEEPASS -> SourceFormat.KDBX
        ImportSource.BITWARDEN -> SourceFormat.BITWARDEN
        ImportSource.ONEPASSWORD -> SourceFormat.ONEPUX
        ImportSource.AEGIS -> SourceFormat.AEGIS
        ImportSource.KEEPER -> SourceFormat.KEEPER
        ImportSource.ENPASS -> SourceFormat.ENPASS
        ImportSource.PROTONPASS -> SourceFormat.PROTONPASS
        ImportSource.DASHLANE -> SourceFormat.DASHLANE
        ImportSource.CHROME, ImportSource.APPLE, ImportSource.CSV -> null
    }

/**
 * A launch intent for the first of [ImportSource.appPackages] that is actually installed, or null.
 *
 * Null is the ordinary case, not a failure: most people importing from Chrome on a phone that has
 * no Chrome are importing a file someone sent them. The caller shows no button at all rather than
 * one that fails on tap.
 *
 * `getLaunchIntentForPackage` returns null for a package this app cannot see, so this depends on
 * the `<queries>` block in the module manifest — without it the result is null for every package
 * and the shortcut silently never appears, which is exactly the kind of failure that looks like a
 * dead feature rather than a missing manifest entry.
 */
fun ImportSource.installedLaunchIntent(context: Context): Intent? =
    appPackages.firstNotNullOfOrNull { pkg ->
        runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
