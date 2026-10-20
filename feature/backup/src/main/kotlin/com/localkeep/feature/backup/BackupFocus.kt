package com.localkeep.feature.backup

/**
 * Which section the next [BackupSettingsScreen] should scroll to on arrival.
 *
 * "Rolling local snapshots" is the rollback point for the whole vault, and it was the fifth and
 * last section of a screen that opens on cloud-backup settings — reachable only by knowing it was
 * there and scrolling past four groups of encryption and export options. That is a poor place for
 * the one thing a user goes looking for when something has already gone wrong.
 *
 * The drawer now links to it directly. Rather than build a second screen for the same list, the
 * link opens Backup & Export and asks it to bring the snapshots section into view — the same shape
 * as [com.localkeep.feature.settings.SettingsFocus], which the "Duress / decoy" drawer row uses to
 * land on a row rather than the top of a long list.
 *
 * Held in memory rather than passed as a navigation argument, so it cannot end up serialised into
 * saved instance state, and [take] consumes it so that reaching Backup any other way opens the
 * screen normally.
 */
object BackupFocus {

    /** The rolling-snapshots section. */
    const val SNAPSHOTS = "snapshots"

    /**
     * The "Import a .vault file" section — where the import wizard sends a LocalKeep backup it was
     * handed by mistake (device report C3 finding 1).
     */
    const val IMPORT = "import"

    @Volatile
    private var pending: String? = null

    /** Asks the next Backup screen to open scrolled to [section]. */
    fun request(section: String) {
        pending = section
    }

    /** Returns the requested section and forgets it, or null if there is none. */
    fun take(): String? {
        val taken = pending
        pending = null
        return taken
    }

    fun clear() {
        pending = null
    }
}
