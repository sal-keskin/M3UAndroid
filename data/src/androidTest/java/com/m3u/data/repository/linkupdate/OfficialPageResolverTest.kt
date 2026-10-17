package com.m3u.data.repository.linkupdate

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m3u.data.registry.model.RegistryChannelSpec
import com.m3u.data.registry.model.ResolverSpec
import com.m3u.data.registry.resolve.ResolvedLink
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the resolver against `:testing:mock-server`'s official-page fixtures on a device.
 */
@RunWith(AndroidJUnit4::class)
class OfficialPageResolverTest {
    private val baseUrl = InstrumentationRegistry.getArguments()
        .getString("m3uMockServerUrl", "http://10.0.2.2:8080")
        .trimEnd('/')
    private val resolver = OfficialPageResolver(ApplicationProvider.getApplicationContext(), OkHttpClient())

    private fun channel(resolver: ResolverSpec) = RegistryChannelSpec(
        id = "Official.tr",
        rank = 1,
        name = "Official",
        resolver = resolver,
    )

    @Test
    fun linkDeclaredInThePageIsReadWithoutWebView() = runBlocking {
        val resolved = resolver.resolve(
            channel(
                ResolverSpec(
                    type = ResolverSpec.TYPE_WEBVIEW,
                    page = "$baseUrl/official/static",
                    match = "linkcheck/good/master\\.m3u8",
                )
            )
        )
        assertEquals("$baseUrl/linkcheck/good/master.m3u8?st=Rb6q&e=1792240827&tv=1", resolved?.url)
    }

    @Test
    fun linkBuiltByScriptIsCapturedByTheWebView() = runBlocking {
        val resolved = resolver.resolve(
            channel(
                ResolverSpec(
                    type = ResolverSpec.TYPE_WEBVIEW,
                    page = "$baseUrl/official/script",
                    match = "linkcheck/good/master\\.m3u8",
                )
            )
        )
        assertNotNull("the WebView found no link", resolved)
        assertEquals(ResolvedLink.Kind.HLS, resolved!!.kind)
        assertTrue(resolved.url, resolved.url.startsWith("$baseUrl/linkcheck/good/master.m3u8?st="))
    }

    @Test
    fun youtubeResolversNeverUseTheWebView() = runBlocking {
        val resolved = resolver.resolve(
            channel(ResolverSpec(type = ResolverSpec.TYPE_YOUTUBE, page = "$baseUrl/official/script"))
        )
        assertNull(resolved)
        val youtube = resolver.resolve(
            channel(ResolverSpec(type = ResolverSpec.TYPE_YOUTUBE, page = "$baseUrl/official/youtube"))
        )
        assertEquals("https://www.youtube.com/watch?v=ztmY_cCtUl0", youtube?.url)
    }
}
