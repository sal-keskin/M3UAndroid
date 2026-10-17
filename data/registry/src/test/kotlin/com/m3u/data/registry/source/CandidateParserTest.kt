package com.m3u.data.registry.source

import kotlin.test.Test
import kotlin.test.assertEquals

class CandidateParserTest {
    @Test
    fun `m3u carries extvlcopt and kodi headers`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="KanalD.tr@SD" tvg-logo="https://logo/kd.png" group-title="Ulusal",Kanal D (1080p)
            #EXTVLCOPT:http-referrer=https://www.kanald.com.tr/
            #EXTVLCOPT:http-user-agent=Mozilla/5.0 (SMART-TV)
            https://demiroren.example/kanald/kanald.m3u8?app=web
            #EXTINF:-1 tvg-id="ATV.tr@SD",ATV (720p) [Not 24/7]
            https://cdn.example/atv/atv.m3u8|Referer=https%3A%2F%2Fwww.atv.com.tr%2F&Origin=https://www.atv.com.tr
            #EXTINF:-1,Local file
            file:///sdcard/local.m3u8
        """.trimIndent()

        val candidates = CandidateParser.parseM3u("iptvorg-tr", 0.9, playlist.byteInputStream())

        assertEquals(2, candidates.size)
        with(candidates[0]) {
            assertEquals("https://demiroren.example/kanald/kanald.m3u8?app=web", url)
            assertEquals("Kanal D (1080p)", name)
            assertEquals("KanalD.tr@SD", tvgId)
            assertEquals(1080, heightHint)
            assertEquals(
                mapOf("Referer" to "https://www.kanald.com.tr/", "User-Agent" to "Mozilla/5.0 (SMART-TV)"),
                headers,
            )
            assertEquals("iptvorg-tr", sourceId)
            assertEquals(0.9, trust)
        }
        with(candidates[1]) {
            assertEquals("https://cdn.example/atv/atv.m3u8", url)
            assertEquals(
                mapOf("Referer" to "https://www.atv.com.tr/", "Origin" to "https://www.atv.com.tr"),
                headers,
            )
            assertEquals(720, heightHint)
            assertEquals(setOf("not 24/7"), labels)
        }
    }

    @Test
    fun `iptv-org api is filtered by country suffix`() {
        val api = """
            [
              {"channel":"ATV.tr","feed":"SD","title":"ATV","url":"https://cdn/atv.m3u8","quality":"1080p","labels":[],"user_agent":null,"referrer":"https://www.atv.com.tr/"},
              {"channel":"BBCOne.uk","feed":null,"title":"BBC One","url":"https://cdn/bbc.m3u8","quality":null,"labels":[],"user_agent":null,"referrer":null},
              {"channel":null,"feed":null,"title":"Unknown","url":"https://cdn/x.m3u8","quality":null,"labels":[],"user_agent":null,"referrer":null},
              {"channel":"TRT1.tr","feed":null,"title":"TRT 1","url":"rtmp://cdn/trt1","quality":null,"labels":["Geo-blocked"],"user_agent":"UA","referrer":null}
            ]
        """.trimIndent()

        val candidates = CandidateParser.parseIptvOrgApi("iptvorg-api", 0.85, "TR", api.byteInputStream())

        assertEquals(1, candidates.size)
        with(candidates.single()) {
            assertEquals("ATV.tr@SD", tvgId)
            assertEquals(1080, heightHint)
            assertEquals(mapOf("Referer" to "https://www.atv.com.tr/"), headers)
        }
    }
}
