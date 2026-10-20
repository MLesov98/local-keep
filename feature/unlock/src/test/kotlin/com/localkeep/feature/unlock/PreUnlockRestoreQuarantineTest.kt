package com.localkeep.feature.unlock

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The quarantine directory this module writes to is the one every backup path excludes.
 *
 * `BackupInclusionSetTest` in `:app` already fails when the two XML rule files and
 * `LocalKeepBackupAgent.EXCLUDED_PATHS` disagree with *each other*. What it cannot know is which
 * directory name the restore flow actually passes to `VaultLoader.quarantineContainers` — that
 * lives here, as a parameter, precisely because the module owning the XML has to name it. So this
 * is the half of the agreement that has no other home.
 *
 * The copy semantics themselves (all three containers, copy rather than move, never overwrite an
 * existing copy, throw rather than half-succeed) moved into `:core:vault` with
 * `VaultLoader.quarantineContainers` and are proven there; `PreUnlockRestoreNoWriteTest`
 * (instrumented) proves this module's ordering — quarantine first, replacement second, and neither
 * without a proven password.
 */
class PreUnlockRestoreQuarantineTest {

    @Test
    fun theQuarantineDirectoryIsExcludedFromEveryBackupPath() {
        val rules = File("../../app/src/main/res/xml/data_extraction_rules.xml").readText()
        val legacy = File("../../app/src/main/res/xml/full_backup_content.xml").readText()
        val agent = File("../../app/src/main/kotlin/com/localkeep/app/LocalKeepBackupAgent.kt").readText()
        val dir = PreUnlockRestore.QUARANTINE_DIR

        assertEquals(
            2,
            Regex("""<exclude domain="file" path="$dir/"\s*/>""").findAll(rules).count(),
            "both cloud-backup and device-transfer must exclude $dir/",
        )
        assertTrue("""<exclude domain="file" path="$dir/"/>""" in legacy, "$dir/ missing from full_backup_content")
        assertTrue(""""$dir/",""" in agent, "$dir/ missing from LocalKeepBackupAgent.EXCLUDED_PATHS")
    }
}
