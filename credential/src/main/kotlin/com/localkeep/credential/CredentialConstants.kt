package com.localkeep.credential

internal const val EXTRA_ITEM_ID = "com.localkeep.credential.ITEM_ID"

/**
 * Which option of the request this entry was built for — `"passkey"` or `"password"`.
 *
 * A single `GetCredentialRequest` may carry several `CredentialOption`s; the standard Credential
 * Manager sign-in pattern sends `GetPasswordOption` and `GetPublicKeyCredentialOption` together.
 * The entry knows which one it answers, and the activity cannot re-derive it (identifying the item
 * needs an unlocked vault, which is the very thing verification is deciding), so it is carried.
 */
internal const val EXTRA_CREDENTIAL_TYPE = "com.localkeep.credential.CREDENTIAL_TYPE"

internal const val CREDENTIAL_TYPE_PASSKEY = "passkey"
internal const val CREDENTIAL_TYPE_PASSWORD = "password"
internal const val ACTION_CREATE = "com.localkeep.credential.CREATE"
internal const val ACTION_GET = "com.localkeep.credential.GET"
internal const val ACTION_UNLOCK = "com.localkeep.credential.UNLOCK"
