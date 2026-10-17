package com.m3u.data.service

import android.graphics.Rect
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.parser.xtream.XtreamEpisodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

interface PlayerManager {
    val player: StateFlow<Player?>
    val size: StateFlow<Rect>

    val channel: StateFlow<Channel?>
    val playlist: StateFlow<Playlist?>

    val playbackState: StateFlow<@Player.State Int>
    val playbackException: StateFlow<PlaybackException?>
    val isPlaying: StateFlow<Boolean>

    val tracksGroups: StateFlow<List<Tracks.Group>>
    val cacheSpace: Flow<Long>

    fun chooseTrack(group: TrackGroup, index: Int)
    fun clearTrack(type: @C.TrackType Int)
    suspend fun play(
        command: MediaCommand,
        applyContinueWatching: Boolean = true
    )
    suspend fun replay()
    fun release()
    fun clearCache()
    fun pauseOrContinue(value: Boolean)
    fun updateSpeed(race: Float)

    suspend fun recordVideo(uri: Uri)

    val cwPosition: SharedFlow<Long>
    suspend fun onResetPlayback(channelUrl: String)
    suspend fun getCwPosition(channelUrl: String): Long
    suspend fun reloadThumbnail(channelUrl: String): Uri?
    suspend fun syncThumbnail(channelUrl: String): Uri?

    /**
     * Fallback urls of the playing channel. Only channels of a channel-registry playlist have
     * several; null for every other channel.
     */
    val sourceState: StateFlow<PlaybackSourceState?>

    /**
     * One-shot notices about automatic source handling, for transient UI such as a toast.
     */
    val sourceEvents: SharedFlow<PlaybackSourceEvent>

    /**
     * Moves the position by [deltaMs] inside the seekable (timeshift) window.
     */
    fun seekBy(deltaMs: Long)

    /**
     * Jumps back to the live edge of a live stream.
     */
    fun seekToLive()
}

@Immutable
data class PlaybackSourceState(
    // index of the url being played, 0 is the primary
    val index: Int,
    val count: Int,
    val sourceId: String?,
    val height: Int?,
    // every url failed
    val exhausted: Boolean,
)

@Immutable
sealed interface PlaybackSourceEvent {
    // the current url failed and the next fallback is being tried
    data class Switching(val index: Int, val count: Int) : PlaybackSourceEvent

    // a paused or rewound position fell out of the timeshift window
    data object BackToLive : PlaybackSourceEvent

    // no url of the channel plays
    data object Exhausted : PlaybackSourceEvent
}

@Immutable
data class PlayerTrack(
    val type: @C.TrackType Int,
    val group: TrackGroup,
    val index: Int,
    val format: Format
)

@Immutable
sealed class MediaCommand(open val channelId: Int) {
    data class Common(override val channelId: Int) : MediaCommand(channelId)
    data class XtreamEpisode(
        override val channelId: Int,
        val episode: XtreamEpisodeInfo
    ) : MediaCommand(channelId)
}

val PlayerManager.tracks: Flow<Map<@C.TrackType Int, List<PlayerTrack>>>
    get() = tracksGroups.mapLatest { all ->
        // Group all tracks by their type
        all.groupBy { it.type }
            .mapValues { (_, innerGroups) ->
                // For each group, flatten the list of tracks and filter out unsupported tracks
                innerGroups.flatMap { trackGroup ->
                    List(trackGroup.length) { index ->
                        trackGroup.takeIf { it.isTrackSupported(index) }?.let {
                            PlayerTrack(
                                type = it.type,
                                group = it.mediaTrackGroup,
                                index = index,
                                format = it.getTrackFormat(index)
                            )
                        }
                    }.filterNotNull()
                }
            }
    }.flowOn(Dispatchers.IO)

val PlayerManager.currentTracks: Flow<Map<@C.TrackType Int, PlayerTrack?>>
    get() = tracksGroups.mapLatest { currentTracksGroups ->
        currentTracksGroups
            .groupBy { it.type }
            .mapValues { (_, tracksGroups) ->
                tracksGroups
                    .asSequence()
                    .mapNotNull { trackGroup ->
                        (0 until trackGroup.length)
                            .firstOrNull {
                                trackGroup.isTrackSupported(it) &&
                                        trackGroup.isTrackSelected(it)
                            }
                            ?.let {
                                PlayerTrack(
                                    type = trackGroup.type,
                                    group = trackGroup.mediaTrackGroup,
                                    index = it,
                                    format = trackGroup.getTrackFormat(it)
                                )
                            }
                    }
                    .firstOrNull()
            }
    }.flowOn(Dispatchers.IO)