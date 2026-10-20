package com.localkeep.feature.itemedit

import androidx.annotation.StringRes

/**
 * A human label for a custom field whose *name* is a key the app wrote, not one a person typed.
 *
 * 4A-F9, 2026-09-20: a passkey created for an Android app carries its anti-phishing binding in two
 * custom fields, and the passkey detail drew them under the raw keys —
 * `CREDENTIAL.BOUNDPACKAGE` and `CREDENTIAL.BOUNDCERTSHA256`, in the CUSTOM FIELDS group, each with
 * its own Show and Copy. They are plumbing, and they read as a fault in the app rather than as a
 * fact about the credential.
 *
 * **The stored key is not touched.** It is written and read by `:credential`
 * (`RpIdValidator.BOUND_PACKAGE_FIELD` / `BOUND_CERT_FIELD`) as an exact-match lookup on every
 * ceremony, so it has to stay locale-independent and stable across a vault that moves between
 * devices and languages. The mapping is therefore display-time only, and a name this function does
 * not know falls through to what the user typed — which is every ordinary custom field.
 *
 * The literals are duplicated here rather than imported: `:feature:itemedit` does not depend on
 * `:credential`, and those constants are `internal` to it. `PasskeyBindingFieldNamesMatchTest`
 * fails if the two ever disagree, which is the check that makes the duplication safe.
 */
@StringRes
internal fun wellKnownCustomFieldLabelRes(name: String): Int? = when (name) {
    BOUND_PACKAGE_FIELD -> R.string.itemedit_custom_field_bound_package
    BOUND_CERT_FIELD -> R.string.itemedit_custom_field_bound_cert
    else -> null
}

internal const val BOUND_PACKAGE_FIELD = "credential.boundPackage"
internal const val BOUND_CERT_FIELD = "credential.boundCertSha256"
