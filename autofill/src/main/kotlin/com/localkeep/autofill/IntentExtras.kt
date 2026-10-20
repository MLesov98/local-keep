package com.localkeep.autofill

import android.content.Intent
import android.os.Build
import android.os.Parcelable
import android.view.autofill.AutofillId

internal inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }

/**
 * A [Parcelable] array extra as a list, empty when absent.
 *
 * The single-value sibling above covers the one-id-per-type extras [AuthActivity] carries;
 * [PickerActivity] needs several ids of the same type, because a sign-up form's password box and
 * its confirmation box are both password fields and the generator has to reach both.
 */
internal inline fun <reified T : Parcelable> Intent.parcelableArrayExtraCompat(
    key: String,
): List<T> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableArrayExtra(key, T::class.java)?.toList().orEmpty()
} else {
    @Suppress("DEPRECATION")
    getParcelableArrayExtra(key)?.filterIsInstance<T>().orEmpty()
}

private const val EXTRA_CURRENT_PASSWORD_IDS = "com.localkeep.autofill.CURRENT_PASSWORD_IDS"

/**
 * Records which password ids are a change-password form's Current box (2026-10-02).
 *
 * Every activity that rebuilds the field list from its extras has to carry this, or the response
 * rebuilt on the other side of an unlock or a domain confirmation offers a generator that fills
 * the Current box again.
 */
internal fun Intent.putCurrentPasswordIds(fields: List<FillableField>): Intent = putExtra(
    EXTRA_CURRENT_PASSWORD_IDS,
    fields.filter { it.type == FieldType.Password && it.isCurrentPassword }.map { it.id }.toTypedArray(),
)

/** The password fields under [passwordIdsKey], each marked as [putCurrentPasswordIds] recorded it. */
internal fun Intent.passwordFields(passwordIdsKey: String): List<FillableField> {
    val current = parcelableArrayExtraCompat<AutofillId>(EXTRA_CURRENT_PASSWORD_IDS).toSet()
    return parcelableArrayExtraCompat<AutofillId>(passwordIdsKey)
        .map { FillableField(it, FieldType.Password, isCurrentPassword = it in current) }
}
