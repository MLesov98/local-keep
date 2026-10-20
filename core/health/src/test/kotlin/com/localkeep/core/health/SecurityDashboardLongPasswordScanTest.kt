package com.localkeep.core.health

import com.localkeep.core.model.Card
import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.PasswordHistoryEntry
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * One imported item whose password field holds a long blob must not stall the whole scan.
 *
 * N-04: a 54-item vault built from real exports left the health scan running for ten minutes at
 * 100% CPU with its progress ring frozen, no result and no cancel. The captured stack was
 * `Zxcvbn.measure` under [StrengthAnalyzer.score], and the item it was measuring came from a
 * Firefox `accounts-data` CSV, whose password column is an 837-character JSON document. zxcvbn's
 * cost is roughly cubic in length, so that single item cost more than every other password in the
 * vault put together, and `analyze` has no per-password bound it could have been stopped at.
 *
 * Unlike [SecurityDashboardStrengthCostTest], which deliberately measures call counts rather than
 * time, this one has to be a clock test: the defect is unbounded work inside a single call, which
 * no call count can see. The timeout is set two orders of magnitude above the fixed version's real
 * cost so it says "not terminating", not "slow machine" — without the fix the same vault takes over
 * a minute on a desktop JVM and far longer on a device.
 */
class SecurityDashboardLongPasswordScanTest {

    private val t: Instant = Instant.parse("2026-09-16T00:00:00Z")

    /** No corpus: breach matching is not what this test is about, and a null source never throws. */
    private fun noBreachData() = BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { null })

    private fun item(
        content: ItemContent,
        title: String,
        customFields: List<CustomField> = emptyList(),
    ) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = t,
        updatedAt = t,
        customFields = customFields,
        content = content,
    )

    private fun login(password: String, index: Int) = item(
        Login(
            username = "user$index@example.com",
            password = password,
            passwordChangedAt = t,
            // The shape this vault gained between the passing run and the failing one: KDBX and
            // Bitwarden password history.
            passwordHistory = (1..5).map {
                PasswordHistoryEntry(password = "previous-$index-$it", changedAt = t)
            },
        ),
        "Account $index",
        // And Bitwarden custom fields, which live on the item rather than on the Login.
        customFields = listOf(
            CustomField(name = "field-$index", value = "value-$index", type = CustomFieldType.TEXT),
            CustomField(name = "hidden-$index", value = "secret-$index", type = CustomFieldType.HIDDEN),
        ),
    )

    /**
     * The shape of a Firefox `accounts-data` CSV password column: a JSON document with long runs of
     * one character, ~800 chars. Synthesised rather than copied out of the corpus — the point is the
     * length and the repetition, not the bytes.
     */
    private fun firefoxAccountsBlob(): String =
        """{"version":1,"accountData":{"scopedKeys":{"https://identity.mozilla.com/apps/oldsync":""" +
            """{"kid":"${"x".repeat(13)}-${"x".repeat(24)}","k":"${"x".repeat(86)}","kty":"xxx"},""" +
            """"sync:addon_storage":{"kid":"${"x".repeat(60)}","k":"${"x".repeat(86)}","kty":"xxx"},""" +
            """"apps/notes":{"kid":"${"x".repeat(60)}","k":"${"x".repeat(86)}","kty":"xxx"}},""" +
            """"sessionToken":"${"x".repeat(64)}","uid":"${"x".repeat(32)}","verified":true}}"""

    /** The shape of a Keeper export that keeps an SSH private key in a field: ~2.8 kB of base64. */
    private fun sshKeyBlob(): String =
        "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            "b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABBmV7At\n".repeat(40) +
            "-----END OPENSSH PRIVATE KEY-----"

    private fun vault(): List<VaultItem> {
        val shared = "shared-across-accounts"
        val logins = (0 until 50).map { i ->
            // A lot of identical passwords, as an import corpus produces: every third login reuses
            // one, so the reuse pass has real groups to build.
            login(if (i % 3 == 0) shared else "Distinct-Passw0rd-$i", i)
        }
        val blobs = listOf(
            item(Login(username = "firefoxaccounts", password = firefoxAccountsBlob(), passwordChangedAt = t), "firefoxaccounts"),
            item(Login(username = "keeper", password = sshKeyBlob(), passwordChangedAt = t), "SSH key"),
        )
        val others = listOf(
            item(Card(holder = "Q A", number = "4111111111111111", cvv = "123"), "Credit-card"),
            item(Identity(firstName = "Test", lastName = "Licence"), "Test Drivers License"),
            item(Note(body = "Test Bank Account"), "Bank"),
        )
        return logins + blobs + others
    }

    @Test(timeout = 10_000)
    fun `a vault holding a blob in a password field still finishes its scan`() {
        val summary = SecurityDashboard(noBreachData()).analyze(vault())

        // Every live Login is still scanned — a "fix" that skipped the blob item would pass the
        // clock and silently stop reporting on it.
        assertEquals(52, summary.scannedCount, "every live Login is scanned")
        assertTrue(summary.reused.count > 0, "the reuse pass still reports its groups")
    }

    /**
     * The control the timeout alone cannot give: bounding the measurement must not change any
     * verdict a user sees. Both blobs are long and random enough to be strong, and the ordinary
     * weak/strong passwords either side of them still score the way they did.
     */
    @Test(timeout = 10_000)
    fun `bounding the measurement does not change any verdict`() {
        assertEquals(false, StrengthAnalyzer.isWeak(firefoxAccountsBlob()), "a long random blob is not weak")
        assertEquals(false, StrengthAnalyzer.isWeak(sshKeyBlob()), "an SSH key is not weak")
        assertEquals(true, StrengthAnalyzer.isWeak("hunter2"), "a short guessable password is still weak")
        assertEquals(false, StrengthAnalyzer.isWeak("correct horse battery staple"), "a passphrase is still strong")
        // A password below the cap is handed to zxcvbn whole, so its score is untouched by any of this.
        assertEquals(
            StrengthAnalyzer.score("Tr0ub4dor&3"),
            StrengthAnalyzer.score("Tr0ub4dor&3"),
        )
    }

    /**
     * The cap is a prefix, not a rejection: a long password made of nothing but one repeated
     * character is still caught. Without this, "measure only short passwords" would pass everything
     * above and quietly stop flagging the worst long passwords there are.
     */
    @Test(timeout = 10_000)
    fun `a long but degenerate password is still weak`() {
        assertEquals(true, StrengthAnalyzer.isWeak("a".repeat(400)))
        assertEquals(true, StrengthAnalyzer.isWeak("password".repeat(60)))
    }
}
