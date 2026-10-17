package com.m3u.data.parser.m3u

import androidx.core.net.toUri
import com.m3u.data.database.model.Channel
import com.m3u.data.registry.source.PlaybackHeaders
import dev.oxyroid.parser.protocol.ParsedChannel

internal typealias M3UData = ParsedChannel

internal fun M3UData.toChannel(
    playlistUrl: String,
    seen: Long = 0L
): Channel {
    val fileScheme = "file:///"
    // kodi adaptive: 'url|Referer=..&User-Agent=..' carries request headers after the pipe.
    val (playableUrl, kodiHeaders) = PlaybackHeaders.splitKodiUrl(url)
    val absoluteUrl = if (!playableUrl.startsWith(fileScheme)) playableUrl
    else {
        with(playlistUrl.toUri()) {
            val paths = pathSegments.dropLast(1) + playableUrl.drop(fileScheme.length)
            buildUpon()
                .path(
                    paths.joinToString("/", "", "")
                )
                .build()
                .toString()
        }
    }

    /**
     * kodi adaptive: 'tvg-id' corresponds to 'channel-id' field in the EPG xml file.
     * If missing from the M3U file, the addon will use the 'tvg-name' tag to map the channel to the EPG.
     *
     * https://kodi.wiki/view/Add-on:PVR_IPTV_Simple_Client#Usage
     */
    val relationId = id.ifEmpty { name }

    return Channel(
        url = absoluteUrl,
        category = group,
        title = title,
        cover = cover,
        playlistUrl = playlistUrl,
        seen = seen,
        licenseType = licenseType,
        licenseKey = licenseKey,
        relationId = relationId,
        // '#EXTVLCOPT' options first; the kodi url suffix wins when both name a header.
        headers = PlaybackHeaders
            .merge(PlaybackHeaders.fromOptions(options), kodiHeaders)
            .takeIf { it.isNotEmpty() }
    )
}
