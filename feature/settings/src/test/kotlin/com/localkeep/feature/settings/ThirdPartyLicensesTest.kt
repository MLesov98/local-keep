package com.localkeep.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps the About screen's licence notices honest.
 *
 * A stale attribution list is not a cosmetic bug — it is a false public statement about what the
 * app redistributes.
 *
 * **This used to check the wrong list.** It diffed only the aliases declared in
 * `gradle/libs.versions.toml`, which are the dependencies somebody *chose* — not the ones that
 * actually ship. Four artifacts on the real release runtime classpath had no alias and therefore no
 * way to be flagged, and none of them was attributed:
 * `org.bouncycastle:bcpkix-jdk18on`, `org.bouncycastle:bcutil-jdk18on`,
 * `org.linguafranca.pwdb:KeePassJava2-kdbx` and `com.google.dagger:dagger`. Transitive dependencies
 * are redistributed exactly as thoroughly as direct ones.
 *
 * It now checks both: the catalog (so a *declared* dependency cannot be added silently) and
 * `app/sbom/dependencies.txt` (so a *transitive* one cannot either). The inventory is generated
 * from both shipping flavours' release runtime classpaths and kept fresh by `checkSbomUpToDate`, so
 * this needs no machinery of its own — it just reads a file the build already guarantees.
 */
class ThirdPartyLicensesTest {

    private val catalog = File("../../gradle/libs.versions.toml")

    /** Alias names declared under `[libraries]`. */
    private fun catalogAliases(): List<String> {
        assertTrue(catalog.exists(), "version catalog not found at ${catalog.absolutePath}")
        val lines = catalog.readLines()
        val start = lines.indexOfFirst { it.trim() == "[libraries]" }
        assertTrue(start >= 0, "no [libraries] section in the version catalog")
        return lines.drop(start + 1)
            .takeWhile { !it.trim().startsWith("[") }
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                trimmed.substringBefore('=').trim().ifEmpty { null }
            }
    }

    /** The generated inventory of what actually ships. See `app/build.gradle.kts`. */
    private val inventory = File("../../app/sbom/dependencies.txt")

    /**
     * The project a Maven coordinate belongs to, for attribution purposes.
     *
     * Licences attach to projects, not artifacts, and picking the granularity is the whole design of
     * this check — the two obvious choices both fail:
     *
     *  - **per artifact** flagged ~150 entries, nearly all AndroidX/Compose sub-artifacts of
     *    umbrellas already named in About (`androidx.compose.ui:ui-android` under
     *    `androidx.compose.ui:ui`);
     *  - **per Maven group** still flagged ~26, because AndroidX splits one Apache-2.0 project family
     *    across `androidx.annotation`, `androidx.collection`, `androidx.compose.runtime` and so on.
     *
     * A check that fires dozens of times on a correct About screen is a check somebody deletes. So:
     * `androidx.*` is one family (which is what it is — one project, one licence, one notice), and
     * everything else is identified by its first two namespace segments, which separates
     * `com.google.dagger` from `com.google.android.material` without splitting `org.bouncycastle`.
     */
    private fun attributionKey(coordinates: String): String {
        val group = coordinates.substringBefore(':')
        if (group.startsWith("androidx.") || group == "androidx") return "androidx"
        return group.split('.').take(2).joinToString(".")
    }

    /** The project key for every third-party artifact on a shipping release runtime classpath. */
    private fun shippedProjects(): List<String> {
        assertTrue(inventory.exists(), "dependency inventory not found at ${inventory.absolutePath}")
        return inventory.readLines()
            .filterNot { it.isBlank() || it.startsWith("#") || it.startsWith("local:") }
            .map { attributionKey(it) }
            .distinct()
            .sorted()
    }

    @Test
    fun `every third party that ships has a licence notice`() {
        val attributed = ThirdPartyLicenses.groups
            .flatMap { group -> group.libraries.map { attributionKey(it.coordinates) } }
            .toSet()
        val missing = shippedProjects().filterNot { it in attributed }
        assertTrue(
            missing.isEmpty(),
            "these third parties are on a shipping release runtime classpath with no licence " +
                "notice in About: $missing\n\n" +
                "They redistribute whether or not anyone declared them — a transitive dependency " +
                "is in the APK exactly as much as a direct one. Add each to ThirdPartyLicenses " +
                "with the licence read from the dependency's own POM, never guessed.",
        )
    }

    @Test
    fun `every catalog library is either attributed or declared test-only`() {
        val missing = catalogAliases()
            .filterNot { it in ThirdPartyLicenses.coveredAliases }
            .filterNot { it in ThirdPartyLicenses.testOnlyAliases }
        assertTrue(
            missing.isEmpty(),
            "these dependencies ship with no licence notice in About: $missing — add them to " +
                "ThirdPartyLicenses (with the licence read from the dependency's own POM, not " +
                "guessed) or to testOnlyAliases if they never reach an APK",
        )
    }

    @Test
    fun `no notice refers to a dependency the project no longer uses`() {
        // A notice is stale only if the dependency is in neither place it could legitimately be:
        // the catalog (declared) or the shipped inventory (transitive). Checking the catalog alone
        // was correct while every notice had an alias, and became wrong the moment transitive
        // artifacts were attributed — they have no alias by definition.
        val aliases = catalogAliases().toSet()
        val shipped = shippedProjects().toSet()
        val attributedElsewhere = ThirdPartyLicenses.groups
            .flatMap { group -> group.libraries }
            .filter { attributionKey(it.coordinates) in shipped }
            .map { it.alias }
            .toSet()
        val stale = (ThirdPartyLicenses.coveredAliases + ThirdPartyLicenses.testOnlyAliases)
            .filterNot { it in aliases || it in attributedElsewhere }
        assertTrue(
            stale.isEmpty(),
            "notices name dependencies that are in neither the version catalog nor the shipped " +
                "dependency inventory: $stale",
        )
    }

    @Test
    fun `every group names a licence and a url`() {
        ThirdPartyLicenses.groups.forEach { group ->
            assertTrue(group.license.isNotBlank(), "a licence group has no name")
            assertTrue(group.url.startsWith("http"), "${group.license} has no usable url")
            assertTrue(group.libraries.isNotEmpty(), "${group.license} lists no libraries")
        }
    }

    @Test
    fun `no library is attributed twice`() {
        val all = ThirdPartyLicenses.groups.flatMap { group -> group.libraries.map { it.alias } }
        assertEquals(all.size, all.toSet().size, "a library appears under two licences: $all")
    }
}
