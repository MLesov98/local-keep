package com.localkeep.core.billing.di

import com.localkeep.core.billing.Entitlement
import com.localkeep.core.billing.PlayBillingEntitlement
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BillingModule {
    @Binds
    @Singleton
    abstract fun bindEntitlement(impl: PlayBillingEntitlement): Entitlement
}
