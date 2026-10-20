package com.localkeep.test.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import com.localkeep.core.import.AegisJsonImporter
import com.localkeep.core.import.BitwardenJsonImporter
import com.localkeep.core.import.CsvImporter
import com.localkeep.core.import.DashlaneImporter
import com.localkeep.core.import.EnpassImporter
import com.localkeep.core.import.KdbxImporter
import com.localkeep.core.import.KeeperImporter
import com.localkeep.core.import.OnePuxImporter
import com.localkeep.core.import.ProtonPassJsonImporter

/**
 * The importers are the largest genuinely attacker-controlled surface in this app: the user picks a
 * file from anywhere on the device and the app parses it, in two cases with a third-party library
 * over a container format.
 *
 * Each target below is the same three lines — call `parse`, hand the result to [ImporterOracle] —
 * because the interesting part is not the harness but the oracle, and the oracle is shared so the
 * five importers cannot drift into being held to different standards.
 *
 * **Two modes, one set of targets.** Without `JAZZER_FUZZ` these run as ordinary tests replaying the
 * committed seed corpus under `src/test/resources/<class>/<method>/` — fast enough for the daily
 * gate. With `JAZZER_FUZZ=1` the same methods become libFuzzer campaigns. Keeping them as one thing
 * is what stops the campaign and the regression suite from drifting apart, which is the failure mode
 * that leaves a fuzzer green and useless.
 */
class ImporterFuzzTest {

    @FuzzTest(maxDuration = "10m")
    fun bitwarden(bytes: ByteArray) = ImporterOracle.check("Bitwarden", BitwardenJsonImporter, bytes)

    @FuzzTest(maxDuration = "10m")
    fun aegis(bytes: ByteArray) = ImporterOracle.check("Aegis", AegisJsonImporter, bytes)

    @FuzzTest(maxDuration = "10m")
    fun csv(bytes: ByteArray) = ImporterOracle.check("CSV", CsvImporter, bytes)

    /**
     * A zip container, so this one is also the decompression-bounds target: `OnePuxImporter` carries
     * a `MAX_EXPORT_DATA_BYTES` check (256 MB) that exists precisely to be exercised, and nothing has
     * ever exercised it with an input it did not author.
     */
    @FuzzTest(maxDuration = "10m")
    fun onepux(bytes: ByteArray) = ImporterOracle.check("1pux", OnePuxImporter, bytes)

    /**
     * The four added on 2026-09-17, named so `checkFuzzTargetsCovered` can pair each with its entry
     * in `ALL_IMPORTERS`: it matches on `removeSuffix("Importer").replace("Json", "").lowercase()`,
     * which is why this one is `protonpass` and not `protonpassjson`.
     *
     * Keeper and Enpass each read two unrelated shapes — a native JSON export and a header-less or
     * label/value CSV — behind one `detect`, and both branches are reachable from the same
     * `ByteArray`, so one target covers both. Both carry a seed of each shape for that reason.
     */
    @FuzzTest(maxDuration = "10m")
    fun keeper(bytes: ByteArray) = ImporterOracle.check("Keeper", KeeperImporter, bytes)

    @FuzzTest(maxDuration = "10m")
    fun enpass(bytes: ByteArray) = ImporterOracle.check("Enpass", EnpassImporter, bytes)

    @FuzzTest(maxDuration = "10m")
    fun protonpass(bytes: ByteArray) = ImporterOracle.check("Proton Pass", ProtonPassJsonImporter, bytes)

    /**
     * The four non-credential CSVs, recognised by an exact header set — so the seeds are one per
     * schema, and a campaign with fewer would fuzz one branch of four.
     */
    @FuzzTest(maxDuration = "10m")
    fun dashlane(bytes: ByteArray) = ImporterOracle.check("Dashlane", DashlaneImporter, bytes)

    /**
     * Fuzzed with a fixed password, deliberately. Mutating the password as well would spend the
     * whole budget rediscovering that a wrong password fails, which is the one behaviour here that
     * is already covered by an ordinary test.
     */
    @FuzzTest(maxDuration = "10m")
    fun kdbx(bytes: ByteArray) = ImporterOracle.check("KDBX") {
        KdbxImporter.parse(bytes, FIXED_PASSWORD.toCharArray())
    }

