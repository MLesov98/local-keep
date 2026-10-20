package com.localkeep.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Source- and copy-level guards for the two Hidden-items properties no ViewModel test can observe.
 *
 * **The row is always present and says nothing.** A row that appeared only while something was
 * hidden would announce that something is, and would be missing for the user who has forgotten the
 * reveal phrase. That is a property of how the entry is *declared*, not of any state, so it is
 * checked where it is declared.
 *
 * **A secret typed on this screen never enters saved state.** `rememberSaveable` is written to disk
 * by the system on process death, and a half-typed vault password or reveal phrase does not belong
 * there. No unit test can watch a process die; the shape that causes the defect can be pinned.
 * Same idiom as `SnapshotsRevealTest` in feature/backup, for the same reason.
 */
class HiddenItemsSurfaceTest {

    private val screen: String =
        File("src/main/kotlin/com/localkeep/feature/settings/HiddenItemsScreen.kt").readText()

    private val settingsScreen: String =
        File("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt").readText()

    private val strings: String = File("src/main/res/values/strings.xml").readText()

    /**
     * Source with its comments removed.
     *
     * Both forbidden-token checks below first fired on prose: this codebase documents *why* a
     * decision was taken, so the file that must never call `rememberSaveable` is exactly the file
     * that explains why it does not, and the prompt that excludes `DEVICE_CREDENTIAL` names it to
     * say so. A scan that cannot tell the two apart forces the rationale out of the code, which is
     * the wrong trade on a password manager.
     */
    private fun code(path: String): String = File(path)
        .readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(?<!:)//.*"""), "")

    /**
     * The entry row leads with a type plate, and `LkGroupRow` arranges its children `Start` — no gap
     * — by default. That default is right for the shape every other caller uses (a weighted text
     * column with a trailing chevron, where the weight does the separating) and silently wrong here:
     * the plate shipped touching the title, reported from a phone 2026-08-21 as *"where's the
     * spacing? Look how stacked they are"*.
     *
     * Pinned in source because it is a layout property of a screen this module cannot render, and
     * because the failure mode is invisible in review — nothing in the row's code says "no gap", the
     * gap is simply absent.
     */
    @Test
    fun theEntryRowSeparatesItsTypePlateFromTheTitle() {
        val row = code("src/main/kotlin/com/localkeep/feature/settings/HiddenItemsScreen.kt")
            .substringAfter("private fun HiddenEntryCard(")
            .substringBefore("private fun PeekSheet(")
        assertTrue(
            row.contains("horizontalArrangement = Arrangement.spacedBy(LkSpace."),
            "the leading plate needs a token gap before the title — LkGroupRow's Start default " +
                "puts them flush against each other",
        )
    }

    @Test
    fun theHiddenItemsRowIsDeclaredUnconditionally() {
        val group = Regex(
            """SettingsGroup\(\s*stringResource\(R\.string\.settings_group_privacy\),\s*listOf\(""",
        )
        assertTrue(
            group.containsMatchIn(settingsScreen),
            "the Privacy group must be a plain listOf — a listOfNotNull or an `if` around this row " +
                "makes its presence depend on whether anything is hidden, which is the leak",
        )
        assertTrue(
            Regex("""testTag = HIDDEN_ITEMS_TEST_TAG""").containsMatchIn(settingsScreen),
            "the row carries the language-independent handle the sweep and device pass navigate by",
        )
    }

    @Test
    fun theRowsOwnCopyCarriesNoCountAndNoStatus() {
        val summary = Regex("""<string name="settings_hidden_items_summary">(.*?)</string>""")
            .find(strings)
            ?.groupValues
            ?.get(1)
        assertTrue(summary != null, "the always-present row needs a summary")
        assertFalse(
            summary!!.any(Char::isDigit),
            "a count on the Settings row would leak how much is hidden: $summary",
        )
        // "hidden"/"reveal" name the feature; a *state* word would report the current vault.
        listOf(" are ", " currently", "no items", "nothing").forEach { leak ->
            assertFalse(
                summary.contains(leak, ignoreCase = true),
                "the row must read identically with nothing hidden and with something hidden: $summary",
            )
        }
    }

    @Test
    fun noSecretOnThisScreenEntersSavedState() {
        assertFalse(
            code("src/main/kotlin/com/localkeep/feature/settings/HiddenItemsScreen.kt")
                .contains("rememberSaveable"),
            "a vault password or reveal phrase held in rememberSaveable is written to disk by the " +
                "system on process death",
        )
    }

