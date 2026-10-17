package com.m3u.data.repository.linkupdate

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.m3u.data.api.ProviderOkhttpClient
import com.m3u.data.registry.model.RegistryChannelSpec
import com.m3u.data.registry.model.ResolverSpec
import com.m3u.data.registry.resolve.OfficialPageScanner
import com.m3u.data.registry.resolve.ResolvedLink
import com.m3u.data.registry.source.PlaybackHeaders
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds a channel's free official stream on its own live page, on this device only.
 *
 * 1. Read the page once, like a browser's first request, and scan it ([OfficialPageScanner]).
 *    No page script runs; this is enough when the page declares its player's source.
 * 2. Only for `webview` resolvers that found nothing: load the page in a hidden WebView and capture
 *    the player's own playlist request (or find it in the page and its resource list), then
 *    destroy the WebView.
 *
 * YouTube embeds are only ever turned into a watch link for the YouTube app; their media urls are
 * never captured. Resolved links stay in this device's database and are never published.
 */
@Singleton
internal class OfficialPageResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    @param:ProviderOkhttpClient private val client: OkHttpClient,
) {
    private val timber = Timber.tag("OfficialPageResolver")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(spec: RegistryChannelSpec): ResolvedLink? {
        val resolver = spec.resolver ?: return null
        val page = resolver.page ?: spec.officialLivePage ?: return null
        OfficialPageScanner.fetchAndScan(client, page, resolver)?.let { return it }
        if (resolver.type != ResolverSpec.TYPE_WEBVIEW) return null
        return withTimeoutOrNull(WEBVIEW_TIMEOUT_MILLIS) { captureWithWebView(page, resolver) }
            .also { if (it == null) timber.w("no stream found on $page") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureWithWebView(page: String, spec: ResolverSpec): ResolvedLink? =
        withContext(Dispatchers.Main) {
            // some TV builds ship without a WebView provider
            val webView = runCatching { WebView(context) }.getOrNull() ?: return@withContext null
            val found = CompletableDeferred<ResolvedLink>()
            try {
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    // the page is never shown: skip pictures
                    blockNetworkImage = true
                }
                webView.webViewClient = object : WebViewClient() {
                    // runs on a WebView thread for every request the page makes
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        OfficialPageScanner.scanHls(request.url.toString(), spec).firstOrNull()?.let { url ->
                            found.complete(
                                ResolvedLink(
                                    url = url,
                                    kind = ResolvedLink.Kind.HLS,
                                    headers = PlaybackHeaders.merge(
                                        OfficialPageScanner.pageHeaders(page),
                                        PlaybackHeaders.normalize(request.requestHeaders)
                                            .filterKeys { it != PlaybackHeaders.USER_AGENT },
                                    ),
                                )
                            )
                        }
                        return null
                    }
                }
                webView.loadUrl(page)
                // native video playback fetches outside the request hook: also look at the page
                val inspector = launch {
                    while (!found.isCompleted) {
                        delay(INSPECT_INTERVAL_MILLIS)
                        webView.evaluateJavascript(INSPECT_SCRIPT) { result ->
                            val text = runCatching { json.decodeFromString<String>(result) }.getOrNull()
                                ?: return@evaluateJavascript
                            OfficialPageScanner.scanHls(text, spec).firstOrNull()?.let { url ->
                                found.complete(
                                    ResolvedLink(url, ResolvedLink.Kind.HLS, OfficialPageScanner.pageHeaders(page))
                                )
                            }
                        }
                    }
                }
                found.await().also { inspector.cancel() }
            } finally {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }

    private companion object {
        const val WEBVIEW_TIMEOUT_MILLIS = 20_000L
        const val INSPECT_INTERVAL_MILLIS = 1_000L

        // resource urls the page loaded, video sources, and the live document
        const val INSPECT_SCRIPT = """
            (function () {
              var out = [];
              try { performance.getEntriesByType('resource').forEach(function (e) { out.push(e.name); }); } catch (e) {}
              var media = document.querySelectorAll('video, source');
              for (var i = 0; i < media.length; i++) { out.push(media[i].currentSrc || ''); out.push(media[i].src || ''); }
              out.push(document.documentElement.outerHTML.slice(0, 1000000));
              return out.join('\n');
            })()
        """
    }
}
