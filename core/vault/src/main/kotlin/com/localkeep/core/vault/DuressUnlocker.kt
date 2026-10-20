package com.localkeep.core.vault

import com.localkeep.core.model.Vault

/**
 * Manages the independently keyed decoy vault. Constant-work slot trials remain in
 * [VaultLockManager.unlock].
 */
object DuressUnlocker {
    suspend fun setupDuress(
        lockManager: VaultLockManager,
        duressPassword: CharArray,
        decoyVault: Vault,
    ) {
        lockManager.setupDuress(duressPassword, decoyVault)
    }

    suspend fun removeDuress(lockManager: VaultLockManager) {
        lockManager.removeDuress()
    }

    /**
     * Requires a real unlocked session. Detection uses region authentication, not a visible
     * header flag.
     */
    suspend fun isConfigured(lockManager: VaultLockManager): Boolean =
        lockManager.isDuressConfigured()
}
