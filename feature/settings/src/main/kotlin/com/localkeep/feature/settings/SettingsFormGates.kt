package com.localkeep.feature.settings

import com.localkeep.core.health.StrengthAnalyzer

/**
 * The terms the change-password and duress forms are submitted on, hoisted out of the composable so
 * the screen and its tests read the same rule.
 */

/**
 * Whether the change-master-password submit is live.
 *
 * It carries every term the ViewModel refuses on, the zxcvbn floor included. The floor used to be
 * enforced only inside `changePassword`, *after* the submit's `onClick` had spent the current-password
 * well, so a rule the form could state up front cost the user a credential they had typed correctly
 * (and showed the refusal twice: the inline caption, then a banner). [newPasswordScore] is the score
 * the screen already computes off the main thread; nought until it arrives, so this fails closed.
 */
internal fun changePasswordReady(
    isWorking: Boolean,
    credentialGiven: Boolean,
    newPassword: String,
    newPasswordScore: Int,
    confirmPassword: String,
): Boolean {
    val tooShort = newPassword.isNotEmpty() && newPassword.length < MIN_MASTER_PASSWORD_LENGTH
    return !isWorking &&
        credentialGiven &&
        newPassword.isNotEmpty() &&
        !tooShort &&
        newPasswordScore >= StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE &&
        newPassword == confirmPassword
}

/**
 * Whether the strength meter is drawn under the replacement password.
 *
 * Not while the replacement is below the length floor: the coral too-short line is the verdict
 * there, and a short string can still score "Excellent" on zxcvbn, which drew a jade word directly
 * under the line that contradicted it.
 */
internal fun showsStrengthMeter(newPassword: String): Boolean =
    newPassword.length >= MIN_MASTER_PASSWORD_LENGTH

/**
 * Whether the duress password is too short to be accepted, so the form can say so inline.
 *
 * The same floor the ViewModel's `setupDuress` enforces. Its refusal came after the vault-password
 * gate had been answered and the form wiped, so someone who typed eight characters filled in
 * everything, confirmed, and was told only then. An untouched well is not an error.
 *
 * Only the length can move in front of the gate. "Same as your master password" cannot: deciding it
 * needs the slot derived from the vault password the gate collects, so that refusal still arrives
 * after the dialog and keeps its banner.
 */
internal fun duressPasswordTooShort(password: String): Boolean =
    password.isNotEmpty() && password.length < MIN_MASTER_PASSWORD_LENGTH

/** Whether the duress "Set up" button is live. */
internal fun duressSetupReady(isWorking: Boolean, password: String, confirmPassword: String): Boolean =
    !isWorking &&
        password.isNotEmpty() &&
        !duressPasswordTooShort(password) &&
        password == confirmPassword
