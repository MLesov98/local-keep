package com.localkeep.core.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trusted-apps management surface (UI/UX spec §10.1) is only as trustworthy as the grouping
 * that feeds it: a grant it fails to show is a grant the user cannot revoke. These pin the pure
 * parse/group step; the DataStore read around it is thin glue and needs a device.
 */
class TrustedAppGroupingTest {

    private fun group(
        pairs: Set<String>,
        pinnedPackages: Set<String> = emptySet(),
        label: (String) -> String? = { null },
    ) = AppSignatureCache.groupPairs(pairs, pinnedPackages, label)

    @Test
    fun `pairs are grouped by app with sorted distinct domains`() {
        val apps = group(
            setOf("com.example.browser|example.com", "com.example.browser|a.com"),
        )
        assertEquals(1, apps.size)
        assertEquals("com.example.browser", apps.single().packageName)
        assertEquals(listOf("a.com", "example.com"), apps.single().domains)
    }

    @Test
    fun `an installed label is used and apps sort by it`() {
        val apps = group(
            setOf("com.z.app|z.com", "com.a.app|a.com"),
            label = { pkg -> if (pkg == "com.z.app") "Alpha" else "Zebra" },
        )
        assertEquals(listOf("Alpha", "Zebra"), apps.map { it.label })
    }

    /**
     * The label sort used to be `it.label.lowercase()`, a code-point sort — 'é' (U+00E9) sorts past
     * every unaccented ASCII letter including 'z', so "École..." landed after "Zoo..." instead of
     * alphabetically among the E's, the exact defect `DisplayNameCollation.kt` documents
     * `sortedByDisplayName` as fixing (`café` unaffected, `école` sorts past `zoo`). Trusted Apps
     * (Settings > autofill trust management) must order the same way every other name list in the
     * app already does.
     */
    @Test
    fun `accented labels sort alphabetically, not past the unaccented alphabet`() {
        val apps = group(
            setOf("com.ecole.app|ecole.com", "com.zoo.app|zoo.com"),
            label = { pkg -> if (pkg == "com.ecole.app") "École du Web" else "Zoo Tycoon" },
        )
        assertEquals(listOf("École du Web", "Zoo Tycoon"), apps.map { it.label })
    }

    @Test
    fun `an uninstalled app falls back to its package name and stays removable`() {
        val apps = group(setOf("com.gone.app|gone.com"), label = { null })
        assertEquals("com.gone.app", apps.single().label)
        assertEquals(listOf("gone.com"), apps.single().domains)
    }

    @Test
    fun `malformed and empty entries never become opaque rows`() {
        val apps = group(setOf("no-separator", "|orphan-domain", "com.ok.app|"))
        // "no-separator": no '|'. "|orphan-domain": empty package (sep at 0). "com.ok.app|": empty
        // domain filtered — the app survives but with no blank line under it.
        assertEquals(listOf("com.ok.app"), apps.map { it.packageName })
        assertTrue(apps.single().domains.isEmpty())
    }

    /**
     * A native app has no web domain, so filling a login in one pins its signing certificate and
     * stores no `pkg|domain` pair. Grouping the pairs alone gave it no row, [AppSignatureCache
     * .forgetApp] is reachable only from a row, and so a pinned identity could not be reset from
     * anywhere in the app — which is also why `trusted_apps_no_sites` was unreachable dead copy
     * (release sweep 2026-09-20, 3B-F5).
     */
    @Test
    fun `an app with a pinned certificate and no domain grant is still a row`() {
        val apps = group(pairs = emptySet(), pinnedPackages = setOf("com.bank.native"))
        assertEquals(listOf("com.bank.native"), apps.map { it.packageName })
        assertTrue(
            "a pinned app with no grant must render the no-sites line, not an invented domain",
            apps.single().domains.isEmpty(),
        )
    }

    @Test
    fun `a pinned app that also has grants is one row, not two`() {
        val apps = group(
            pairs = setOf("com.example.browser|example.com"),
            pinnedPackages = setOf("com.example.browser"),
        )
        assertEquals(1, apps.size)
        assertEquals(listOf("example.com"), apps.single().domains)
    }
}
