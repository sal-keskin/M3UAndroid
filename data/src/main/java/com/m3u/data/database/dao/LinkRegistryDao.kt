package com.m3u.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.m3u.data.database.model.ChannelStream
import com.m3u.data.database.model.RankedChannel
import com.m3u.data.database.model.RegistryChannel
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkRegistryDao {
    @Upsert
    suspend fun upsertChannels(vararg channels: RegistryChannel)

    @Query("SELECT * FROM registry_channels WHERE playlist_url = :playlistUrl ORDER BY rank")
    suspend fun getChannels(playlistUrl: String): List<RegistryChannel>

    @Query("SELECT * FROM registry_channels WHERE canonical_id = :canonicalId")
    suspend fun getChannel(canonicalId: String): RegistryChannel?

    @Query("SELECT * FROM registry_channels WHERE playlist_url = :playlistUrl ORDER BY rank")
    fun observeChannels(playlistUrl: String): Flow<List<RegistryChannel>>

    @Query(
        """
        DELETE FROM registry_channels
        WHERE playlist_url = :playlistUrl
        AND canonical_id NOT IN (:keep)
        """
    )
    suspend fun deleteChannelsNotIn(playlistUrl: String, keep: List<String>)

    @Query("SELECT * FROM channel_streams WHERE canonical_id = :canonicalId ORDER BY position")
    suspend fun getStreams(canonicalId: String): List<ChannelStream>

    @Query(
        """
        SELECT channel_streams.* FROM channel_streams
        INNER JOIN registry_channels
        ON channel_streams.canonical_id = registry_channels.canonical_id
        WHERE registry_channels.playlist_url = :playlistUrl
        ORDER BY registry_channels.rank, channel_streams.position
        """
    )
    suspend fun getStreamsByPlaylistUrl(playlistUrl: String): List<ChannelStream>

    @Query(
        """
        SELECT channel_streams.* FROM channel_streams
        INNER JOIN registry_channels
        ON channel_streams.canonical_id = registry_channels.canonical_id
        WHERE registry_channels.playlist_url = :playlistUrl
        ORDER BY registry_channels.rank, channel_streams.position
        """
    )
    fun observeStreamsByPlaylistUrl(playlistUrl: String): Flow<List<ChannelStream>>

    @Query("DELETE FROM channel_streams WHERE canonical_id = :canonicalId")
    suspend fun deleteStreams(canonicalId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStreams(vararg streams: ChannelStream)

    @Transaction
    suspend fun replaceStreams(canonicalId: String, streams: List<ChannelStream>) {
        deleteStreams(canonicalId)
        if (streams.isNotEmpty()) insertStreams(*streams.toTypedArray())
    }

    /**
     * Records a playback failure reported by the player and moves the url behind the others.
     */
    @Query(
        """
        UPDATE channel_streams
        SET status = :status,
            failure_reason = :reason,
            failure_count = failure_count + 1,
            checked_at = :checkedAt,
            position = (
                SELECT COALESCE(MAX(position), 0) + 1 FROM channel_streams
                WHERE canonical_id = :canonicalId
            )
        WHERE canonical_id = :canonicalId AND url = :url
        """
    )
    suspend fun demoteStream(
        canonicalId: String,
        url: String,
        status: String,
        reason: String,
        checkedAt: Long,
    )

    @Query(
        """
        SELECT streams.*, registry_channels.rank AS registry_rank, registry_channels.stale AS registry_stale
        FROM streams
        INNER JOIN registry_channels
        ON streams.relation_id = registry_channels.canonical_id
        AND streams.playlist_url = registry_channels.playlist_url
        WHERE streams.playlist_url = :playlistUrl
        AND streams.hidden = 0
        ORDER BY registry_channels.rank
        """
    )
    fun observeRankedChannels(playlistUrl: String): Flow<List<RankedChannel>>

    @Query(
        """
        UPDATE streams
        SET url = :url,
            http_headers = :headers,
            title = :title,
            cover = :cover,
            `group` = :category
        WHERE playlist_url = :playlistUrl AND relation_id = :canonicalId
        """
    )
    suspend fun updateChannelInPlace(
        playlistUrl: String,
        canonicalId: String,
        url: String,
        headers: Map<String, String>?,
        title: String,
        cover: String?,
        category: String,
    ): Int

    @Query(
        """
        DELETE FROM streams
        WHERE playlist_url = :playlistUrl
        AND (relation_id IS NULL OR relation_id NOT IN (:keep))
        """
    )
    suspend fun deleteChannelsOutside(playlistUrl: String, keep: List<String>)
}
