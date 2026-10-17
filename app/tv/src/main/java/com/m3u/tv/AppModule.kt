@file:Suppress("unused")

package com.m3u.tv

import com.m3u.core.foundation.architecture.Publisher
import com.m3u.data.repository.linkupdate.LinkRegistryConfig
import com.m3u.data.repository.tv.TvRemoteControlConfig
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface AppModule {
    @Binds
    @Singleton
    fun bindPublisher(provider: AppPublisher): Publisher

    companion object {
        /**
         * The curated Turkish channel registry. The base url is editable in Settings; the bundled
         * snapshot in assets/registry seeds the lineup on first launch without network.
         */
        @Provides
        @Singleton
        fun provideLinkRegistryConfig(): LinkRegistryConfig = LinkRegistryConfig(
            defaultBaseUrl = BuildConfig.REGISTRY_BASE_URL,
            bundledAssetDirectory = "registry",
            defaultTitle = "TR TV",
        )

        /**
         * No phone-remote server: it costs a Netty server and a network broadcast on every launch,
         * and the TV remote already does everything.
         */
        @Provides
        @Singleton
        fun provideTvRemoteControlConfig(): TvRemoteControlConfig = TvRemoteControlConfig(broadcastOnTv = false)
    }
}