    @Test
    fun theDuressRenderedCopyNeverNamesTheRealVault() {
        // Every string this screen can show while a decoy session is open. "master"/"real" would
        // tell a coercer that the password being asked for is not the only one there is.
        val keys = Regex("""<string name="(settings_hidden_[a-z_]+)">(.*?)</string>""")
        val offenders = keys.findAll(strings)
            .filter { it.groupValues[2].contains(Regex("""\b(master|real)\b""", RegexOption.IGNORE_CASE)) }
            .map { it.groupValues[1] }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "hidden-items copy is rendered inside a decoy session too and must not name the real " +
                "vault or its master password: $offenders",
        )
    }

    @Test
    fun theScreenStatesWhatConcealmentCostsAndWhatItIsNot() {
        assertTrue(
            screen.contains("R.string.settings_hidden_explainer_health"),
            "the screen must say that health counts move when entries are hidden",
        )
        assertTrue(
            screen.contains("R.string.settings_hidden_explainer_storage"),
            "the screen must say hidden entries remain in the vault and in backups",
        )
        val storage = Regex("""<string name="settings_hidden_explainer_storage">(.*?)</string>""")
            .find(strings)!!
            .groupValues[1]
        assertTrue(
            storage.contains("backup", ignoreCase = true) && storage.contains("vault", ignoreCase = true),
            "the claim has to name both places the data actually stays: $storage",
        )
    }

    @Test
    fun theGateComposesNoEntryContentUntilItPasses() {
        val source = code("src/main/kotlin/com/localkeep/feature/settings/HiddenItemsScreen.kt")
        val gated = Regex(
            """if \(!state\.authorized\) \{\s*AuthGate\(.*?\}\s*else \{\s*HiddenEntries\(""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(
            gated.containsMatchIn(source),
            "entry content must be the else branch of the authorization check — not merely " +
                "covered, blurred or disabled, but never composed",
        )
        // Declaration plus that one call. A second call site is how "never composed" quietly
        // becomes "composed anyway, below the gate": the branch above would still read correctly.
        assertEquals(
            2,
            Regex("""\bHiddenEntries\(""").findAll(source).count(),
            "the authorized body must have exactly one call site, inside the gate's else branch",
        )
    }

    /**
     * "Make visible" is the one action on this card that goes through `HiddenItemsViewModel.mutate`,
     * and `mutate` returns immediately when `isWorking` — after having already cleared `message` and
     * `error`. So a tap taken during the in-flight window produces nothing: no navigation, no
     * banner, no failure. Painting the label faint is not enough on its own; the control has to
     * refuse the press.
     *
     * "Change duration" deliberately keeps no such guard. It only sets local state
     * (`onChangeDuration = { pendingDuration = it }`) to raise the duration sheet, and the mutation
     * runs later from that sheet's `onPick` — so it is genuinely available while something else is
     * in flight. One dimmed action beside one live one is the honest rendering of that difference.
     *
     * Pinned in source because it is a property of how the row is declared, and because the failure
     * it prevents is a *missing* argument — nothing in a rendered row says "this tap will be
     * dropped".
     */
    @Test
    fun theStopHidingActionRefusesTapsWhileAMutationIsInFlight() {
        val actions = code("src/main/kotlin/com/localkeep/feature/settings/HiddenItemsScreen.kt")
            .substringAfter("private fun HiddenEntryCard(")
            .substringBefore("private fun PeekSheet(")
        val stop = Regex(
            // Ends at the call's own trailing-comma close, not at the first `)` — an inner
            // `weight(1f)` would otherwise cut the slice short and let an argument order change
            // decide the verdict.
            """LkTextButton\(\s*text = stringResource\(R\.string\.settings_hidden_stop\),.*?,\s*\n\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(actions)?.value
        assertTrue(stop != null, "the card must still carry the stop-hiding action")
        assertTrue(
            stop!!.contains("enabled = !working"),
            "the stop-hiding button must be disabled while working — mutate() drops the tap with " +
                "no banner on screen, so a live-but-faint button is a press that does nothing: $stop",
        )
    }

    @Test
    fun theBiometricPromptDoesNotAdmitTheDeviceCredential() {
        assertFalse(
            code("src/main/kotlin/com/localkeep/feature/settings/HiddenItemsViewModel.kt")
                .contains("DEVICE_CREDENTIAL"),
            "the device PIN is the secret a coercer is most likely to have watched being typed; " +
                "this gate is BIOMETRIC_STRONG plus the vault password only",
        )
    }
}
