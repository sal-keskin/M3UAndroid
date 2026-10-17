package com.m3u.data.registry.source

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackHeadersTest {
    @Test
    fun `kodi suffix is split into url and canonical headers`() {
        val split = PlaybackHeaders.splitKodiUrl(
            "https://cdn.example.tr/live/atv.m3u8?token=a|Referer=https%3A%2F%2Fwww.atv.com.tr%2F&User-Agent=Mozilla/5.0 (Linux)&Origin=https://www.atv.com.tr"
        )
        assertEquals("https://cdn.example.tr/live/atv.m3u8?token=a", split.url)
        assertEquals(
            mapOf(
                "Referer" to "https://www.atv.com.tr/",
                "User-Agent" to "Mozilla/5.0 (Linux)",
                "Origin" to "https://www.atv.com.tr",
            ),
            split.headers,
        )
    }

    @Test
    fun `kodi suffix keys are case insensitive and unknown keys are dropped`() {
        val split = PlaybackHeaders.splitKodiUrl("http://a/b.m3u8|referer=http://r/&user-agent=UA&Cookie=x&!verifypeer=false")
        assertEquals("http://a/b.m3u8", split.url)
        assertEquals(mapOf("Referer" to "http://r/", "User-Agent" to "UA"), split.headers)
    }

    @Test
    fun `urls without kodi options are unchanged`() {
        assertEquals(
            PlaybackHeaders.SplitUrl("https://a/b.m3u8?x=1", emptyMap()),
            PlaybackHeaders.splitKodiUrl("https://a/b.m3u8?x=1"),
        )
        assertEquals(
            PlaybackHeaders.SplitUrl("https://a/b|c", emptyMap()),
            PlaybackHeaders.splitKodiUrl("https://a/b|c"),
        )
    }

    @Test
    fun `vlc options map to canonical headers`() {
        val headers = PlaybackHeaders.fromOptions(
            mapOf(
                "http-referrer" to "https://www.kanald.com.tr/",
                "http-user-agent" to "Mozilla/5.0",
                "http-origin" to "https://www.kanald.com.tr",
                "network-caching" to "1000",
                "http-reconnect" to "true",
            )
        )
        assertEquals(
            mapOf(
                "Referer" to "https://www.kanald.com.tr/",
                "User-Agent" to "Mozilla/5.0",
                "Origin" to "https://www.kanald.com.tr",
            ),
            headers,
        )
    }

    @Test
    fun `kodi url round trips`() {
        val headers = mapOf("Referer" to "https://x.tr/", "User-Agent" to "Mozilla/5.0 (X11; Linux)")
        val url = PlaybackHeaders.toKodiUrl("https://a/b.m3u8", headers)
        assertEquals(PlaybackHeaders.SplitUrl("https://a/b.m3u8", headers), PlaybackHeaders.splitKodiUrl(url))
    }
}
