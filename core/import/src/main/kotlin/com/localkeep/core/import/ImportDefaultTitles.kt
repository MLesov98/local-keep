package com.localkeep.core.import

/**
 * The titles importers fall back to when a source entry has no title of its own — "Untitled item",
 * "Imported item", and so on.
 *
 * Different in kind from [ImportFailureReason] and [SkipReason]: those are resolved fresh every
 * time the wizard renders (a `stringResource` call at composition), but a default title becomes
 * part of a persisted `VaultItem` at the moment an importer runs, so there is no later render to
 * resolve it at. `feature/import` therefore resolves these once, via `Context.getString` in
 * `ImportWizardViewModel` — a narrow, deliberate exception to the "ViewModels never hold display
 * strings" rule: the value here is content being written into the vault, not UI state
 * a composable will read back.
 *
 * The field defaults are the exact English literals every importer used to hardcode. They exist so
 * that every existing test — which constructs an importer and calls `parse` directly, never
 * supplying this parameter — keeps compiling and passing unchanged; they are not a second, informal
 * translation channel. The production path (`ImportWizardViewModel`) always supplies real,
 * resource-resolved values, so these defaults are never reached from a running app.
 */
data class ImportDefaultTitles(
    val untitledItem: String = "Untitled item",
    val importedItem: String = "Imported item",
    val untitledCode: String = "Untitled code",
    val importedCode: String = "Imported 2FA code",
    val untitledRow: String = "Untitled row",
    val untitledFolder: String = "Imported",
    /** The custom-field name a CSV row's unparseable TOTP column is rescued under — shown in the
     *  item editor exactly like any other custom field name, so it needs the same treatment. */
    val rescuedTotpFieldName: String = "TOTP seed",
    /** The custom-field name an Identity item's source notes are rescued under — Identity has no
     *  notes field of its own, so this is how the text survives rather than being discarded. Reused
     *  since 2026-09-13 for an Aegis entry's own note, which `Totp` has nowhere to put either
     *  (4-2): one name, because to the user it is the same field doing the same job. */
    val identityNotesFieldName: String = "Notes",
)
