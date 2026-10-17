@file:Suppress("unused")

package com.m3u.data.repository

import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.repository.channel.ChannelRepositoryImpl
import com.m3u.data.repository.linkupdate.LinkRegistryConfig
import com.m3u.data.repository.linkupdate.LinkUpdateRepository
import com.m3u.data.repository.linkupdate.LinkUpdateRepositoryImpl
import com.m3u.data.repository.media.MediaRepository
import com.m3u.data.repository.media.MediaRepositoryImpl
import com.m3u.data.repository.playlist.PlaylistRepository
import com.m3u.data.repository.playlist.PlaylistRepositoryImpl
import com.m3u.data.repository.programme.ProgrammeRepository
import com.m3u.data.repository.programme.ProgrammeRepositoryImpl
import com.m3u.data.repository.tv.TvRepository
import com.m3u.data.repository.tv.TvRepositoryImpl
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface RepositoryModule {
    @Binds
    @Singleton
    fun bindPlaylistRepository(
        repository: PlaylistRepositoryImpl
    ): PlaylistRepository

    @Binds
    @Singleton
    fun bindChannelRepository(
        repository: ChannelRepositoryImpl
    ): ChannelRepository

    @Binds
    @Singleton
    fun bindProgrammeRepository(
        repositoryImpl: ProgrammeRepositoryImpl
    ): ProgrammeRepository

    @Binds
    @Singleton
    fun bindMediaRepository(
        repository: MediaRepositoryImpl
    ): MediaRepository

    @Binds
    @Singleton
    fun bindTvRepository(
        repository: TvRepositoryImpl
    ): TvRepository

    @Binds
    @Singleton
    fun bindLinkUpdateRepository(
        repository: LinkUpdateRepositoryImpl
    ): LinkUpdateRepository

    // provided by apps that ship a channel registry (the TV fork)
    @BindsOptionalOf
    fun optionalLinkRegistryConfig(): LinkRegistryConfig
}
