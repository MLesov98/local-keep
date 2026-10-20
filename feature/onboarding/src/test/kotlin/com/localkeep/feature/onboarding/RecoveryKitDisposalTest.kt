package com.localkeep.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [shouldForgetRecoveryKit] is the decision `RecoveryKitScreen`'s `onDispose` used to skip: it
 * called `viewModel::forget` unconditionally, so an Activity recreation from a configuration
 * change (rotation, dark-mode toggle, language switch) wiped the freshly-generated, unsaved
 * recovery mnemonic exactly as if the user had navigated away. `RecoveryKit.generate` draws fresh
 * entropy on every call, so the 24 words are gone for good — the user has to redo the ceremony
 * with a different set of words. Only a real navigate-away (`isChangingConfigurations == false`)
 * should forget the kit; a recreation should keep it, mirroring the pattern
 * `VaultListScreen`/`noteLeavingList` already uses for the same class of bug.
 */
class RecoveryKitDisposalTest {

    @Test
    fun `keeps the prepared kit across a configuration change`() {
        assertFalse(shouldForgetRecoveryKit(isChangingConfigurations = true))
    }

    @Test
    fun `forgets the prepared kit on a real navigate-away`() {
        assertTrue(shouldForgetRecoveryKit(isChangingConfigurations = false))
    }
}
