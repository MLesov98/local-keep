package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The published Argon2id memory figure must equal the constant the app actually seals vaults with.
 *
 * Written after `64 MiB` was found in eight documents — including `FEATURES.md` and
 * `COMPARISON.md`, which a reader of the public repository sees — while every vault this build
 * creates is sealed at `KdfParams.PORTABLE_MAX_MEMORY_KIB`, which has been 32 MiB since
 * 2026-07-29. The number drifted for forty days because nothing tied the prose to the code.
 *
 * The prose is what moves. The constant is not: 64 MiB is a live heap allocation and API 28's
 * `dalvik.vm.heapgrowthlimit` is 48 MiB, so raising the ceiling to make a document true would
 * make vaults created on a modern phone unopenable on a device the app claims to support. If this
 * test fails, correct the document — never the constant.
 *
 * The constant is read out of the source text rather than imported, so the check holds without
 * :app taking a dependency on :core:crypto for it.
 */
class KdfMemoryDocsMatchCodeTest {

    private val shippedMemoryMib: Int = run {
        val src = File(REPO, "core/crypto/src/main/kotlin/com/localkeep/core/crypto/KdfParams.kt")
        assertTrue(src.isFile, "KdfParams.kt not found at ${src.path}")
        val kib = Regex("""const val PORTABLE_MAX_MEMORY_KIB = ([0-9_]+)""")
            .find(src.readText())
            ?.groupValues
            ?.get(1)
            ?.replace("_", "")
            ?.toInt()
            ?: fail("PORTABLE_MAX_MEMORY_KIB not found in ${src.path}")
        assertEquals(0, kib % 1024, "PORTABLE_MAX_MEMORY_KIB is not a whole number of MiB")
        kib / 1024
    }

    @Test
    fun `every published Argon2id memory figure matches the shipped ceiling`() {
        val figure = Regex("""(\d+) ?MiB""")
        val offenders = mutableListOf<String>()
        var claimsChecked = 0
        for ((path, needle) in DOCUMENTED_SITES) {
            val file = File(REPO, path)
            assertTrue(file.isFile, "$path not found")
            val lines = file.readLines()
            lines.forEachIndexed { i, line ->
                if (!needle.containsMatchIn(line)) return@forEachIndexed
                // The figure is not always on the same line as the algorithm name — FEATURES.md
                // wraps it onto the next one, which an earlier draft of this test silently skipped.
                // That is why the window exists, and why CLAIMS_EXPECTED below is asserted.
                for (offset in 0..LOOKAHEAD) {
                    val candidate = lines.getOrNull(i + offset) ?: break
                    figure.findAll(candidate).forEach { m ->
                        claimsChecked++
                        val mib = m.groupValues[1].toInt()
                        if (mib != shippedMemoryMib) {
                            offenders += "$path:${i + offset + 1} claims $mib MiB — ${candidate.trim()}"
                        }
                    }
                }
            }
        }
        assertTrue(
            claimsChecked >= CLAIMS_EXPECTED,
            "Only $claimsChecked Argon2id memory figures were examined, expected at least " +
                "$CLAIMS_EXPECTED. A marker has stopped matching, so this guard is checking less " +
                "than it claims to — fix the marker rather than lowering the number.",
        )
        if (offenders.isNotEmpty()) {
            fail(
                "Argon2id memory is $shippedMemoryMib MiB in KdfParams.PORTABLE_MAX_MEMORY_KIB, " +
                    "but these lines say otherwise. Fix the prose, not the constant:\n" +
                    offenders.joinToString("\n"),
            )
        }
    }

    private companion object {
        /**
         * Each entry is a file and the marker that identifies the line carrying the claim. The
         * marker keeps the check off unrelated MiB figures in the same document — an attachment
         * size limit is not a KDF parameter.
         */
        const val LOOKAHEAD = 2

        /**
         * How many Argon2id memory figures the markers below must reach. A marker that stops
         * matching would otherwise turn this test green by examining nothing — the failure mode
         * that shipped in its first draft.
         */
        const val CLAIMS_EXPECTED = 3

        val DOCUMENTED_SITES = listOf(
            "FEATURES.md" to Regex("""\*\*Argon2id\*\*"""),
            "COMPARISON.md" to Regex("""\*\*Argon2id\*\*,"""),
            "docs/THREAT-MODEL.md" to Regex("""\*\*Argon2id\*\*, RFC 9106"""),
        )
    }
}

private val REPO: File = generateSequence(File("").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").isFile }
    ?: error("repo root not found above ${File("").absolutePath}")
