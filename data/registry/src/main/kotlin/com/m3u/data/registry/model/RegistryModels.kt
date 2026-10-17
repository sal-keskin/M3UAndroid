package com.m3u.data.registry.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `channels.json` of the registry repository: the curated channel list and its matching rules.
 */
@Serializable
data class RegistryDocument(
    val schemaVersion: Int = 1,
    val updated: String? = null,
    val title: String? = null,
    val channels: List<RegistryChannelSpec> = emptyList(),
) {
    val enabledChannels: List<RegistryChannelSpec>
        get() = channels.filter { it.enabled }.sortedBy { it.rank }
}

@Serializable
data class RegistryChannelSpec(
    // canonical iptv-org channel id, e.g. "ATV.tr"
    val id: String,
    // channel number; ordered by audience share
    val rank: Int,
    val name: String,
    val aliases: List<String> = emptyList(),
    // names that must never match this channel, whatever the score
    val exclude: List<String> = emptyList(),
    val group: String = "",
    val logo: String? = null,
    val officialLivePage: String? = null,
    // applied to every url of this channel unless the url brings its own value
    val headers: Map<String, String> = emptyMap(),
    // manual overrides, always tried first; may carry a kodi `|Header=` suffix
    val pinnedUrls: List<String> = emptyList(),
    val enabled: Boolean = true,
    val note: String? = null,
    // optional: how to find this channel's link on its own official live page, see [ResolverSpec]
    val resolver: ResolverSpec? = null,
)

/**
 * Finds a channel's free official stream on its own live page, on the viewer's device only.
 * Resolved links are never written back to the registry or `playlist.m3u`.
 *
 * Only add one after reviewing the site's terms of use and robots.txt; record the review in
 * [termsReviewed].
 */
@Serializable
data class ResolverSpec(
    // "webview": read the page, else load it in a hidden WebView and capture the player's request;
    // "youtube": the page embeds the broadcaster's own YouTube live; hand it to the YouTube app
    val type: String,
    // defaults to officialLivePage
    val page: String? = null,
    // regular expression the stream url must contain, e.g. to skip a sister channel on the same page
    val match: String? = null,
    // the stream must be served from these hosts (or their subdomains): the broadcaster's own CDN
    val hosts: List<String> = emptyList(),
    // a resolved link older than this is resolved again before playback (tokens expire)
    val ttlMinutes: Int = DEFAULT_TTL_MINUTES,
    val terms: String? = null,
    val termsReviewed: String? = null,
) {
    companion object {
        const val TYPE_WEBVIEW = "webview"
        const val TYPE_YOUTUBE = "youtube"
        const val DEFAULT_TTL_MINUTES = 60
    }
}

/**
 * `sources.json` of the registry repository: where candidate urls come from.
 * Adding a source is a data change only.
 */
@Serializable
data class SourcesDocument(
    val schemaVersion: Int = 1,
    val sources: List<SourceSpec> = emptyList(),
    // urls on these hosts (or their subdomains) are never used
    val blockedHosts: List<String> = emptyList(),
    // broadcasters' own CDNs; when the list is not empty, urls on other hosts rank lower
    val officialHosts: List<String> = emptyList(),
)

@Serializable
data class SourceSpec(
    val id: String,
    // "m3u" or "iptvorg-api"
    val type: String,
    val url: String,
    // 0..1, multiplies the match tier weight when ranking
    val trust: Double = 0.5,
    // iptvorg-api only: keep channels of this ISO country code
    val filterCountry: String? = null,
    val enabled: Boolean = true,
) {
    companion object {
        const val TYPE_M3U = "m3u"
        const val TYPE_IPTVORG_API = "iptvorg-api"
    }
}

val RegistryJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
    prettyPrint = true
}
