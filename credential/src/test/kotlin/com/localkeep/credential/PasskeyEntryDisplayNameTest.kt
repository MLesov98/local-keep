package com.localkeep.credential

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two passkeys for the same account have to be two rows in the system credential sheet.
 *
 * LocalKeep emits one entry per matching passkey and always has, but Android collapses rows whose
 * user name and display name are both equal — and a second passkey for the same account has both.
 * A vault holding three `example.test` passkeys showed one unselectable row, and register-then-
 * sign-in asserted an older credential than the one created seconds earlier (2C-F13, device
 * 2026-09-20).
 *
 * The date is what distinguishes them, so the two things worth pinning are that it is *there* and
 * that two passkeys made on different days do not produce the same label. The template is asserted
 * on the source for the reason `CredentialEntryLabelTest`'s neighbours give: building a real
 * `PublicKeyCredentialEntry` needs a `Context` and a `PendingIntent`.
 */
class PasskeyEntryDisplayNameTest {

    private val template = "%1\$s · %2\$s"
    private val zone: ZoneId = ZoneId.of("UTC")

    private fun label(name: String, createdAt: Instant, locale: Locale = Locale.UK): String =
        passkeyEntryDisplayName(template, name, createdAt, locale, zone)

    /**
     * The exact month abbreviation moves with the JDK's CLDR data ("Sep" became "Sept" for en-GB),
     * so the assertion is the shape and the year rather than a transcription of one CLDR release.
     */
    @Test
    fun `the label carries the account name and the day the passkey was made`() {
        val label = label("alice", Instant.parse("2026-09-20T11:00:00Z"))
        assertTrue("expected the account name first, got: $label", label.startsWith("alice · "))
        val date = label.removePrefix("alice · ")
        assertTrue("expected a creation date after the separator, got: $label", "2026" in date)
        assertTrue("expected a spelled month, not a numeric-only date, got: $label", date.any { it.isLetter() })
    }

    @Test
    fun `two passkeys for one account are two different labels`() {
        val older = label("passkey@example.test", Instant.parse("2026-09-17T09:00:00Z"))
        val newer = label("passkey@example.test", Instant.parse("2026-09-20T09:00:00Z"))
        assertNotEquals(
            "Two passkeys for the same account must not produce the same display name — equal " +
                "user name and equal display name is exactly what makes the platform collapse " +
                "them into one unselectable row.",
            older,
            newer,
        )
    }

    /** The reader's own convention, not a fixed one — this app ships in five languages. */
    @Test
    fun `the date follows the locale`() {
        val at = Instant.parse("2026-09-20T11:00:00Z")
        assertNotEquals(label("alice", at, Locale.US), label("alice", at, Locale.GERMANY))
    }

    @Test
    fun `the passkey entry is labelled through this function`() {
        val source =
            File("src/main/kotlin/com/localkeep/credential/CredentialEntryFactory.kt").readText()
        val body = source.substringAfter("private fun passkeyEntries(")
            .substringBefore("private fun passwordEntries(")
        assertTrue(
            "passkeyEntries must build its display name through passkeyEntryDisplayName, or two " +
                "passkeys for one account collapse to one row again. Found: $body",
            "passkeyEntryDisplayName(" in body && "createdAt = item.createdAt" in body,
        )
    }
}
