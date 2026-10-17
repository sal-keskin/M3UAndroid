package com.m3u.data.parser.m3u

import dev.oxyroid.parser.m3u.M3UPlaylistParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class M3UDataHeadersTest {
    private fun parse(playlist: String) = M3UPlaylistParser
        .parse(playlist.trimIndent().byteInputStream())
        .map { it.toChannel(playlistUrl = "https://registry.example/playlist.m3u") }
        .toList()

    @Test
    fun `extvlcopt headers reach the channel`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="KanalD.tr",Kanal D
            #EXTVLCOPT:http-referrer=https://www.kanald.com.tr/
            #EXTVLCOPT:http-user-agent=Mozilla/5.0
            #EXTVLCOPT:http-origin=https://www.kanald.com.tr
            https://cdn.example/kanald.m3u8
            """
        ).single()
        assertEquals("https://cdn.example/kanald.m3u8", channel.url)
        assertEquals(
            mapOf(
                "Referer" to "https://www.kanald.com.tr/",
                "User-Agent" to "Mozilla/5.0",
                "Origin" to "https://www.kanald.com.tr",
            ),
            channel.headers,
        )
        assertEquals("KanalD.tr", channel.relationId)
    }

    @Test
    fun `kodi suffix is stripped from the url and wins over vlc options`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="ATV.tr",ATV
            #EXTVLCOPT:http-referrer=https://old.example/
            https://cdn.example/atv.m3u8?a=1|Referer=https%3A%2F%2Fwww.atv.com.tr%2F&User-Agent=TV
            """
        ).single()
        assertEquals("https://cdn.example/atv.m3u8?a=1", channel.url)
        assertEquals(mapOf("Referer" to "https://www.atv.com.tr/", "User-Agent" to "TV"), channel.headers)
    }

    @Test
    fun `channels without headers keep null`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1,Plain
            https://cdn.example/plain.m3u8
            """
        ).single()
        assertNull(channel.headers)
    }
}
