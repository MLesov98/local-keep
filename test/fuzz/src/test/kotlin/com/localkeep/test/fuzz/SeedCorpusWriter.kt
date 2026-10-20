package com.localkeep.test.fuzz

import com.localkeep.core.health.BreachFilterAssetBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import com.localkeep.core.import.Argon2idKdbxHeader
import com.localkeep.core.import.Argon2idKdf
import com.localkeep.core.import.TwofishCipher
import com.localkeep.core.import.TwofishKdbxHeader
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.KdbxStreamFormat
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the seed corpus into `src/test/resources/`, where Jazzer's JUnit extension looks for it.
 *
 * **Why seeds decide whether any of this is worth running.** The first campaign against
 * `BreachFilter.load` ran a full 60 seconds and reported nothing — not because the parser is sound
 * but because the fuzzer never got past line 124, `require(din.readInt() == BREACH_FILTER_MAGIC)`.
 * Four specific bytes are not something random mutation discovers. Every target here has a gate of
 * that shape: a magic number, a `{`, a zip local-file header. Without a valid input to mutate from,
 * a fuzz campaign is an expensive way to test one rejection path, and it is green while doing it.
 *
 * Disabled unless `-DwriteSeeds=true`, and deliberately so: it writes into the *source* tree, which
 * a test run in CI must never do. Regenerate and commit the result when a format changes:
 *
 * ```bash
 * ./gradlew :test:fuzz:test --tests '*SeedCorpusWriter*' -DwriteSeeds=true
 * ```
 *
 * The seeds are ordinary valid documents, harvested from the same fixtures the unit tests already
 * use rather than invented here — a seed nobody recognises is a seed nobody maintains.
 */
@EnabledIfSystemProperty(named = "writeSeeds", matches = "true")
class SeedCorpusWriter {

    private val root = File("src/test/resources/com/localkeep/test/fuzz")

    /**
     * Jazzer's layout, not one this file invents: seeds for `pkg.ClassName.method` live in
     * `src/test/resources/pkg/ClassNameInputs/method/`. Getting the directory wrong is silent —
     * the target still runs, on the empty input alone, and still passes.
     */
    private fun seed(target: String, method: String, name: String, bytes: ByteArray) {
        val dir = File(root, "${target}Inputs/$method")
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
        println("seed: ${dir.path}/$name (${bytes.size} bytes)")
    }

