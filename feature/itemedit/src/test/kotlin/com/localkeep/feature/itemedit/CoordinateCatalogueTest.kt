package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the coordinate catalogue says, in every shipped language, read off the resource files.
 *
 * A JVM test rather than an instrumented one because the question is about the *text*, not about
 * rendering, and the text is in five XML files this module owns. Same approach as
 * `TierGClaimsTest`: walk up to the repo root, open the catalogues, and pin the words.
 */
class CoordinateCatalogueTest {

    @Test
    fun `a saved location is named after the category, never called a Reading`() {
        // Reported 2026-09-02: "GPS location says 'Reading, sep 2, 2026'. Is this normal?" It was
        // — and it was bad naming. A coordinate item is a place, and the suggestion opens with the
        // word the drawer and the Add sheet already use for the category (glossary: lieu / Ort
        // / lugar), so a user meets one noun for the thing everywhere it appears.
        val expected = mapOf(
            "values" to "Location, %1\$s",
            "values-fr" to "Lieu, %1\$s",
            "values-de" to "Ort, %1\$s",
            "values-es" to "Lugar, %1\$s",
            "values-pt" to "Lugar, %1\$s",
        )
        expected.forEach { (dir, value) ->
            assertEquals(value, string(dir, "itemedit_title_suggestion_location"), dir)
        }
    }

    @Test
    fun `no coordinate string prints a distance, in any language`() {
        // The 2026-09-03 decision, and the reason this test is phrased as a sweep over the whole
        // coordinate catalogue rather than as three named keys: the previous version of it pinned
        // the two keys that *did* carry a figure, so it could only ever notice the figure leaving
        // the strings it already knew about. A metre figure reintroduced under a new key would have
        // passed it. The claim is now the one the principal actually made — *no* metre figure
        // anywhere in this feature's UI — so it is checked against every string the feature owns.
        //
        // Why the figure went: a receiver's 20 m is an ordinary, good urban fix, and it reads to
        // anyone who does not know that as a failure. "Not so many people know that most GPS
        // coordinates are 20 m etc. […] a user that doesn't know these things sees 20 m and it
        // will cause a bad feeling." The band word carries the whole claim now.
        // An integer placeholder (every metre figure this feature ever printed was "%1\$d"), a
        // literal number followed by a metre unit, or the "≈" the panel's corner readout used.
        // "%1\$s" is deliberately not matched: that is the caption's timestamp.
        val distance = Regex("%\\d\\\$d|\\d+\\s*m\\b|≈")
        LOCALES.forEach { dir ->
            coordinateStrings(dir).forEach { (key, value) ->
                assertTrue(
                    !distance.containsMatchIn(value),
                    "$dir/$key prints a distance: \"$value\"",
                )
            }
        }
    }

    @Test
    fun `every band has a whole sentence of its own, in every language`() {
        // Self-contained sentences rather than a band word substituted into a template. A template
        // cannot survive four target languages at once: the word is the subject of the clause that
        // follows it, so French and Spanish need the following adjective to agree with the noun
        // ("Mesure précise. Prête à enregistrer."), German needs the adjective declined against
        // "Messung", and every language needs it capitalised at the head of the live line but
        // lower-case after the caption's middot. Nine strings per language is the price of not
        // assembling a sentence out of fragments at runtime.
        val bands = listOf("precise", "street", "area")
        LOCALES.forEach { dir ->
            bands.forEach { band ->
                listOf(
                    "itemedit_coordinate_refining_$band",
                    "itemedit_coordinate_settled_$band",
                ).forEach { key ->
                    val value = string(dir, key)
                    assertTrue(value.isNotBlank(), "$dir/$key is blank")
                    assertTrue(!value.contains('%'), "$dir/$key takes an argument; it should be whole")
                }
                // The caption's only argument is the timestamp.
                val caption = string(dir, "itemedit_coordinate_captured_caption_$band")
                assertTrue(caption.contains("%1\$s"), "$dir caption for $band lost its timestamp")
                assertTrue(!caption.contains("%2"), "$dir caption for $band takes a second argument")
            }
            // The three words must actually differ, or the band is not saying anything. Compared on
            // the settled line, where the band word is the only part that varies.
            val settled = bands.map { string(dir, "itemedit_coordinate_settled_$it") }
            assertEquals(3, settled.toSet().size, "$dir: the three bands do not read differently")
        }
    }

