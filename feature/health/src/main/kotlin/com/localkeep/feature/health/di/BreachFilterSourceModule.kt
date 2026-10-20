package com.localkeep.feature.health.di

import android.content.Context
import com.localkeep.core.health.BreachFilterSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The breach-filter asset, in every flavour.
 *
 * This used to be two modules — `play` opened the asset, `foss` returned `null` — and the split was
 * about *corpus licensing*, never about tiers. The consequence was that an F-Droid user got a
 * Security screen which could not answer the one question it exists to answer. The shipped corpus
 * is derived from SecLists (`Pwdb_top-10000000`), which is MIT, so the redistribution question that
 * forced the split has an answer and the split has no reason left to exist.
 *
 * `hasData == false` is still a live state and still has to be handled: the asset can fail to open
 * or fail to parse. What no longer produces it is the flavour.
 */
@Module
@InstallIn(SingletonComponent::class)
object BreachFilterSourceModule {

    @Provides
    @Singleton
    fun provideBreachFilterSource(@ApplicationContext context: Context): BreachFilterSource =
        BreachFilterSource { context.assets.open("breached-passwords.filter") }
}
