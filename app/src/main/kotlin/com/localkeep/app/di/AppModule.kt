package com.localkeep.app.di

import android.content.Context
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.location.AndroidCurrentLocationProvider
import com.localkeep.core.location.CurrentLocationProvider
import com.localkeep.core.vault.ForegroundVisibilityCoordinator
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideKeystoreManager(
        @ApplicationContext context: Context,
    ): KeystoreManager = KeystoreManager(context)

    @Provides
    @Singleton
    fun provideBiometricUnlock(
        keystoreManager: KeystoreManager,
    ): BiometricUnlock = BiometricUnlock(keystoreManager)

    @Provides
    @Singleton
    fun provideVaultLockManager(
        @ApplicationContext context: Context,
        keystoreManager: KeystoreManager,
    ): VaultLockManager = VaultLockManager(context, keystoreManager)

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    /**
     * The one binding of the platform location provider.
     *
     * Bound here rather than inside `:feature:itemedit` so the editor depends on the interface and
     * a test can hand it a fake — the whole feature is a sequence of platform outcomes (denied,
     * approximate, disabled, timed out, a fix that arrived after the screen was gone), and none of
     * them is reachable by driving a real receiver on a desk.
     */
    @Provides
    @Singleton
    fun provideCurrentLocationProvider(
        @ApplicationContext context: Context,
    ): CurrentLocationProvider = AndroidCurrentLocationProvider(context)

    @Provides
    @Singleton
    fun provideVaultRepository(
        lockManager: VaultLockManager,
        clock: Clock,
    ): VaultRepository = VaultRepository(lockManager, clock = clock)

    @Provides
    @Singleton
    fun provideForegroundVisibilityCoordinator(
        repository: VaultRepository,
        clock: Clock,
    ): ForegroundVisibilityCoordinator = ForegroundVisibilityCoordinator(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        clock = clock,
        nextExpiry = {
            (repository.presentationState.value as? VaultPresentationState.Unlocked)?.snapshot?.nextExpiry
        },
        onExpiry = { repository.refreshPresentation() },
    )
}
