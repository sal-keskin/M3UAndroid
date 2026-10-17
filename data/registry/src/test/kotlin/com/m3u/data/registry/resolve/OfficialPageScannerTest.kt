package com.m3u.data.registry.resolve

import com.m3u.data.registry.model.ResolverSpec
import com.m3u.testing.mockserver.EmbeddedMockServer
import com.m3u.testing.mockserver.startEmbeddedMockServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfficialPageScannerTest {
    // the shape of Show TV's live page (2026-10-17): the channel and a sister channel side by side
    private val showTvPage = """
        <script src="https://cdn.radiantmediatechs.com/rmp/10.7.4/js/rmp.min.js"></script>
        <script>
            var videoUrl = "https://ciner.daioncdn.net/showtv/showtv.m3u8?ce=3&app=4bc8&st=RBzh&e=1664766175&tv=1";
            var showturkVideoUrl = "https://ciner-live.ercdn.net/showturk/playlist.m3u8?e=1792240827&st=gRb6&tv=1";
        </script>
    """.trimIndent()

    private val showTv = ResolverSpec(
        type = ResolverSpec.TYPE_WEBVIEW,
        match = "showtv/showtv\\.m3u8",
        hosts = listOf("daioncdn.net"),
    )

    @Test
    fun `picks the channel's own stream, not the sister channel's`() {
        assertEquals(
            listOf("https://ciner.daioncdn.net/showtv/showtv.m3u8?ce=3&app=4bc8&st=RBzh&e=1664766175&tv=1"),
            OfficialPageScanner.scanHls(showTvPage, showTv),
        )
        val resolved = OfficialPageScanner.scan(showTvPage, showTv, "https://www.showtv.com.tr/canli-yayin")
        assertEquals(ResolvedLink.Kind.HLS, resolved?.kind)
        assertEquals(
            mapOf("Referer" to "https://www.showtv.com.tr/", "Origin" to "https://www.showtv.com.tr"),
            resolved?.headers,
        )
    }

    @Test
    fun `streams off the broadcaster's hosts are ignored`() {
        val page = """var u = "https://restream.example.top/showtv/showtv.m3u8?x=1";"""
        assertTrue(OfficialPageScanner.scanHls(page, showTv).isEmpty())
    }

    @Test
    fun `escaped json urls are cleaned`() {
        val page = """{"hls":"https:\/\/ciner.daioncdn.net\/showtv\/showtv.m3u8?st=a&e=1"}"""
        assertEquals(
            listOf("https://ciner.daioncdn.net/showtv/showtv.m3u8?st=a&e=1"),
            OfficialPageScanner.scanHls(page, showTv),
        )
    }

    @Test
    fun `youtube embeds become a watch link, never a media url`() {
        val page = """<iframe title="YouTube video player" src="https://www.youtube.com/embed/ztmY_cCtUl0?si=MVRz"></iframe>"""
        val resolved = OfficialPageScanner.scan(page, ResolverSpec(type = ResolverSpec.TYPE_YOUTUBE), "https://www.szctv.com.tr/")
        assertEquals("https://www.youtube.com/watch?v=ztmY_cCtUl0", resolved?.url)
        assertEquals(ResolvedLink.Kind.YOUTUBE, resolved?.kind)
        assertTrue(OfficialPageScanner.isYoutubeUrl(resolved?.url))
        assertFalse(OfficialPageScanner.isYoutubeUrl("https://ciner.daioncdn.net/showtv/showtv.m3u8"))
        assertNull(OfficialPageScanner.scan("<html></html>", ResolverSpec(type = ResolverSpec.TYPE_YOUTUBE), "https://x/"))
    }

    @Test
    fun `official page is read with one request`() = runBlocking {
        val spec = ResolverSpec(type = ResolverSpec.TYPE_WEBVIEW, match = "linkcheck/good/master\\.m3u8", hosts = listOf("127.0.0.1"))
        val resolved = OfficialPageScanner.fetchAndScan(OkHttpClient(), "${server.baseUrl}/official/static", spec)
        assertEquals("${server.baseUrl}/linkcheck/good/master.m3u8?st=Rb6q&e=1792240827&tv=1", resolved?.url)
    }

    @Test
    fun `pages that build the link in script need the webview`() = runBlocking {
        val spec = ResolverSpec(type = ResolverSpec.TYPE_WEBVIEW, match = "linkcheck/good/master\\.m3u8")
        assertNull(OfficialPageScanner.fetchAndScan(OkHttpClient(), "${server.baseUrl}/official/script", spec))
    }

    @Test
    fun `youtube pages resolve over http`() = runBlocking {
        val resolved = OfficialPageScanner.fetchAndScan(
            OkHttpClient(),
            "${server.baseUrl}/official/youtube",
            ResolverSpec(type = ResolverSpec.TYPE_YOUTUBE),
        )
        assertEquals("https://www.youtube.com/watch?v=ztmY_cCtUl0", resolved?.url)
    }

    companion object {
        private lateinit var server: EmbeddedMockServer

        @JvmStatic
        @BeforeClass
        fun start() {
            server = startEmbeddedMockServer()
        }

        @JvmStatic
        @AfterClass
        fun stop() {
            server.close()
        }
    }
}
