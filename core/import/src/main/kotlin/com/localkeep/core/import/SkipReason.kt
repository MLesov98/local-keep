package com.localkeep.core.import

/**
 * Why a single source entry did not become a vault item — grouped and counted by the import
 * wizard's preview ("Bank Account (2); HOTP code (1)").
 *
 * Carries no English text, for the same reason [ImportFailureReason] does not: `core/import` is a
 * pure-Kotlin/JVM leaf module and cannot hold string resources. `feature/import` resolves every
 * case to a `@StringRes` — see `ImportWizardScreen.SkipReason.label()`.
 */
sealed interface SkipReason {
    /**
     * A Bitwarden item whose declared type (Login/Card/Identity) had no corresponding sub-object in
     * the export's own JSON — the file was internally inconsistent for this one entry.
     *
     * Also, since 2026-09-13, an Aegis entry whose `secret` is empty or not readable as Base32 —
     * the same shape of defect (the entry names something that is not there), and the reason it
     * moved here is that it used to borrow [UnsupportedTotpSettings], which renders the algorithm,
     * digit count and period and so blamed SHA1/6/30 for a missing secret (4-3).
     */
    data object IncompleteEntry : SkipReason

    /** Bitwarden type 5: an SSH key. LocalKeep has no item type for one. */
    data object SshKey : SkipReason

    /**
     * A Bitwarden item type this build predates. [type] is Bitwarden's own integer type code, shown
     * as-is — a debugging aid, not a word, so it needs no translation.
     */
    data class UnsupportedBitwardenType(val type: Int) : SkipReason

    /**
     * An Aegis entry whose own `type` field is not "totp" (HOTP, Steam, …) — codes this app's
     * generator cannot compute. [codeType] is Aegis's own type string, upper-cased — a format
     * identifier rather than prose, so it is passed through rather than translated.
     */
    data class UnsupportedAegisType(val codeType: String) : SkipReason

    /**
     * A "totp"-typed Aegis entry whose algorithm, digit count, or period this app cannot compute a
     * code for. The three values are shown as-is: technical parameters, not prose.
     */
    data class UnsupportedTotpSettings(val algorithm: String, val digits: Int, val periodSec: Int) : SkipReason

    /** A 1Password item filed in the Archive vault — the user's own data, just not active. */
    data object OnePasswordArchived : SkipReason

    /**
     * An entry sitting in the source app's deleted items — today only KeePass's Recycle Bin.
     *
     * Distinct from [OnePasswordArchived], which was the nearest existing case and renders
     * *Archived item*: archiving is something a user does to keep something out of the way, and
     * deleting is not. Importing these as live entries made everything the user had thrown away
     * searchable, autofill-eligible and counted by the health dashboard again — and dropping them
     * without saying so would be the same defect facing the other way, which is why this reason
     * exists rather than a silent filter.
     */
    data object SourceRecycleBin : SkipReason

    /**
     * A 1Password category this build has no item type for (Bank Account, Server, …).
     * [categoryUuid] is 1Password's own category id; `feature/import` maps the ones it recognises to
     * a name and falls back to a generic label for the rest.
     */
    data class UnsupportedOnePasswordCategory(val categoryUuid: String) : SkipReason

    /**
     * A CSV row with no address, username, or password recognised in any mapped column — not a
     * login by any reading, though it may still carry a title or note the user wrote.
     */
    data object NoLoginFields : SkipReason
}