    @Test
    fun writeSeeds() {
        val importer = "ImporterFuzzTest"
        val breach = "BreachFilterFuzzTest"

        // --- Bitwarden ------------------------------------------------------------------------
        val bitwarden = """
            {"encrypted":false,"folders":[{"id":"f1","name":"Work"}],
             "items":[{"id":"i1","type":1,"name":"Example","folderId":"f1",
               "login":{"username":"alice@example.com","password":"s3cr3t",
                        "uris":[{"uri":"https://example.com","match":0}],"totp":"otpauth://totp/x?secret=JBSWY3DPEHPK3PXP"},
               "notes":"hello","fields":[{"name":"pin","value":"1234","type":1}]}]}
        """.trimIndent().toByteArray()
        seed(importer, "bitwarden", "bitwarden.json", bitwarden)
        // The password-protected branch — a distinct authored rejection, so a distinct seed.
        seed(importer, "bitwarden", "bitwarden-encrypted.json", """{"encrypted":true,"items":[],"folders":[]}""".toByteArray())

        // --- Aegis ----------------------------------------------------------------------------
        val aegis = """
            {"version":1,"header":{"slots":null,"params":null},
             "db":{"version":2,"entries":[{"type":"totp","uuid":"u1","name":"acct","issuer":"Example",
               "info":{"secret":"JBSWY3DPEHPK3PXP","algo":"SHA1","digits":6,"period":30}}]}}
        """.trimIndent().toByteArray()
        seed(importer, "aegis", "aegis.json", aegis)
        seed(importer, "aegis", "aegis-encrypted.json", """{"version":1,"header":{},"db":"BASE64CIPHERTEXT"}""".toByteArray())

        // --- CSV ------------------------------------------------------------------------------
        val csv = """
            name,url,username,password,note
            Example,https://example.com,alice@example.com,s3cr3t,hello
            "Quoted, comma",https://b.example,bob,"pa""ss",
        """.trimIndent().toByteArray()
        seed(importer, "csv", "generic.csv", csv)

        // --- 1pux (a zip container) -----------------------------------------------------------
        val exportData = """
            {"accounts":[{"attrs":{"accountName":"Personal"},"vaults":[{"attrs":{"name":"Private"},
              "items":[{"item":{"uuid":"u1","overview":{"title":"Example","url":"https://example.com"},
              "details":{"loginFields":[{"designation":"username","value":"alice"},
                                        {"designation":"password","value":"s3cr3t"}],"notesPlain":"hi"}}}]}]}]}
        """.trimIndent()
        val onepux = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("export.data"))
                zip.write(exportData.toByteArray())
                zip.closeEntry()
            }
            out.toByteArray()
        }
        seed(importer, "onepux", "export.1pux", onepux)

        // --- KDBX -----------------------------------------------------------------------------
        // Built with DomDatabaseWrapper, the same backend the app ships, so the seed cannot drift
        // away from the reader under test. Same construction as KdbxRoundTripTest.
        val db = DomDatabaseWrapper()
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "Example"
                username = "alice@example.com"
                setPassword("s3cr3t-p@ss")
                url = "https://example.com/login"
                notes = "imported from keepass"
            },
        )
        val kdbx = ByteArrayOutputStream().use { out ->
            db.save(KdbxCreds("correct horse battery staple".toByteArray(Charsets.UTF_8)), out)
            out.toByteArray()
        }
        seed(importer, "kdbx", "example.kdbx", kdbx)

        // A second KDBX seed, because the first one exercises only half the KDF code. The library
        // writes Argon2d; KeePassXC has written **Argon2id** into every new database since 2.6.6,
        // and `:core:import` derives that one itself (`Argon2idKdbxHeader`) because KeePassJava2's
        // KDF registry is a closed enum with no room for it. Without this seed the fuzzer would
        // never reach that derivation, and the most common modern .kdbx would be the one shape
        // never fuzzed.
        //
        // 16 MiB / 2 passes / 2 lanes rather than the library's 64 MiB default: every mutated input
        // pays for a full derivation, and this module runs on a deliberately small 256 MB heap.
        val argon2idHeader = Argon2idKdbxHeader(4).apply {
            setKdfParameters(
                Argon2idKdf.createKdfParameters().apply {
                    putULong("M", 16L * 1024 * 1024)
                    putULong("I", 2L)
                    putUInt("P", 2)
                },
            )
        }
        val argon2id = ByteArrayOutputStream().use { out ->
            db.save(
                KdbxStreamFormat(argon2idHeader),
                KdbxCreds("correct horse battery staple".toByteArray(Charsets.UTF_8)),
                out,
            )
            out.toByteArray()
        }
        seed(importer, "kdbx", "example-argon2id.kdbx", argon2id)

        // A third KDBX seed, for the same reason as the second but on the cipher axis instead of
        // the KDF one: KeePassJava2's cipher registry is a closed enum of AES and ChaCha20, so
        // Twofish is decrypted by `:core:import`'s own `TwofishCipher` and no mutation of the
        // other two seeds can reach it — their header names a different cipher UUID, and the
        // sixteen bytes that name this one are not something a fuzzer discovers.
        //
        // The library's default KDF (AES-KDF) is kept here rather than Argon2id: what this seed
        // exists to exercise is the cipher, and a second expensive derivation on every mutated
        // input would buy nothing the seed above does not already cover.
        val twofishHeader = TwofishKdbxHeader(4).apply { setCipherUuid(TwofishCipher.UUID) }
        val twofish = ByteArrayOutputStream().use { out ->
            db.save(
                KdbxStreamFormat(twofishHeader),
                KdbxCreds("correct horse battery staple".toByteArray(Charsets.UTF_8)),
                out,
            )
            out.toByteArray()
        }
        seed(importer, "kdbx", "example-twofish.kdbx", twofish)

        // --- Keeper ---------------------------------------------------------------------------
        // Both shapes behind one `detect`: the native JSON, and the header-less positional CSV whose
        // recognition rests on five narrow rules. A campaign with only the JSON seed would never
        // reach the CSV branch at all, because random mutation of a `{` does not produce one.
        val keeperJson = """
            {"shared_folders":[{"path":"Work\\Shared","manage_users":false}],
             "records":[{"uid":"r1","title":"Example","login":"alice@example.com","password":"s3cr3t",
               "login_url":"https://example.com","notes":"hello","folders":[{"folder":"Work"}],
               "custom_fields":{"pin":"1234","${'$'}oneTimeCode":"otpauth://totp/x?secret=JBSWY3DPEHPK3PXP"}}]}
        """.trimIndent().toByteArray()
        seed(importer, "keeper", "keeper.json", keeperJson)
        seed(
            importer,
            "keeper",
            "keeper.csv",
            "Work\\Bank,Example,alice@example.com,s3cr3t,https://example.com,hello,,pin,1234\n".toByteArray(),
        )

        // --- Enpass ---------------------------------------------------------------------------
        // The JSON needs `template_type`/`category` on an item, not merely `items` + `folders`:
        // that pair alone is Bitwarden's shape, and a seed without it would be mutated against the
        // wrong parser's gate. The CSV is the label/value interleave, and its `*Password` is
        // load-bearing rather than decorative: `EnpassCsv.detect` recognises a header-less file by
        // a `*`-prefixed cell in a label position and nothing else, so the same row without the
        // asterisk is not claimed at all — which is how this seed was written the first time, and
        // what `every committed seed is recognised by the importer it seeds` caught.
        val enpassJson = """
            {"folders":[{"uuid":"f1","title":"Work","parent_uuid":""}],
             "items":[{"uuid":"i1","title":"Example","category":"login","template_type":"login.default",
               "folders":["f1"],"note":"hello",
               "fields":[{"label":"Username","type":"username","value":"alice","sensitive":0},
                         {"label":"Password","type":"password","value":"s3cr3t","sensitive":1},
                         {"label":"Website","type":"url","value":"https://example.com","sensitive":0}]}]}
        """.trimIndent().toByteArray()
        seed(importer, "enpass", "enpass.json", enpassJson)
        seed(
            importer,
            "enpass",
            "enpass.csv",
            "Example,Username,alice,*Password,s3cr3t,Website,https://example.com,a note\n".toByteArray(),
        )

        // --- Proton Pass ----------------------------------------------------------------------
        val protonPass = """
            {"version":"1.31.6","userId":"u1","encrypted":false,
             "vaults":{"v1":{"name":"Personal","description":"","items":[
               {"itemId":"i1","state":1,"createTime":1700000000,"modifyTime":1700000001,
                "data":{"metadata":{"name":"Example","note":"hello"},
                        "content":{"itemUsername":"alice","username":"","password":"s3cr3t",
                                   "urls":["https://example.com"],"totpUri":""},
                        "extraFields":[]}}]}}}
        """.trimIndent().toByteArray()
        seed(importer, "protonpass", "protonpass.json", protonPass)

        // --- Dashlane -------------------------------------------------------------------------
        // One per schema. Recognition is an exact header set, so a single seed would leave three
        // of the four branches unreachable however long the campaign ran.
        seed(
            importer,
            "dashlane",
            "id.csv",
            ("type,number,name,issue_date,expiration_date,place_of_issue,state\n" +
                "passport,X1234567,Alice Example,2019-01-02,2029-01-02,London,\n").toByteArray(),
        )
        seed(
            importer,
            "dashlane",
            "payments.csv",
            ("type,account_name,account_holder,cc_number,code,expiration_month,expiration_year," +
                "routing_number,account_number,country,issuing_bank\n" +
                "credit_card,Card,Alice Example,4111111111111111,123,4,2030,,,GB,Example Bank\n").toByteArray(),
        )
        seed(
            importer,
            "dashlane",
            "personal-info.csv",
            ("type,title,first_name,middle_name,last_name,login,date_of_birth,place_of_birth,email," +
                "email_type,item_name,phone_number,address,country,state,city,zip,address_recipient," +
                "address_building,address_apartment,address_floor,address_door_code,job_title,url\n" +
                "name,mr,Alice,,Example,alice,1990-01-01,London,alice@example.com,personal,Alice," +
                "+441234567890,1 Example Street,GB,,London,SW1A 1AA,,,,,,Engineer,\n").toByteArray(),
        )
        seed(
            importer,
            "dashlane",
            "securenotes.csv",
            "title,note\nExample,hello\n".toByteArray(),
        )

        // --- BreachFilter ---------------------------------------------------------------------
        // A real asset from the shipped writer, so every length field in the header is internally
        // consistent and the fuzzer starts from "valid" rather than from "rejected at byte 4".
        val filter = BreachFilterAssetBuilder.build(
            passwords = listOf("password", "123456", "qwerty", "letmein", "correcthorsebatterystaple"),
            vintage = "fuzz seed corpus",
        )
        seed(breach, "load", "valid.filter", filter)
        seed(breach, "ensureLoadedNeverThrows", "valid.filter", filter)
    }
}
