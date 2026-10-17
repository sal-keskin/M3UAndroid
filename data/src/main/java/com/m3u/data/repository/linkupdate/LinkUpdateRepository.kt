package com.m3u.data.repository.linkupdate

import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.RankedChannel
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Keeps the curated registry playlist's stream links fresh: fetches the registry and its sources,
 * matches candidates to channels, validates them on the device, and updates channels in place so
 * favourites, history and channel numbers survive every update.
 */
interface LinkUpdateRepository {
    /**
     * True when the app ships a registry (the TV fork); false in apps without one.
     */
    val enabled: Boolean

    fun observeRankedChannels(): Flow<List<RankedChannel>>

    fun observeDiagnostics(): Flow<List<ChannelDiagnostics>>

    /**
     * Progress of the running update or repair, then its result.
     */
    fun observeProgress(): Flow<LinkUpdateProgress>

    /**
     * State of one update or repair started with [updateNow] or [repair]: running, then
     * [LinkUpdateProgress.Finished] or [LinkUpdateProgress.Failed].
     */
    fun observeRun(id: UUID): Flow<LinkUpdateProgress>

    fun observeLastReport(): Flow<LinkUpdateReport?>

    fun observeBaseUrl(): Flow<String>

    suspend fun setBaseUrl(url: String)

    /**
     * Seeds the registry playlist from the bundled snapshot on the first launch.
     * @return true when channels were seeded
     */
    suspend fun bootstrap(): Boolean

    /**
     * Weekly refresh plus a refresh on start when the last one is older than a day; both wait
     * for an unmetered network.
     */
    suspend fun scheduleAutomaticUpdates()

    fun updateNow(): UUID

    fun repair(channelId: String): UUID

    suspend fun run(
        request: LinkUpdateRequest,
        onProgress: suspend (LinkUpdateProgress.Running) -> Unit = {},
    ): LinkUpdateReport

    /**
     * Ordered playable urls for a registry channel; empty for every other channel.
     */
    suspend fun streamsFor(channel: Channel): List<PlaybackStream>

    /**
     * Resolves a registry channel's link on its official live page again when the stored one is
     * older than the channel's resolver allows (tokenized links expire). Opt-in; returns true when
     * the stored link changed.
     */
    suspend fun refreshOfficialLink(channel: Channel): Boolean

    /**
     * The player gave up on [url]: record the failure and move it behind the other urls.
     */
    suspend fun reportPlaybackFailure(channel: Channel, url: String, reason: String)
}
