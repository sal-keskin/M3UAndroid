package com.m3u.testing.mockserver

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Fixtures for the TR TV link validator (data/registry StreamValidator) and the registry
 * fetcher. Every route is deterministic:
 *
 * - `/linkcheck/good/master.m3u8`: master playlist with 360p, 1080p and 1440p variants;
 *   variants are live media playlists with three 6 s segments.
 * - `/linkcheck/forbidden.m3u8`: 403.
 * - `/linkcheck/empty.m3u8`: valid header, no segments.
 * - `/linkcheck/slow.m3u8`: answers after [SLOW_DELAY_MILLIS].
 * - `/linkcheck/needs-referer.m3u8`: 403 unless `Referer: https://referer.example/`.
 * - `/linkcheck/segment-404.m3u8`: playlist whose segments return 404.
 * - `/linkcheck/not-hls.m3u8`: an HTML page.
 * - `/linkcheck/etag.json`: ETag `"v1"`; answers 304 to `If-None-Match: "v1"`.
 * - `/linkcheck/dvr/{count}.m3u8`: a long DVR playlist (TRT keeps 24 h, 14 400 segments); segment
 *   names with a cut-off sequence number answer 400 like the real CDN.
 * - `/official/static`: an official live page declaring its tokenized stream in a script variable,
 *   next to a sister channel's stream (the resolver must pick the right one).
 * - `/official/script`: a page whose player builds the stream url at runtime and fetches it; only a
 *   WebView sees it.
 * - `/official/youtube`: a page embedding the broadcaster's YouTube live.
 */
internal fun Route.linkCheckRoutes() {
    val mpegUrl = ContentType.parse("application/vnd.apple.mpegurl")

    get("/linkcheck/good/master.m3u8") {
        call.respondText(GOOD_MASTER, mpegUrl)
    }
    get("/linkcheck/good/{variant}.m3u8") {
        val variant = call.parameters["variant"].orEmpty()
        call.respondText(livePlaylist(prefix = "$variant-"), mpegUrl)
    }
    get("/linkcheck/good/{segment}.ts") {
        val segment = call.parameters["segment"].orEmpty()
        call.respondBytes(linkCheckSegment(segment), ContentType.parse("video/mp2t"))
    }
    get("/linkcheck/forbidden.m3u8") {
        call.respond(HttpStatusCode.Forbidden, "geo-blocked")
    }
    get("/linkcheck/empty.m3u8") {
        call.respondText("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n", mpegUrl)
    }
    get("/linkcheck/slow.m3u8") {
        delay(SLOW_DELAY_MILLIS)
        call.respondText(livePlaylist(prefix = "good/1080-"), mpegUrl)
    }
    get("/linkcheck/needs-referer.m3u8") {
        if (call.request.headers[HttpHeaders.Referrer] != "https://referer.example/") {
            call.respond(HttpStatusCode.Forbidden, "referer required")
        } else {
            call.respondText(livePlaylist(prefix = "good/1080-"), mpegUrl)
        }
    }
    get("/linkcheck/segment-404.m3u8") {
        call.respondText(livePlaylist(prefix = "missing/"), mpegUrl)
    }
    get("/linkcheck/missing/{segment}.ts") {
        call.respond(HttpStatusCode.NotFound, "gone")
    }
    get("/linkcheck/not-hls.m3u8") {
        call.respondText("<html><body>Canlı yayın</body></html>", ContentType.Text.Html)
    }
    get("/linkcheck/dvr/{count}.m3u8") {
        val count = call.parameters["count"]?.toIntOrNull()?.coerceIn(1, 50_000) ?: 1
        call.respondText(dvrPlaylist(count), mpegUrl)
    }
    get("/linkcheck/dvr/dvr-segment-{number}.ts") {
        val number = call.parameters["number"].orEmpty()
        if (number.length == DVR_SEQUENCE_DIGITS && number.all(Char::isDigit)) {
            call.respondBytes(linkCheckSegment("dvr-$number"), ContentType.parse("video/mp2t"))
        } else {
            call.respond(HttpStatusCode.BadRequest, "bad segment name")
        }
    }
    get("/official/static") {
        val base = call.baseUrl()
        // the same link as players often embed it in JSON: escaped slashes and ampersands
        val escaped = "$base/linkcheck/good/master.m3u8?st=Rb6q&e=1".replace("/", "\\/").replace("&", "\\u0026")
        call.respondText(
            """
            <html><head><title>Canlı yayın</title></head><body>
            <div id="player"></div>
            <script>
              var videoUrl = "$base/linkcheck/good/master.m3u8?st=Rb6q&e=1792240827&tv=1";
              var sisterVideoUrl = "$base/official/sister/playlist.m3u8?st=x&tv=1";
              var config = {"hls":"$escaped"};
            </script>
            </body></html>
            """.trimIndent(),
            ContentType.Text.Html,
        )
    }
    get("/official/script") {
        call.respondText(
            """
            <html><head><title>Canlı yayın</title></head><body>
            <video id="v" muted></video>
            <script>
              var parts = ["linkcheck", "good", "master" + "." + "m3u8"];
              var url = location.origin + "/" + parts.join("/") + "?st=" + Date.now();
              fetch(url).then(function (r) { return r.text(); }).then(function () {
                document.getElementById("v").setAttribute("data-ready", "1");
              });
            </script>
            </body></html>
            """.trimIndent(),
            ContentType.Text.Html,
        )
    }
    get("/official/youtube") {
        call.respondText(
            """
            <html><body>
            <iframe title="YouTube video player" src="https://www.youtube.com/embed/ztmY_cCtUl0?si=abc" allowfullscreen></iframe>
            </body></html>
            """.trimIndent(),
            ContentType.Text.Html,
        )
    }
    get("/linkcheck/etag.json") {
        if (call.request.headers[HttpHeaders.IfNoneMatch] == ETAG) {
            call.respond(HttpStatusCode.NotModified)
        } else {
            call.response.header(HttpHeaders.ETag, ETAG)
            call.respondText("""{"schemaVersion":1,"channels":[]}""", ContentType.Application.Json)
        }
    }
}

