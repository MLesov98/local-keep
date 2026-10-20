package com.localkeep.feature.files

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The quota refusal has to name the number, in every language.
 *
 * "Files & media is part of Pro" is a true sentence that answers the wrong question once a free
 * user already has five of them — so the copy says how many, and the count arrives as a format
 * argument from `AttachmentQuota.FREE_LIMIT` rather than being written into the sentence.
 *
 * This assertion used to live in `core/vault`, against a `const val LIMIT_MESSAGE` that no screen
 * had read since feature/files was extracted. A test is a reason for a string to exist; that one
 * was keeping an untranslatable English sentence alive in a module with no UI. Pure JVM, so it
 * reads the resource files rather than resolving ids.
 */
class QuotaMessageTest {

    private val languages = listOf("values", "values-fr", "values-de", "values-es", "values-pt")

    private fun value(dir: String): String =
        java.io.File("src/main/res/$dir/strings.xml").takeIf { it.exists() }?.readText()
            ?.let {
                Regex("""<string name="files_error_quota_exceeded"[^>]*>(.*?)</string>""",
                    RegexOption.DOT_MATCHES_ALL).find(it)?.groupValues?.get(1)
            }
            ?: fail("files_error_quota_exceeded missing from $dir/strings.xml")

    /**
     * The remedy carries a count too, for the same reason the vault list's dialog now does.
     *
     * "Remove one" was written into the sentence in all five languages. It is true at exactly the
     * cap and false above it, and above it is reachable — import is never charged against the cap.
     * A user in that state removes one, sees the identical refusal, and has been misinstructed by
     * an app holding the real number. Reported from a device 2026-08-25; the sibling assertion is
     * `feature/vaultlist`'s QuotaBlockedCopyTest.
     */
    @Test
    fun everyLanguageNamesHowManyMustGo() {
        languages.forEach { dir ->
            val text = value(dir)
            assertTrue(
                "%2\$d" in text,
                "$dir must ask for a counted number of deletions, not \"one\": \"$text\"",
            )
        }
    }

    @Test
    fun everyLanguageNamesTheNumberRatherThanTheTier() {
        languages.forEach { dir ->
            val text = value(dir)
            assertTrue(
                "%1\$d" in text,
                "$dir must place the limit with %1\$d, not write a number into the sentence: \"$text\"",
            )
        }
    }
}
