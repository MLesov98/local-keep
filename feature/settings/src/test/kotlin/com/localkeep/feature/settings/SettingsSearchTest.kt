package com.localkeep.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {

    private fun entry(
        title: String,
        keywords: String = "",
        summary: String = "",
    ) = SettingsEntry.Expandable(title, summary, keywords) {}

    private val groups = listOf(
        SettingsGroup(
            "Security",
            listOf(
                entry("Auto-lock", "timeout idle background grace"),
                entry("Biometric unlock", "fingerprint face"),
                entry("Duress vault", "decoy coercion"),
            ),
        ),
        SettingsGroup(
            "Appearance",
            listOf(entry("Theme", "dark light mode")),
        ),
    )

    @Test
    fun `empty query returns everything untouched`() {
        assertEquals(groups, searchSettings(groups, "   "))
    }

    @Test
    fun `matches on keywords the title never mentions`() {
        // The whole point: nobody searching for a decoy vault types "duress".
        val hits = searchSettings(groups, "coercion")
        assertEquals(listOf("Security"), hits.map { it.title })
        assertEquals(listOf("Duress vault"), hits.single().entries.map { it.title })
    }

    @Test
    fun `filters within a group rather than returning the whole section`() {
        val hits = searchSettings(groups, "fingerprint")
        assertEquals(1, hits.single().entries.size)
    }

    @Test
    fun `a group with no surviving entry disappears entirely`() {
        val hits = searchSettings(groups, "dark")
        assertEquals(listOf("Appearance"), hits.map { it.title })
    }

    @Test
    fun `multiple terms narrow instead of widening`() {
        // "lock" alone hits both Auto-lock and Biometric unlock; adding "idle" must not add more.
        val broad = searchSettings(groups, "lock").single().entries
        val narrow = searchSettings(groups, "lock idle").single().entries
        assertTrue(broad.size > narrow.size, "expected 'lock idle' to narrow, got ${narrow.size}")
        assertEquals(listOf("Auto-lock"), narrow.map { it.title })
    }

    @Test
    fun `group name is searchable so section names still work`() {
        assertEquals(3, searchSettings(groups, "security").single().entries.size)
    }

    @Test
    fun `search is case-insensitive`() {
        assertEquals(
            searchSettings(groups, "biometric").single().entries.map { it.title },
            searchSettings(groups, "BioMetric").single().entries.map { it.title },
        )
    }

    @Test
    fun `the summary line is searchable too`() {
        // Rows collapsed to a title plus a summary, so the summary is now the only place some of
        // the wording lives — "Argon2id" and "Always on" appear nowhere else. A search that read
        // titles and keywords only would silently stop finding settings as prose moved into it.
        val withSummary = listOf(
            SettingsGroup(
                "How this works",
                listOf(entry(title = "Encryption", summary = "Argon2id, then AES-256-GCM-SIV")),
            ),
        )
        assertEquals(
            listOf("Encryption"),
            searchSettings(withSummary, "argon2id").single().entries.map { it.title },
        )
    }

    @Test
    fun `every entry kind is searchable, not just the expandable one`() {
        // Action and Custom rows are the ones with no body to read — a switch, a version number.
        // If search only understood Expandable, "cloud backup" would find nothing at all.
        val mixed = listOf(
            SettingsGroup(
                "Vault",
                listOf(
                    SettingsEntry.Action(
                        title = "Backup & export",
                        summary = "Save an encrypted copy",
                        keywords = "archive",
                        onClick = {},
                    ),
                    SettingsEntry.Custom(
                        title = "Cloud backup",
                        summary = "Keep the vault out of Auto Backup",
                        keywords = "drive",
                    ) {},
                ),
            ),
        )
        assertEquals(1, searchSettings(mixed, "archive").single().entries.size)
        assertEquals(1, searchSettings(mixed, "drive").single().entries.size)
    }

    @Test
    fun `no match yields an empty list rather than everything`() {
        // Failing open here would show the full settings list and read as "your search worked".
        assertTrue(searchSettings(groups, "bluetooth").isEmpty())
    }
}
