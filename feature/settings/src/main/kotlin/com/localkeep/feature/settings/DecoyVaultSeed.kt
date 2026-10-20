package com.localkeep.feature.settings

import android.content.Context
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Builds the decoy vault installed by duress setup.
 *
 * An EMPTY decoy defeats the entire point: a coerced user who opens a vault containing nothing is
 * obviously hiding a real one (UI/UX spec §10.3 — "the decoy never looks empty/suspicious"). So the
 * decoy is seeded with a small, plausible set of everyday logins with real generated passwords and
 * staggered timestamps, which the user can edit afterwards like any normal item.
 *
 * Deliberately mundane and non-financial: no bank, no crypto, no work SSO — a believable "this is
 * all I use" vault that invites no follow-up questions.
 */
internal object DecoyVaultSeed {

    private data class Seed(val title: String, val username: String, val domain: String, val ageDays: Long)

    private val SEEDS = listOf(
        Seed("Gmail", "j.harper.mail", "mail.google.com", 412),
        Seed("Netflix", "j.harper.mail@gmail.com", "netflix.com", 300),
        Seed("Spotify", "j.harper", "spotify.com", 264),
        Seed("Amazon", "j.harper.mail@gmail.com", "amazon.com", 187),
        Seed("Booking.com", "j.harper.mail@gmail.com", "booking.com", 141),
        Seed("Reddit", "harper_j", "reddit.com", 96),
        Seed("LinkedIn", "j.harper.mail@gmail.com", "linkedin.com", 62),
    )

    /**
     * A decoy [Vault] with believable contents. [now] anchors the staggered timestamps. [context]
     * resolves the two seeded strings that are genuine UI copy (the Wi-Fi item's title and note) —
     * read imperatively via [Context.getString] rather than `stringResource`, because this object is
     * plain Kotlin called from the ViewModel, not a composable (same exception `enableBiometric`'s
     * BiometricPrompt title uses).
     */
    fun build(now: Instant, context: Context): Vault {
        val items = SEEDS.map { seed ->
            val created = now.minus(seed.ageDays, ChronoUnit.DAYS)
            VaultItem(
                id = UUID.randomUUID(),
                title = seed.title,
                createdAt = created,
                updatedAt = created,
                content = Login(
                    username = seed.username,
                    password = decoyPassword(),
                    // Real password age, so a health scan of the decoy also looks lived-in.
                    passwordChangedAt = created,
                    uris = listOf(UriMatch(uri = seed.domain)),
                ),
            )
        } + VaultItem(
            id = UUID.randomUUID(),
            title = context.getString(R.string.settings_decoy_wifi_title),
            createdAt = now.minus(220, ChronoUnit.DAYS),
            updatedAt = now.minus(220, ChronoUnit.DAYS),
            content = Note(context.getString(R.string.settings_decoy_wifi_note, decoyPassword())),
        )
        return Vault(items = items, createdAt = now.minus(430, ChronoUnit.DAYS), modifiedAt = now)
    }

    private val random = SecureRandom()
    private const val ALPHABET =
        "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#\$%&*?"

    /**
     * Random 16-char passwords — a decoy full of obviously-fake values ("password123") is its own
     * tell. Generated locally rather than via `:core:generator` to avoid a module dependency that
     * exists only for dummy data.
     */
    private fun decoyPassword(): String =
        (1..16).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
}
