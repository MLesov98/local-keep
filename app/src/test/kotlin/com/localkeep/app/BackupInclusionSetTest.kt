package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The two descriptions of what leaves this device must not disagree.
 *
 * `LocalKeepBackupAgent` takes over from the platform whenever "Disable cloud backup" is on, and
 * from that moment the XML rules govern nothing — the agent's own enumeration decides what a
 * privacy-conscious user's backup contains. It used to add exactly one file while its comment
 * claimed to be "the XML rules' inclusion set MINUS vault.enc", so several persisted files quietly
 * fell out of backup for that cohort, and any file a future version wrote would have joined them
 * with nothing in the build noticing.
 *
 * This is the guard against the drift, in both directions: adding an `<exclude>` to the rules
 * without adding it to the agent fails here, and so does the reverse.
 *
 * Parsed with a regex rather than a DOM parser on purpose — the repo's `checkNoJvmOnlyXmlApis`
 * guard exists because a JVM-only XML API passed every unit test and failed on every device, and a
 * test that reaches for one of those, even in test-only code, muddies what that guard means.
 */
class BackupInclusionSetTest {

    private val cloudRules = File("src/main/res/xml/data_extraction_rules.xml")
    private val legacyRules = File("src/main/res/xml/full_backup_content.xml")

    /** Every `<exclude domain="file" path="…"/>` in `xml`, with a directory's trailing slash kept. */
    private fun fileExcludes(xml: String): Set<String> =
        Regex("""<exclude\s+domain="file"\s+path="([^"]+)"\s*/>""")
            .findAll(xml)
            .map { it.groupValues[1] }
            .toSet()

    /** Every `<exclude domain="sharedpref" path="…"/>` in `xml`. */
    private fun sharedPrefExcludes(xml: String): Set<String> =
        Regex("""<exclude\s+domain="sharedpref"\s+path="([^"]+)"\s*/>""")
            .findAll(xml)
            .map { it.groupValues[1] }
            .toSet()

    /**
     * The sharedpref half of the same drift guard: `unlock_throttle.xml` is excluded in all three
     * places or in none. Added 2026-09-16 with the persisted unlock backoff; before it the only
     * `<include domain="sharedpref">` had no excludes at all, so nothing here looked at that domain.
     */
    @Test
    fun `the agent excludes exactly the shared prefs every rules block excludes`() {
        val cloud = read(cloudRules)
        assertEquals(LocalKeepBackupAgent.EXCLUDED_SHARED_PREFS, sharedPrefExcludes(block(cloud, "cloud-backup")))
        assertEquals(LocalKeepBackupAgent.EXCLUDED_SHARED_PREFS, sharedPrefExcludes(block(cloud, "device-transfer")))
        assertEquals(LocalKeepBackupAgent.EXCLUDED_SHARED_PREFS, sharedPrefExcludes(read(legacyRules)))
        assertTrue("unlock_throttle.xml" in LocalKeepBackupAgent.EXCLUDED_SHARED_PREFS)
    }

    private fun read(xml: File): String {
        assertTrue(xml.exists(), "missing ${xml.path} — the backup rules are not optional")
        return xml.readText()
    }

    /**
     * One `<cloud-backup>`/`<device-transfer>` element's own text.
     *
     * The scoping is the point. This used to regex the whole of `data_extraction_rules.xml`, which
     * collapsed its two blocks into a single union set — so an `<exclude>` added to only one of
     * them compared equal to the agent's list and to the legacy file, and the *other* backup path
     * stayed open with every assertion here green. Found while adding `device_keys.bin.tmp`, which
     * is exactly the shape of edit that slips through: one file, four places, one of them easy to
     * miss.
     */
    private fun block(xml: String, name: String): String {
        val open = xml.indexOf("<$name>")
        assertTrue(open >= 0, "no <$name> element — has data_extraction_rules.xml been restructured?")
        val close = xml.indexOf("</$name>", open)
        assertTrue(close >= 0, "unterminated <$name> element in data_extraction_rules.xml")
        return xml.substring(open, close)
    }

    @Test
    fun `the agent excludes exactly what the cloud-backup rules exclude`() {
        assertEquals(fileExcludes(block(read(cloudRules), "cloud-backup")), LocalKeepBackupAgent.EXCLUDED_PATHS)
    }

    /**
     * The device-to-device transfer set is a second, independently editable copy of the same policy
     * inside the same file — and `onFullBackup` is the same method for both transports, so a file
     * excluded from one and not the other still leaves the device.
     */
    @Test
    fun `the device-transfer rules say the same thing as the cloud-backup rules`() {
        val text = read(cloudRules)
        assertEquals(fileExcludes(block(text, "cloud-backup")), fileExcludes(block(text, "device-transfer")))
    }

    /**
     * Pre-Android-12 devices read the legacy file instead, and it is a separate copy of the same
     * policy — so it can rot on its own. Sweep 3 had to edit both to keep the DataStore exclusion
     * in step; this asserts nobody has to remember that again.
     */
    @Test
    fun `the legacy full-backup rules say the same thing as the modern ones`() {
        assertEquals(fileExcludes(block(read(cloudRules), "cloud-backup")), fileExcludes(read(legacyRules)))
    }

