package com.localkeep.feature.health.di

import com.localkeep.core.health.BreachFilter
import com.localkeep.core.health.BreachFilterSource
import com.localkeep.core.health.SecurityDashboard
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.health.forgetStrengthScoresOnLock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object HealthModule {

    @Provides
    @Singleton
    fun provideBreachFilter(source: BreachFilterSource): BreachFilter = BreachFilter(source = source)

    @Provides
    @Singleton
    fun provideSecurityDashboard(
        breachFilter: BreachFilter,
        repository: VaultRepository,
    ): SecurityDashboard = SecurityDashboard(breachFilter).also { dashboard ->
        // The dashboard's strength cache and the thing that wipes it are created together, on a
        // scope with the dashboard's own lifetime — see `forgetStrengthScoresOnLock` for why the
        // ViewModel cannot own this. The scope is never cancelled because the singleton it guards
        // is never released; it holds one collector on an already-hot StateFlow.
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
            .forgetStrengthScoresOnLock(repository.presentationState, dashboard::forgetStrengthScores)
    }
}
