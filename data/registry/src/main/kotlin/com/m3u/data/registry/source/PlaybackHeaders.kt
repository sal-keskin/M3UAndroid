package com.m3u.data.registry.source

import java.io.ByteArrayOutputStream

/**
 * Playback request headers carried by playlist entries.
 *
 * Two playlist conventions are understood:
 * - VLC options: `#EXTVLCOPT:http-referrer=…`, `#EXTVLCOPT:http-user-agent=…`, `#EXTVLCOPT:http-origin=…`
 * - Kodi URL suffix: `https://host/live.m3u8|Referer=…&User-Agent=…&Origin=…`
 *   (https://kodi.wiki/view/HTTP). Values may be percent-encoded.
 *
 * Only [REFERER], [USER_AGENT] and [ORIGIN] are carried; everything else is ignored so a playlist
 * cannot inject arbitrary request headers.
 */
object PlaybackHeaders {
    const val REFERER = "Referer"
    const val USER_AGENT = "User-Agent"
    const val ORIGIN = "Origin"

    data class SplitUrl(
        val url: String,
        val headers: Map<String, String>,
    )

    /**
     * Maps a header or option name to its canonical header name, or null when it is not carried.
     */
    fun canonicalName(name: String): String? = when (name.trim().lowercase()) {
        "referer", "referrer", "http-referrer", "http-referer" -> REFERER
        "user-agent", "useragent", "http-user-agent" -> USER_AGENT
        "origin", "http-origin" -> ORIGIN
        else -> null
    }

    /**
     * Splits a Kodi-style `url|Header=value&Header2=value2` into the playable url and its headers.
     * Urls without a `|` suffix, or whose suffix has no `key=value` pair, are returned unchanged.
     */
    fun splitKodiUrl(raw: String): SplitUrl {
        val separator = raw.indexOf('|')
        if (separator < 0) return SplitUrl(raw.trim(), emptyMap())
        val suffix = raw.substring(separator + 1)
        if ('=' !in suffix) return SplitUrl(raw.trim(), emptyMap())
        val headers = linkedMapOf<String, String>()
        suffix.split('&').forEach { pair ->
            val equals = pair.indexOf('=')
            if (equals <= 0) return@forEach
            val name = canonicalName(percentDecode(pair.substring(0, equals))) ?: return@forEach
            val value = percentDecode(pair.substring(equals + 1)).trim()
            if (value.isNotEmpty()) headers[name] = value
        }
        return SplitUrl(raw.substring(0, separator).trim(), headers)
    }

    /**
     * Converts `#EXTVLCOPT` options (keys such as `http-user-agent`) to canonical headers.
     */
    fun fromOptions(options: Map<String, String>): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        options.forEach { (key, value) ->
            val name = canonicalName(key) ?: return@forEach
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) headers[name] = trimmed
        }
        return headers
    }

    /**
     * Normalizes an arbitrary header map: canonical names, blank values dropped.
     */
    fun normalize(headers: Map<String, String>?): Map<String, String> {
        if (headers.isNullOrEmpty()) return emptyMap()
        return fromOptions(headers)
    }

    /**
     * Merges header maps; later maps win.
     */
    fun merge(vararg maps: Map<String, String>): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        maps.forEach { merged.putAll(it) }
        return merged
    }

    /**
     * Rebuilds the Kodi url form, used when writing playlists for players that only understand it.
     */
    fun toKodiUrl(url: String, headers: Map<String, String>): String {
        if (headers.isEmpty()) return url
        return url + "|" + headers.entries.joinToString("&") { (name, value) ->
            "$name=${percentEncode(value)}"
        }
    }

    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 <= value.lastIndex) {
                val high = Character.digit(value[index + 1], 16)
                val low = Character.digit(value[index + 2], 16)
                if (high >= 0 && low >= 0) {
                    bytes.write(high * 16 + low)
                    index += 3
                    continue
                }
            }
            val encoded = char.toString().toByteArray(Charsets.UTF_8)
            bytes.write(encoded, 0, encoded.size)
            index++
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun percentEncode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (char.isLetterOrDigit() && code < 0x80 || char in "-._~/:;() ,") {
                append(char)
            } else {
                append('%')
                append("0123456789ABCDEF"[code shr 4])
                append("0123456789ABCDEF"[code and 0x0F])
            }
        }
    }
}
