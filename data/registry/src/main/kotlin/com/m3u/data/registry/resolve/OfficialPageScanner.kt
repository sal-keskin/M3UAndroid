package com.m3u.data.registry.resolve

import com.m3u.data.registry.model.ResolverSpec
import com.m3u.data.registry.plan.LinkUpdatePlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * A link found on a channel's own official live page.
 */
data class ResolvedLink(
    val url: String,
    val kind: Kind,
    // headers the page's own player sends: Referer and Origin of the official page
    val headers: Map<String, String> = emptyMap(),
) {
    enum class Kind { HLS, YOUTUBE }
}

/**
 * Reads an official live page like a browser's first request and picks out the channel's stream:
 * an `.m3u8` url on the broadcaster's own hosts that matches the channel's pattern, or the page's
 * embedded YouTube live (never its media urls: YouTube is opened in the YouTube app).
 *
 * Pure text scanning plus one GET; no page script runs. A page that builds its link in script
 * needs the WebView fallback in the app.
 */
object OfficialPageScanner {
    const val SOURCE_OFFICIAL = "official-page"
    const val SOURCE_YOUTUBE = "youtube"

    private val m3u8 = Regex("""https?:(?:\\?/){2}[^\s"'<>()]+?\.m3u8(?:\?[^\s"'<>()]*)?""", RegexOption.IGNORE_CASE)
    private val youtubeEmbed = Regex("""(?:youtube(?:-nocookie)?\.com/embed/|youtube\.com/watch\?v=|youtu\.be/)([A-Za-z0-9_-]{11})""")

    /**
     * Every acceptable stream url in [text], best first (the first one the page declares).
     */
    fun scanHls(text: String, spec: ResolverSpec): List<String> {
        val pattern = spec.match?.let { runCatching { Regex(it) }.getOrNull() }
        return m3u8.findAll(text)
            .map { clean(it.value) }
            .filter { url -> pattern == null || pattern.containsMatchIn(url) }
            .filter { url -> spec.hosts.isEmpty() || LinkUpdatePlanner.hostMatches(url, spec.hosts) }
            .distinct()
            .toList()
    }

    fun scanYoutube(text: String): String? = youtubeEmbed.find(text)?.groupValues?.get(1)

    fun youtubeWatchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    fun isYoutubeUrl(url: String?): Boolean {
        val host = url?.let { runCatching { URI(it).host }.getOrNull() }?.lowercase() ?: return false
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    /**
     * The official page's own origin, sent as Referer and Origin like its player does.
     */
    fun pageHeaders(page: String): Map<String, String> {
        val uri = runCatching { URI(page) }.getOrNull() ?: return emptyMap()
        val origin = "${uri.scheme}://${uri.authority}"
        return mapOf("Referer" to "$origin/", "Origin" to origin)
    }

    fun scan(text: String, spec: ResolverSpec, page: String): ResolvedLink? = when (spec.type) {
        ResolverSpec.TYPE_YOUTUBE -> scanYoutube(text)?.let {
            ResolvedLink(youtubeWatchUrl(it), ResolvedLink.Kind.YOUTUBE)
        }

        else -> scanHls(text, spec).firstOrNull()?.let {
            ResolvedLink(it, ResolvedLink.Kind.HLS, pageHeaders(page))
        }
    }

    /**
     * One GET of the official page with a TV browser's user agent.
     */
    suspend fun fetchAndScan(
        client: OkHttpClient,
        page: String,
        spec: ResolverSpec,
        timeoutMillis: Long = 10_000L,
    ): ResolvedLink? {
        val body = runInterruptible(Dispatchers.IO) {
            val request = Request.Builder()
                .url(page)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            try {
                client.newBuilder()
                    .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response -> if (response.isSuccessful) response.body.string() else null }
            } catch (_: IOException) {
                null
            }
        } ?: return null
        return scan(body, spec, page)
    }

    // urls inside scripts are often JSON- or HTML-escaped
    private fun clean(raw: String): String = raw
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\\u0026", "&")
        .trimEnd('\\', ',', ';')

    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 9; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
