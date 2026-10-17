package com.m3u.data.registry.source

import com.m3u.data.registry.match.NameNormalizer
import dev.oxyroid.parser.m3u.M3UPlaylistParser
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import java.io.InputStream
import java.net.URI
import java.util.Locale

/**
 * A stream url offered by a source, before it is matched to a registry channel.
 */
data class StreamCandidate(
    val sourceId: String,
    val trust: Double,
    val url: String,
    val name: String,
    val tvgId: String? = null,
    val alternativeName: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val heightHint: Int? = null,
    val labels: Set<String> = emptySet(),
) {
    val host: String?
        get() = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull()
}

object CandidateParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val labelPattern = Regex("\\[([^]]+)]")

    /**
     * Parses an M3U/M3U8 playlist. `#EXTVLCOPT` headers and Kodi `|Header=` suffixes are carried.
     */
    fun parseM3u(sourceId: String, trust: Double, input: InputStream): List<StreamCandidate> =
        M3UPlaylistParser.parse(input)
            .mapNotNull { parsed ->
                val (url, kodiHeaders) = PlaybackHeaders.splitKodiUrl(parsed.url)
                if (!url.isHttpUrl()) return@mapNotNull null
                val title = parsed.title.ifBlank { parsed.name }
                StreamCandidate(
                    sourceId = sourceId,
                    trust = trust,
                    url = url,
                    name = title,
                    tvgId = parsed.id.takeIf(String::isNotBlank),
                    alternativeName = parsed.name.takeIf { it.isNotBlank() && it != title },
                    headers = PlaybackHeaders.merge(PlaybackHeaders.fromOptions(parsed.options), kodiHeaders),
                    heightHint = NameNormalizer.heightHint(title),
                    labels = labels(title),
                )
            }
            .toList()

    @Serializable
    private data class IptvOrgStream(
        val channel: String? = null,
        val feed: String? = null,
        val title: String? = null,
        val url: String? = null,
        val quality: String? = null,
        val labels: List<String> = emptyList(),
        @SerialName("user_agent")
        val userAgent: String? = null,
        val referrer: String? = null,
    )

    /**
     * Streams `api/streams.json` from iptv-org without loading the whole array into memory.
     * The api has no country field: channel ids end with ".<country>", e.g. "ATV.tr".
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun parseIptvOrgApi(
        sourceId: String,
        trust: Double,
        filterCountry: String?,
        input: InputStream,
    ): List<StreamCandidate> {
        val suffix = filterCountry?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let { ".$it" }
        return json.decodeToSequence(input, IptvOrgStream.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
            .filter { stream ->
                suffix == null || stream.channel?.lowercase(Locale.ROOT)?.endsWith(suffix) == true
            }
            .mapNotNull { stream ->
                val url = stream.url?.trim()?.takeIf { it.isHttpUrl() } ?: return@mapNotNull null
                val title = stream.title?.trim().orEmpty()
                StreamCandidate(
                    sourceId = sourceId,
                    trust = trust,
                    url = url,
                    name = title,
                    tvgId = stream.channel?.let { channel ->
                        stream.feed?.let { "$channel@$it" } ?: channel
                    },
                    headers = PlaybackHeaders.merge(
                        listOfNotNull(
                            stream.referrer?.let { PlaybackHeaders.REFERER to it },
                            stream.userAgent?.let { PlaybackHeaders.USER_AGENT to it },
                        ).toMap().filterValues(String::isNotBlank)
                    ),
                    heightHint = stream.quality?.let(NameNormalizer::heightHint)
                        ?: NameNormalizer.heightHint(title),
                    labels = stream.labels.map { it.lowercase(Locale.ROOT) }.toSet() + labels(title),
                )
            }
            .toList()
    }

    private fun labels(title: String): Set<String> = labelPattern.findAll(title)
        .map { it.groupValues[1].trim().lowercase(Locale.ROOT) }
        .toSet()

    private fun String.isHttpUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
}
