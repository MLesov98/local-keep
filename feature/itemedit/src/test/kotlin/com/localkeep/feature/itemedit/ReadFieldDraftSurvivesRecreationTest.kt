package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The item view screen's inline edit must not lose its draft to an activity recreation.
 *
 * `ReadField` opens edit-in-place on tap and holds three things while it is open: whether it is
 * open (`editing`), the text being typed (`draft`), and whether the field has ever really had focus
 * (`everFocused`). The first two were plain `remember`, so a rotation mid-edit closed the row and
 * discarded the typed value with no sign that anything had been thrown away.
 *
 * `everFocused` is the third and it must stay plain — see the second test below, which pins a
 * measurement rather than guarding a regression.
 *
 * ### Why this reads the source instead of driving the component
 *
 * The behavioural test is the one written for the naming step in the same fix —
 * [SaveSheetTitleSurvivesRecreationTest] — and it works because `SaveSheet` is `internal` and needs
 * nothing but a state object. `ReadField` is `private` in `ItemDetailScreen.kt` and cannot be
 * composed from a test at all; the only way at it is through the real [ItemDetailScreen], which
 * needs an `ItemEditViewModel`, a `VaultRepository`, a `VaultLockManager` and therefore a
 * `KeystoreManager` opening AndroidKeyStore in its constructor — which is exactly why this module's
 * `build.gradle.kts` puts those tests in `androidTest` rather than here, and why
 * [ReadFieldCopyOnlyAccessibilityTest] is instrumented. A device pass was not available to this fix.
 *
 * So this asserts the one thing that is unambiguously true of the fix and false of the bug, in the
 * idiom `NamingSheetAutoFocusTest` and `CameraPreviewIsClippableTest` already use in this repo: the
 * declarations say `rememberSaveable`. It is a blunt instrument and deliberately so — the control
 * at the bottom is what stops it passing against a file it did not really read.
 */
class ReadFieldDraftSurvivesRecreationTest {

    private val source: String
        get() = File(REPO, "feature/itemedit/src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt")
            .also { assertTrue(it.isFile, "ItemDetailScreen.kt not found at ${it.absolutePath}") }
            .readText()

    private fun declaration(name: String): String =
        source.lineSequence().firstOrNull { it.trimStart().startsWith("$name by ") }
            ?: error("no `$name` declaration in ItemDetailScreen.kt — see the control below")

    private fun assertSaveable(name: String) {
        val line = declaration(name)
        assertTrue(
            "rememberSaveable" in line,
            "ReadField's `$name` is `${line.trim()}`. A plain `remember` is dropped by an activity " +
                "recreation, so a rotation part-way through an inline edit closes the row and " +
                "throws the typed draft away with nothing to show that it happened.",
        )
    }

    @Test
    fun `the inline edit keeps its open state and its draft across a recreation`() {
        assertSaveable("var editing")
        assertSaveable("var draft")
    }

    /**
     * The focus guard is the one that must NOT be saved, which is the opposite of what it looks
     * like it should be — so it is pinned here with the measurement that decided it.
     *
     * `everFocused` suppresses the `hasFocus = false` that `onFocusChanged` fires once on first
     * composition, before `requestFocus` has run. A restored composition is a first composition:
     * the node is freshly attached and unfocused, so that event is the spurious one again. Restore
     * the guard as `true` and the restored row reads it as a tap-away instead and commits on
     * arrival. Measured 2026-08-31 with `StateRestorationTester` over the same modifier chain:
     * saveable → 3 commits after the restore and the edit closed; plain → the edit came back open
     * with its draft. Making all three saveable undoes the fix the other two are.
     */
    @Test
    fun `the focus guard re-arms on a restored composition rather than being restored`() {
        val line = declaration("var everFocused")
        assertFalse(
            "rememberSaveable" in line,
            "ReadField's `everFocused` is `${line.trim()}`. Saving it across a recreation makes the " +
                "restored row treat its own first hasFocus=false as a tap-away, committing and " +
                "closing the inline edit on arrival — which is the thing `editing` and `draft` were " +
                "made saveable to prevent. Re-measure with StateRestorationTester before changing it.",
        )
    }

    /**
     * The control. The assertions above read one file by path and match declarations by name — so
     * prove the file is the item detail screen and that the three states are ReadField's, not three
     * same-named variables somewhere else in it.
     */
    @Test
    fun `the file being read really holds ReadField's inline edit`() {
        assertTrue("private fun ReadField(" in source, "read a file with no ReadField in it")
        listOf("var editing", "var draft", "var everFocused").forEach { name ->
            assertTrue(
                source.lineSequence().count { it.trimStart().startsWith("$name by ") } == 1,
                "`$name` is not declared exactly once in ItemDetailScreen.kt, so matching it by " +
                    "name no longer identifies ReadField's own state",
            )
        }
    }
}

private val REPO: File = generateSequence(File("").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").isFile }
    ?: error("repo root not found above ${File("").absolutePath}")