    /**
     * The one file the toggle is *about*. It is deliberately absent from the XML excludes — a
     * toggle-off user's vault is meant to be backed up — so the agent has to carry it separately,
     * and a rule change that started excluding it outright would make the whole agent pointless.
     */
    @Test
    fun `the vault is the agent's own exclusion, not one of the rules'`() {
        assertTrue(LocalKeepBackupAgent.VAULT_FILE !in fileExcludes(read(cloudRules)))
        assertTrue(LocalKeepBackupAgent.VAULT_FILE !in LocalKeepBackupAgent.EXCLUDED_PATHS)
    }

    /**
     * `StaleContainerSweeper` re-keys `vault.enc.bak` in place by writing
     * `vault.enc.bak.sweep.tmp` beside it in `filesDir`, fsyncing, then renaming over the original.
     * Process death in that one window leaves the temp behind holding a *complete* container — the
     * previous vault generation, sealed under the user's current master password — and nothing ever
     * removes it: the sweeper's own cleanup pass scans only `backups/`, and its candidate list never
     * re-lists a `.sweep.tmp`. Naming only `vault.enc.bak` therefore left the withholding one
     * filename suffix wide, the same gap `device_keys.bin.tmp` and `dek-rotation.journal.tmp` were
     * already closed for. Android matches `<exclude>` paths by canonical path, not string prefix, so
     * `vault.enc.bak` does not cover it in the toggle-on path either.
     *
     * Asserted at all four sites directly rather than leaning on the equality tests above, because
     * this is precisely the shape of edit that slips through: one name, four places.
     */
    @Test
    fun `the sweeper's atomic-write sibling of the vault backup is withheld everywhere`() {
        val sweeperTemp = "vault.enc.bak.sweep.tmp"
        val cloud = read(cloudRules)
        assertTrue(sweeperTemp in LocalKeepBackupAgent.EXCLUDED_PATHS, "the agent still uploads $sweeperTemp")
        assertTrue(
            sweeperTemp in fileExcludes(block(cloud, "cloud-backup")),
            "cloud-backup still uploads $sweeperTemp",
        )
        assertTrue(
            sweeperTemp in fileExcludes(block(cloud, "device-transfer")),
            "device-transfer still uploads $sweeperTemp",
        )
        assertTrue(sweeperTemp in fileExcludes(read(legacyRules)), "the legacy rules still upload $sweeperTemp")
    }

    /**
     * Both rule files must keep including everything else, or the agent's "the XML set minus the
     * vault" premise stops holding — an allowlist that stopped being an allowlist would leave the
     * agent backing up strictly more than the platform does.
     */
    @Test
    fun `both rule files still include all of filesDir and all shared prefs`() {
        listOf(cloudRules, legacyRules).forEach { xml ->
            val text = xml.readText()
            assertTrue(
                """<include domain="file" path="."/>""" in text,
                "${xml.name} no longer includes filesDir wholesale",
            )
            assertTrue(
                """<include domain="sharedpref" path="."/>""" in text,
                "${xml.name} no longer includes shared prefs",
            )
        }
    }

    /**
     * No source file may still say backup is off, or that Phase 15 is coming.
     *
     * `DeviceKeyStore`'s class doc claimed "app backup is disabled until Phase 15 installs explicit
     * rules excluding this file" for as long as the rules it was waiting for had been shipped — the
     * single most dangerous sentence to leave standing near this machinery, because the safety of
     * every file in `filesDir` now rests entirely on being named in the exclusion set, and a
     * maintainer who believes backup is off has no reason to add the next secret to it. The tests
     * above catch a name added to one of the three places and not the others; nothing catches a
     * name added to none, so the comment doing the misleading is the guardable part.
     *
     * `BackupPreferences` is deliberately absent from the scan: it quotes the historical plan text
     * while describing a deviation from it, and the quotation is the rationale.
     */
    @Test
    fun `no source file still claims backup is disabled or pending`() {
        val stale = listOf("backup is disabled", "Phase 15 installs", "lands in Phase 15")
        val offenders = mutableListOf<String>()
        for (path in BACKUP_COMMENT_SITES) {
            val src = File("..", path)
            assertTrue(src.isFile, "$path not found — fix the path, do not drop the entry")
            src.readLines().forEachIndexed { i, line ->
                stale.filter { it in line }.forEach { claim ->
                    offenders += "$path:${i + 1} still says “$claim” — ${line.trim()}"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            fail(
                "Auto Backup and device transfer are ON (AndroidManifest: allowBackup=\"true\", " +
                    "fullBackupOnly, backupAgent, both rule files). What withholds a file is the " +
                    "exclusion set, not backup being off. Correct the comment:\n" +
                    offenders.joinToString("\n"),
            )
        }
    }

    private companion object {
        /** Repo-relative, resolved against `..` because this module's tests run from `app/`. */
        val BACKUP_COMMENT_SITES = listOf(
            "core/vault/src/main/kotlin/com/localkeep/core/vault/DeviceKeyStore.kt",
            "feature/settings/src/main/kotlin/com/localkeep/feature/settings/SettingsPreferences.kt",
        )
    }
}
