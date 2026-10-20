package com.localkeep.credential

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.localkeep.core.model.Login
import com.localkeep.core.model.Passkey
import com.localkeep.core.vault.VaultRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal object CredentialEntryFactory {
    fun response(
        context: Context,
        request: BeginGetCredentialRequest,
        caller: CallerIdentity,
        repository: VaultRepository,
        // False when the caller is a native app whose signing certificate did not verify against the
        // pinned identity — see LocalCredentialProviderService. Passkeys carry their own per-item
        // cert check (RpIdValidator) so they are unaffected; only the password path is gated here.
        includePasswords: Boolean,
    ): BeginGetCredentialResponse {
        val entries = request.beginGetCredentialOptions.flatMap { option ->
            when (option) {
                is BeginGetPublicKeyCredentialOption ->
                    passkeyEntries(context, option, caller, repository)

                is BeginGetPasswordOption ->
                    if (includePasswords) passwordEntries(context, option, caller, repository) else emptyList()

                else -> emptyList()
            }
        }
        return BeginGetCredentialResponse(credentialEntries = entries)
    }

    private fun passkeyEntries(
        context: Context,
        option: BeginGetPublicKeyCredentialOption,
        caller: CallerIdentity,
        repository: VaultRepository,
    ) = runCatching {
        val options = PasskeySigner.parseRequestJson(option.requestJson, caller.origin)
        // Through the projection: a concealed passkey offers no credential entry at all. This is the
        // list Android renders in its own credential sheet, outside this app's UI, so a raw read here
        // would put a hidden entry on screen in the one place the user cannot dismiss it.
        repository.visibilitySnapshot().liveItems.mapNotNull { item ->
            val passkey = item.content as? Passkey ?: return@mapNotNull null
            if (
                !PasskeySigner.isAllowed(passkey, options) ||
                !RpIdValidator.validateForAssertion(caller, item, passkey.rpId)
            ) {
                return@mapNotNull null
            }
            PublicKeyCredentialEntry.Builder(
                context,
                passkey.userName.ifBlank { item.title },
                itemPendingIntent(context, item.id.toString(), option.id, CREDENTIAL_TYPE_PASSKEY),
                option,
            )
                .setDisplayName(
                    passkeyEntryDisplayName(
                        template = context.getString(R.string.credential_passkey_entry_display_name),
                        name = passkey.userDisplayName.ifBlank { item.title },
                        createdAt = item.createdAt,
                        locale = context.resources.configuration.locales[0],
                    ),
                )
                .build()
        }
    }.getOrDefault(emptyList())

    private fun passwordEntries(
        context: Context,
        option: BeginGetPasswordOption,
        caller: CallerIdentity,
        repository: VaultRepository,
    ) = runCatching {
        // Wrapped the way `passkeyEntries` is, and this was the one branch of `response` that was
        // not: a single row the builder refused took the whole BeginGetCredentialResponse with it,
        // every passkey entry included, and surfaced as "could not read the vault".
        PasswordProvider.matchingLogins(repository, caller).map { item ->
            val login = item.content as Login
            PasswordCredentialEntry.Builder(
                context,
                // `Login.username` defaults to "" and nothing forces it non-blank — the editor
                // validates the title alone, and a KDBX entry routinely carries a password with no
                // user name. Unlabelled, the row is nameless in the one credential surface outside
                // this app's UI. Same fallback, for the same reason, as `passkeyEntries` above.
                login.username.ifBlank { item.title },
                itemPendingIntent(context, item.id.toString(), option.id, CREDENTIAL_TYPE_PASSWORD),
                option,
            )
                .setDisplayName(item.title)
                .build()
        }
    }.getOrDefault(emptyList())

    private fun itemPendingIntent(
        context: Context,
        itemId: String,
        optionId: String,
        type: String,
    ): PendingIntent {
        val identity = "$type:$itemId:$optionId"
        val intent = Intent(context, CredentialActivity::class.java).apply {
            action = ACTION_GET
            data = Uri.Builder()
                .scheme("localkeep")
                .authority("credential")
                .appendPath("get")
                // See the same call in LocalCredentialProviderService: `hashCode()` is 32 bits and
                // the data URI is the only thing keeping two entries' PendingIntents apart under
                // FLAG_UPDATE_CURRENT.
                .appendPath(OpaqueKey.of(identity))
                .build()
            putExtra(EXTRA_ITEM_ID, itemId)
            // Already part of `identity`; carrying it as an extra too is what lets
            // CredentialActivity answer a request that holds more than one option.
            putExtra(EXTRA_CREDENTIAL_TYPE, type)
        }
        return PendingIntent.getActivity(
            context,
            identity.hashCode(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/**
 * The display name one passkey entry carries in Android's own credential sheet: the account name,
 * then the day the passkey was made.
 *
 * Several passkeys for one account used to arrive as a single unselectable row. LocalKeep emits one
 * entry per matching passkey — that half was always right — but the platform collapses rows whose
 * user name *and* display name are equal, and a second passkey for the same account has both. So a
 * vault holding three `example.test` passkeys showed one row, and a register-then-sign-in returned
 * an older credential than the one created seconds earlier (2C-F13, measured on device 2026-09-20:
 * create returned `b29CFhN6…`, the assertion `J1-gReDM…`).
 *
 * The date is the one thing that always differs between two passkeys for the same account, and it
 * is also what a user would pick by. `MEDIUM` and [locale]'s own convention, for the reason
 * `recoveryKitFooterDate` gives: it spells the month, so it is not ambiguous between `MM/DD` and
 * `DD/MM` readers, and this app ships in five languages.
 *
 * Only the label changes. The user handle and credential id the relying party sees are untouched —
 * this text never leaves the picker.
 */
internal fun passkeyEntryDisplayName(
    template: String,
    name: String,
    createdAt: Instant,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String = String.format(
    locale,
    template,
    name,
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(createdAt.atZone(zone)),
)