    @Test
    fun `the network-only line still prints no figure of any kind`() {
        // Unchanged by the 2026-09-03 rewrite, and kept because it is a different claim: a wifi or
        // cell estimate is not a satellite reading and gets no band word either — LocationFixPolicy
        // explains why that number describes a database's confidence in an access point rather
        // than the phone's distance from it.
        LOCALES.forEach { dir ->
            assertTrue(
                !string(dir, "itemedit_coordinate_network_only").contains("%"),
                "$dir network-only line must not print a figure",
            )
        }
    }

    @Test
    fun `the two capture buttons never share a word, in any language`() {
        // "Cancel" discards and "Keep" keeps. The principal could not tell them apart when both
        // called the same function; now that they do different things, the labels must not be able
        // to drift back into synonyms of each other.
        LOCALES.forEach { dir ->
            val cancel = words(string(dir, "itemedit_coordinate_stop_capture"))
            val keep = words(string(dir, "itemedit_coordinate_cancel_capture"))
            assertTrue((cancel intersect keep).isEmpty(), "$dir: Cancel and Keep share ${cancel intersect keep}")
        }
    }

    /**
     * The keep button names the act, never the quality of what it keeps.
     *
     * There used to be two keep labels — a plain one and "Keep the rough reading" below the PRECISE
     * edge — so the caveat lived in the verb. That collapsed on 2026-09-03, when the status line
     * above the button started naming the band in plain words: a reading the panel had just called
     * "street level" was called "the rough reading" by the control directly beneath it. One screen
     * cannot call one reading two contradictory things, and the principal's instruction was that a
     * perfectly ordinary reading must stop being described in words that read as a failure.
     *
     * So the quality belongs to the status line, which states it once and states it across three
     * bands; the button states the action, which is the same action whatever the band. This test is
     * what stops a future edit reintroducing a judgement into the label — in any of the five
     * languages, where a translator working from the button alone would have no way to know.
     */
    @Test
    fun `the keep button passes no judgement on the reading, in any language`() {
        val judgements = mapOf(
            "values" to listOf("rough", "poor", "bad", "weak", "inaccurate", "imprecise"),
            "values-fr" to listOf("approximative", "grossière", "imprécise", "mauvaise", "médiocre"),
            "values-de" to listOf("ungefähre", "ungenaue", "grobe", "schlechte"),
            "values-es" to listOf("aproximada", "imprecisa", "mala", "burda"),
            "values-pt" to listOf("aproximada", "imprecisa", "ruim", "grosseira"),
        )
        LOCALES.forEach { dir ->
            val keep = string(dir, "itemedit_coordinate_cancel_capture").lowercase()
            judgements.getValue(dir).forEach { word ->
                assertTrue(
                    word !in keep,
                    "$dir: the keep button calls the reading \"$word\" — the status line owns the " +
                        "quality, the button owns the action",
                )
            }
        }
    }

    /** The retired key stays retired: its absence is the fix, so a re-added one must fail here. */
    @Test
    fun `there is no second keep label to contradict the first`() {
        LOCALES.forEach { dir ->
            assertTrue(
                coordinateStrings(dir).keys.none { it == "itemedit_coordinate_keep_rough_button" },
                "$dir: the band-specific keep label is back — see the test above for why it went",
            )
        }
    }

    private fun words(value: String): Set<String> =
        value.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length > 2 }.toSet()

    /**
     * Every `itemedit_coordinate_*` string in one catalogue, as key to value.
     *
     * Deliberately discovered from the file rather than listed here: a test that enumerates the
     * keys it checks can only police the strings someone remembered to add to it, and the claim
     * being made ("no metre figure anywhere in this feature") is about all of them.
     */
    private fun coordinateStrings(dir: String): Map<String, String> {
        val file = File(REPO, "feature/itemedit/src/main/res/$dir/strings.xml")
        assertTrue(file.isFile, "missing catalogue ${file.path}")
        return Regex("<string name=\"(itemedit_coordinate_[a-z0-9_]+)\">(.*?)</string>")
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
            .also { assertTrue(it.isNotEmpty(), "no coordinate strings found in $dir") }
    }

    private fun string(dir: String, key: String): String {
        val file = File(REPO, "feature/itemedit/src/main/res/$dir/strings.xml")
        assertTrue(file.isFile, "missing catalogue ${file.path}")
        val match = Regex("<string name=\"$key\">(.*?)</string>").find(file.readText())
            ?: error("$key missing from $dir/strings.xml")
        return match.groupValues[1]
    }

    private companion object {
        val LOCALES = listOf("values", "values-fr", "values-de", "values-es", "values-pt")
        val REPO: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repo root not found above ${File("").absolutePath}")
    }
}