    /**
     * The Argon2id seed is only worth having if it is a real Argon2id container, and "the fuzzer did
     * not crash on it" would be satisfied by 1.8 KB of noise. This opens the committed bytes with
     * the same password the campaign uses and reads the entry back out.
     *
     * It also pins the reason the seed exists: KeePassJava2 2.2.4 cannot open this file at all — its
     * KDF registry is a closed enum of Argon2d and AES-KDF — so the derivation under test is
     * `:core:import`'s own (`Argon2idKdbxHeader`). Regenerate with
     * `./gradlew :test:fuzz:test --tests '*SeedCorpusWriter*' -PwriteSeeds=true`.
     */
    @org.junit.jupiter.api.Test
    fun `the committed argon2id seed is a real argon2id container that opens`() {
        val seed = checkNotNull(
            javaClass.getResourceAsStream(
                "/com/localkeep/test/fuzz/ImporterFuzzTestInputs/kdbx/example-argon2id.kdbx",
            ),
        ) { "the Argon2id KDBX seed is missing from the corpus" }.readBytes()

        val argon2id = byteArrayOf(
            0x9E.toByte(), 0x29, 0x8B.toByte(), 0x19, 0x56, 0xDB.toByte(), 0x47, 0x73,
            0xB2.toByte(), 0x3D, 0xFC.toByte(), 0x3E, 0xC6.toByte(), 0xF0.toByte(), 0xA1.toByte(), 0xE6.toByte(),
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            (0..seed.size - argon2id.size).any { i -> argon2id.indices.all { seed[i + it] == argon2id[it] } },
            "the seed's header must name Argon2id, or it is not testing what it claims to",
        )

        val outcome = KdbxImporter.parse(seed, FIXED_PASSWORD.toCharArray())
        org.junit.jupiter.api.Assertions.assertTrue(
            outcome is com.localkeep.core.common.Outcome.Success,
            "the Argon2id seed must open with the campaign's password: $outcome",
        )
        org.junit.jupiter.api.Assertions.assertEquals(
            "Example",
            (outcome as com.localkeep.core.common.Outcome.Success).value.items.single().title,
        )
    }

    /**
     * The same gate on the cipher axis: the Twofish seed has to be a real Twofish container, or the
     * campaign mutates 1.8 KB of AES and the cipher `:core:import` implements itself
     * (`TwofishCipher`, because KeePassJava2's cipher registry is a closed enum of AES and
     * ChaCha20) is the one decryption path never fuzzed — silently, and green.
     */
    @org.junit.jupiter.api.Test
    fun `the committed twofish seed is a real twofish container that opens`() {
        val seed = checkNotNull(
            javaClass.getResourceAsStream(
                "/com/localkeep/test/fuzz/ImporterFuzzTestInputs/kdbx/example-twofish.kdbx",
            ),
        ) { "the Twofish KDBX seed is missing from the corpus" }.readBytes()

        val twofish = byteArrayOf(
            0xAD.toByte(), 0x68, 0xF2.toByte(), 0x9F.toByte(), 0x57, 0x6F, 0x4B, 0xB9.toByte(),
            0xA3.toByte(), 0x6A, 0xD4.toByte(), 0x7A, 0xF9.toByte(), 0x65, 0x34, 0x6C,
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            (0..seed.size - twofish.size).any { i -> twofish.indices.all { seed[i + it] == twofish[it] } },
            "the seed's header must name Twofish, or it is not testing what it claims to",
        )

        val outcome = KdbxImporter.parse(seed, FIXED_PASSWORD.toCharArray())
        org.junit.jupiter.api.Assertions.assertTrue(
            outcome is com.localkeep.core.common.Outcome.Success,
            "the Twofish seed must open with the campaign's password: $outcome",
        )
        org.junit.jupiter.api.Assertions.assertEquals(
            "Example",
            (outcome as com.localkeep.core.common.Outcome.Success).value.items.single().title,
        )
    }

    /**
     * Every seed for the four 2026-09-17 targets is a file its own importer actually claims.
     *
     * The same point [SeedCorpusWriter]'s kdoc makes about magic numbers, made into a gate. A seed
     * that `detect` refuses is not a seed: the campaign mutates from it, never passes the format
     * check, and spends its whole budget confirming one rejection path — while reporting green. It
     * is silent, too, because Jazzer is perfectly happy to fuzz an input nothing recognises.
     *
     * Keeper and Enpass carry one seed per shape (JSON and CSV) and this asserts both, which is the
     * part most easily lost: their CSV branch is header-less or label/value, and random mutation of
     * a `{` will not find it.
     */
    @org.junit.jupiter.api.Test
    fun `every committed seed is recognised by the importer it seeds`() {
        val targets = listOf(
            "keeper" to KeeperImporter,
            "enpass" to EnpassImporter,
            "protonpass" to ProtonPassJsonImporter,
            "dashlane" to DashlaneImporter,
        )
        targets.forEach { (method, importer) ->
            val names = seedNames(method)
            org.junit.jupiter.api.Assertions.assertTrue(
                names.isNotEmpty(),
                "$method has no seed corpus — the campaign would run on the empty input alone",
            )
            names.forEach { name ->
                val bytes = checkNotNull(
                    javaClass.getResourceAsStream(
                        "/com/localkeep/test/fuzz/ImporterFuzzTestInputs/$method/$name",
                    ),
                ) { "seed $method/$name is missing from the corpus" }.readBytes()
                org.junit.jupiter.api.Assertions.assertTrue(
                    importer.detect(bytes),
                    "seed $method/$name is not recognised by the importer it seeds",
                )
            }
        }
    }

    /**
     * The seed file names, read off the source tree rather than the classpath: a directory is not
     * enumerable through `getResourceAsStream`, and this module's tests already run with the module
     * as their working directory ([SeedCorpusWriter] writes through the same relative path).
     */
    private fun seedNames(method: String): List<String> =
        java.io.File("src/test/resources/com/localkeep/test/fuzz/ImporterFuzzTestInputs/$method")
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .map { it.name }
            .sorted()

    private companion object {
        const val FIXED_PASSWORD = "correct horse battery staple"
    }
}