/**
 * Starts the mock server inside the current JVM for unit tests. Port 0 picks a free port; read it
 * from [EmbeddedMockServer.baseUrl].
 */
fun startEmbeddedMockServer(host: String = "127.0.0.1", port: Int = 0): EmbeddedMockServer {
    val server = embeddedServer(
        factory = Netty,
        host = host,
        port = port,
        module = Application::mockServerModule,
    ).start(wait = false)
    val resolvedPort = runBlocking { server.engine.resolvedConnectors().first().port }
    return EmbeddedMockServer(server, "http://$host:$resolvedPort")
}

class EmbeddedMockServer internal constructor(
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    val baseUrl: String,
) : AutoCloseable {
    override fun close() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }
}

const val SLOW_DELAY_MILLIS = 8_000L
private const val ETAG = "\"v1\""

private val GOOD_MASTER = """
    #EXTM3U
    #EXT-X-VERSION:3
    #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
    360.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
    1080.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=2560x1440
    1440.m3u8
""".trimIndent()

private fun livePlaylist(prefix: String): String = """
    #EXTM3U
    #EXT-X-VERSION:3
    #EXT-X-TARGETDURATION:6
    #EXT-X-MEDIA-SEQUENCE:100
    #EXTINF:6.000,
    ${prefix}100.ts
    #EXTINF:6.000,
    ${prefix}101.ts
    #EXTINF:6.000,
    ${prefix}102.ts
""".trimIndent()

private const val DVR_SEQUENCE_DIGITS = 8

private fun dvrPlaylist(count: Int): String = buildString {
    appendLine("#EXTM3U")
    appendLine("#EXT-X-VERSION:3")
    appendLine("#EXT-X-TARGETDURATION:6")
    appendLine("#EXT-X-MEDIA-SEQUENCE:1")
    for (index in 1..count) {
        appendLine("#EXTINF:6.00000,")
        appendLine("dvr-segment-${index.toString().padStart(DVR_SEQUENCE_DIGITS, '0')}.ts")
    }
}

private fun linkCheckSegment(name: String): ByteArray {
    val payload = "TR TV link check segment $name\n".encodeToByteArray()
    return ByteArray(188 * 16) { index ->
        when {
            index % 188 == 0 -> 0x47
            index - 4 in payload.indices -> payload[index - 4]
            else -> 0xFF.toByte()
        }
    }
}
