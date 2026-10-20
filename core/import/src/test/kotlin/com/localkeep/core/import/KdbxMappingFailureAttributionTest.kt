package com.localkeep.core.import

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A failure *after* the KDBX file has decrypted must not be blamed on the file.
 *
 * `parse` used to wrap the whole body in one `catch (t: Throwable)` and route every exception
 * through `describeFailure`, whose else arm says "the file looks damaged". Everything after the
 * `load` call runs on an already-open database — the header verified, so the password was right
 * and the container is intact — so that message would have told a user with a perfectly good
 * export to re-export it or pick another file, neither of which can fix anything on this side of
 * `load`. `KdbxSupportBroken` is the honest reason, and its string already says
 * "this isn't a problem with your password or your file. Please report it."
 *
 * The anchor below is `val db = load(`, not `DomDatabaseWrapper.load`: since 2026-09-13 this module
 * opens the container itself so it can install an Argon2id key derivation the library does not
 * have (see `KdbxArgon2id.kt`). The boundary is the same one, in the same place.
 *
 * **These are source assertions, and deliberately so.** Nothing a user can put in a `.kdbx` reaches
 * the mapping code's failure paths: `collectGroup`, `mapEntry`, `customFields` and `findTotp` are
 * defensive throughout (`orEmpty()`, `mapNotNull`, `toIntOrNull()`), which is why this was an
 * attribution gap rather than a live bug. There is therefore no input that makes the branch run,
 * and the only thing left to assert is where the boundary sits. Same idiom as
 * `FolderCacheReleaseTest` and `HiddenChokepointTest`.
 */
class KdbxMappingFailureAttributionTest {

    private val source = File("src/main/kotlin/com/localkeep/core/import/KdbxImporter.kt")

    /**
     * Code with its comments removed — the file explains this boundary at length, and a scan that
     * counted the prose would pass on a broken file and teach the next person to delete the prose.
     */
    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `the post-decrypt mapping reports an app-side failure, not a damaged file`() {
        val code = codeOnly(source.readText())

        val load = code.indexOf("val db = load(")
        if (load < 0) fail("could not find the KDBX load call in ${source.name}")
        val success = code.indexOf("return Outcome.Success(", load)
        if (success < 0) fail("could not find the success return after the load call in ${source.name}")
        val mapping = code.substring(load, success)

        val walk = mapping.indexOf("collectGroup(")
        if (walk < 0) fail("could not find the entry walk between load and the success return")
        val attribution = mapping.indexOf("ImportFailureReason.KdbxSupportBroken")
        assertTrue(
            attribution > walk,
            "the walk from load to the success return must be guarded by its own catch reporting " +
                "KdbxSupportBroken, or a bug in this module's mapping code is reported as a damaged file",
        )
    }

    /**
     * The control, which held before the boundary was drawn: a genuine container failure is still
     * a damaged file. Narrowing what the else arm covers must not delete it.
     */
    @Test
    fun `a container-level failure is still reported as a damaged file`() {
        val code = codeOnly(source.readText())
        assertTrue(
            "ImportFailureReason.DamagedKdbxFile" in code,
            "describeFailure must still have a damaged-file arm for failures at or before load",
        )
        assertTrue(
            "ImportFailureReason.WrongPassword" in code,
            "describeFailure must still name a wrong password as a wrong password",
        )
    }
}
