package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * No call site in `feature/onboarding` supplies its own gap below an `LkSectionLabel`.
 *
 * `LkSectionLabel` owns that gap (`LkSectionLabelGap`, measured in `:core:ui` by
 * `LkSectionLabelGapTest`). One relationship — an uppercase heading above the group it names — was
 * rendered at 7dp, 9dp, 10dp, 14dp and 16dp across this app because every caller supplied it and no
 * two callers agreed. Now that the component supplies it, a caller's own `Spacer`, its own
 * `padding(bottom = ...)`, and the `verticalArrangement` of a `Column` the label sits directly in
 * are each *added to* the component's padding instead of being it — so each renders the gap too
 * wide, which is the same defect one step further along.
 *
 * Source-scanned rather than measured. The defect is the shape of a call site, and the call sites
 * are spread over screens a Compose test could only reach one at a time, behind the state each one
 * needs to compose at all.
 *
 * FAILS WITHOUT THE FIX: run against 065ca9c2 this reported 1 offending call site -
 * OnboardingScreen.kt:824, whose `Spacer` was 14dp.
 */
class LkSectionLabelGapCallSitesTest {

    @Test
    fun `no call site adds its own gap below a section label`() {
        val offences = sources().flatMap { file -> offencesIn(file) }
        assertTrue(
            offences.isEmpty(),
            "LkSectionLabel owns the gap below it; these call sites add a second one:\n" +
                offences.joinToString("\n"),
        )
    }

    /** The control. Without it, a scan that read nothing at all would pass the test above. */
    @Test
    fun `the scan reaches this module's section labels`() {
        val found = sources().sumOf { file -> file.readLines().count { it.isCall() } }
        assertTrue(found > 0, "found no LkSectionLabel call sites in feature/onboarding — the scan is broken")
    }

    private fun sources(): List<File> =
        File(REPO, "feature/onboarding/src/main/kotlin")
            .also { assertTrue(it.isDirectory, "no main sources at ${it.absolutePath}") }
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun offencesIn(file: File): List<String> {
        val lines = file.readLines()
        val offences = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            if (!line.isCall()) return@forEachIndexed
            val where = "${file.name}:${index + 1}"
            val call = callLines(lines, index)
            if (call.any { "bottom =" in it }) {
                offences += "$where - the call passes a padding(bottom = ...) of its own"
            }
            val next = lines.drop(index + call.size).firstOrNull { it.isCode() }
            if (next != null && next.trimStart().startsWith("Spacer(")) {
                offences += "$where - a Spacer follows the label"
            }
            val owner = enclosingBlock(lines, index)
            if (owner.any { "Column" in it } && owner.any { "verticalArrangement" in it }) {
                offences += "$where - sits directly in a Column carrying its own verticalArrangement"
            }
        }
        return offences
    }

    /** The whole `LkSectionLabel(...)` call, however many lines its arguments are spread over. */
    private fun callLines(lines: List<String>, start: Int): List<String> {
        val call = mutableListOf<String>()
        var depth = 0
        var opened = false
        for (index in start until lines.size) {
            call += lines[index]
            for (character in lines[index]) {
                when (character) {
                    '(' -> { depth++; opened = true }
                    ')' -> depth--
                }
            }
            if (opened && depth <= 0) break
        }
        return call
    }

    /**
     * The declaration of the block this call sits directly inside: the nearest enclosing code line
     * at a shallower indent, plus the argument list it trails from when that line is a bare `) {`.
     *
     * Indentation, not a parser, because the question is only ever about the call's *own* parent —
     * and a Kotlin file in this repo is four-space indented by `.editorconfig` and by ktlint.
     */
    private fun enclosingBlock(lines: List<String>, index: Int): List<String> {
        val indent = lines[index].indentWidth()
        var end = index - 1
        while (end >= 0 && (!lines[end].isCode() || lines[end].indentWidth() >= indent)) end--
        if (end < 0) return emptyList()
        val ownIndent = lines[end].indentWidth()
        var start = end
        while (start > 0 && lines[start - 1].indentWidth() > ownIndent) start--
        // The head of a multi-line call sits back at the closing line's own indent: `Column(` above
        // its arguments above `) {`. Without this the scan reads the argument list without the name
        // it belongs to, and a `Column(\n ... verticalArrangement ...,\n) {` reads as not a Column.
        if (start > 0 &&
            lines[start - 1].indentWidth() == ownIndent &&
            lines[start - 1].trimEnd().endsWith("(")
        ) {
            start--
        }
        return lines.subList(start, end + 1)
    }

    private fun String.isCall(): Boolean =
        isCode() && "LkSectionLabel(" in this && !trimStart().startsWith("import ")

    private fun String.isCode(): Boolean {
        val body = trimStart()
        return isNotBlank() && !body.startsWith("//") && !body.startsWith("*") && !body.startsWith("/*")
    }

    private fun String.indentWidth(): Int = length - trimStart().length
}

private val REPO: File = generateSequence(File("").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").isFile }
    ?: error("repo root not found above ${File("").absolutePath}")
