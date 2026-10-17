package com.m3u.data.repository.linkupdate

import androidx.compose.runtime.Immutable
import com.m3u.data.database.model.Playlist
import com.m3u.data.registry.match.MatchTier
import com.m3u.data.registry.validate.StreamStatus
import kotlinx.serialization.Serializable

/**
 * App-provided registry settings. The TV fork provides it from `AppModule`; apps without a
 * registry provide nothing and every link-update entry point stays inert.
 *
 * @param defaultBaseUrl registry repository root, e.g.
 * `https://raw.githubusercontent.com/sal-keskin/iptv/main`
 * @param bundledAssetDirectory assets folder holding the first-run snapshot
 * (`channels.json`, `sources.json`, `playlist.m3u`)
 */
data class LinkRegistryConfig(
    val defaultBaseUrl: String,
    val bundledAssetDirectory: String? = null,
    val defaultTitle: String = "TR TV",
)

object LinkRegistry {
    // stable synthetic playlist url: the registry base url is editable without re-keying channels
    const val PLAYLIST_URL = "${Playlist.URL_REGISTRY_SCHEME}default"

    // `{registry}` in sources.json urls is replaced with the configured base url
    const val BASE_URL_PLACEHOLDER = "{registry}"
}

val Playlist.isRegistry: Boolean get() = url == LinkRegistry.PLAYLIST_URL

fun isRegistryPlaylistUrl(url: String?): Boolean = url == LinkRegistry.PLAYLIST_URL

enum class LinkUpdateTrigger {
    FIRST_RUN, MANUAL, AUTO, PERIODIC, REPAIR
}

data class LinkUpdateRequest(
    val trigger: LinkUpdateTrigger,
    // null updates every enabled channel
    val channelId: String? = null,
    val validate: Boolean = true,
    // only use the bundled snapshot, no network (first run)
    val offline: Boolean = false,
)

@Immutable
sealed interface LinkUpdateProgress {
    data object Idle : LinkUpdateProgress

    data class Running(
        val phase: Phase,
        val done: Int,
        val total: Int,
        val channelId: String? = null,
    ) : LinkUpdateProgress

    data class Finished(val report: LinkUpdateReport) : LinkUpdateProgress

    data class Failed(val channelId: String?) : LinkUpdateProgress

    enum class Phase { FETCHING, MATCHING, VALIDATING, SAVING }
}

@Immutable
@Serializable
data class LinkUpdateReport(
    val trigger: String,
    val channelId: String? = null,
    val startedAt: Long,
    val finishedAt: Long,
    val channels: Int,
    val updated: Int,
    val unchanged: Int,
    val failing: Int,
    val newFuzzy: Int,
    val validated: Boolean,
    // source id → error, for sources that could not be fetched and had no cached copy
    val sourceErrors: Map<String, String> = emptyMap(),
    val registryFromCache: Boolean = false,
)

/**
 * One playable url of a registry channel, in playback order.
 */
@Immutable
data class PlaybackStream(
    val url: String,
    val headers: Map<String, String>,
    val sourceId: String,
    val height: Int?,
)

@Immutable
data class ChannelDiagnostics(
    val canonicalId: String,
    val rank: Int,
    val title: String,
    val officialLivePage: String?,
    val stale: Boolean,
    val updatedAt: Long,
    val streams: List<StreamDiagnostics>,
) {
    val primary: StreamDiagnostics? get() = streams.firstOrNull()
    val working: Boolean get() = streams.any { it.status == StreamStatus.OK }
}

@Immutable
data class StreamDiagnostics(
    val url: String,
    val position: Int,
    val sourceId: String,
    val tier: MatchTier,
    val score: Double,
    val status: StreamStatus,
    val latencyMs: Long?,
    val height: Int?,
    val failureReason: String?,
    val checkedAt: Long,
    val lastOkAt: Long,
    val failureCount: Int,
    val hasHeaders: Boolean,
)
