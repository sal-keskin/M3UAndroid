package com.m3u.data.database.model

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A channel of the curated registry playlist, keyed by its canonical (iptv-org) channel id.
 *
 * The playable [Channel] row lives in `streams` with `relation_id = canonical_id`; this row adds the
 * registry-only facts: the channel number ([rank]) and the link-update state.
 */
@Entity(
    tableName = "registry_channels",
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["url"],
            childColumns = ["playlist_url"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["playlist_url"])],
)
@Immutable
data class RegistryChannel(
    @PrimaryKey
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String,
    @ColumnInfo(name = "playlist_url")
    val playlistUrl: String,
    @ColumnInfo(name = "rank")
    val rank: Int,
    @ColumnInfo(name = "official_live_page", defaultValue = "NULL")
    val officialLivePage: String? = null,
    // every candidate failed during the last update; the last known good streams were kept
    @ColumnInfo(name = "stale", defaultValue = "0")
    val stale: Boolean = false,
    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,
)

/**
 * One candidate stream url of a registry channel. [position] 0 is the primary, the rest are
 * fallbacks in playback order.
 */
@Entity(
    tableName = "channel_streams",
    primaryKeys = ["canonical_id", "url"],
    foreignKeys = [
        ForeignKey(
            entity = RegistryChannel::class,
            parentColumns = ["canonical_id"],
            childColumns = ["canonical_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
@Immutable
data class ChannelStream(
    @ColumnInfo(name = "canonical_id")
    val canonicalId: String,
    @ColumnInfo(name = "url")
    val url: String,
    @ColumnInfo(name = "position")
    val position: Int,
    @ColumnInfo(name = "headers", defaultValue = "NULL")
    val headers: Map<String, String>? = null,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    // see com.m3u.data.registry.match.MatchTier
    @ColumnInfo(name = "match_tier")
    val matchTier: Int,
    @ColumnInfo(name = "match_score")
    val matchScore: Double,
    // see com.m3u.data.registry.validate.StreamStatus
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "latency_ms", defaultValue = "NULL")
    val latencyMs: Long? = null,
    @ColumnInfo(name = "height", defaultValue = "NULL")
    val height: Int? = null,
    @ColumnInfo(name = "failure_reason", defaultValue = "NULL")
    val failureReason: String? = null,
    @ColumnInfo(name = "checked_at", defaultValue = "0")
    val checkedAt: Long = 0L,
    @ColumnInfo(name = "last_ok_at", defaultValue = "0")
    val lastOkAt: Long = 0L,
    @ColumnInfo(name = "failure_count", defaultValue = "0")
    val failureCount: Int = 0,
    @ColumnInfo(name = "first_seen_at", defaultValue = "0")
    val firstSeenAt: Long = 0L,
)

/**
 * A registry [Channel] joined with its channel number.
 */
@Immutable
data class RankedChannel(
    @Embedded
    val channel: Channel,
    @ColumnInfo(name = "registry_rank")
    val rank: Int,
    @ColumnInfo(name = "registry_stale")
    val stale: Boolean,
)
