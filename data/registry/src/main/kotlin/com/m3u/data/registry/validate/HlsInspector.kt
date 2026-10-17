package com.m3u.data.registry.validate

import java.net.URI

/**
 * Minimal HLS playlist reading for link validation: enough to tell a master from a media playlist,
 * pick a variant and find a segment. Not a general parser.
 */
object HlsInspector {
    data class Variant(
        val uri: String,
        val bandwidth: Long?,
        val width: Int?,
        val height: Int?,
    )

    sealed interface Playlist {
        data class Master(val variants: List<Variant>) : Playlist
        data class Media(val segments: List<String>, val endList: Boolean) : Playlist
        data object NotHls : Playlist
    }

    private val attribute = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    fun parse(text: String): Playlist {
        val lines = text.removePrefix("﻿").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.firstOrNull()?.startsWith("#EXTM3U") != true) return Playlist.NotHls
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            val variants = mutableListOf<Variant>()
            lines.forEachIndexed { index, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
                val uri = lines.drop(index + 1).firstOrNull { !it.startsWith("#") } ?: return@forEachIndexed
                val attributes = attribute.findAll(line.substringAfter(':'))
                    .associate { it.groupValues[1] to it.groupValues[2].trim('"') }
                val resolution = attributes["RESOLUTION"]?.split('x', 'X')
                variants += Variant(
                    uri = uri,
                    bandwidth = attributes["BANDWIDTH"]?.toLongOrNull(),
                    width = resolution?.getOrNull(0)?.toIntOrNull(),
                    height = resolution?.getOrNull(1)?.toIntOrNull(),
                )
            }
            return Playlist.Master(variants)
        }
        return Playlist.Media(
            segments = lines.filterNot { it.startsWith("#") },
            endList = lines.any { it.startsWith("#EXT-X-ENDLIST") },
        )
    }

    /**
     * The tallest variant at or below [maxHeight]; without resolutions, the highest bandwidth up to
     * [maxBandwidth]. Falls back to the smallest variant when everything is too big.
     */
    fun chooseVariant(
        variants: List<Variant>,
        maxHeight: Int = 1080,
        maxBandwidth: Long = 8_000_000L,
    ): Variant? {
        if (variants.isEmpty()) return null
        val withHeight = variants.filter { it.height != null }
        if (withHeight.isNotEmpty()) {
            return withHeight
                .filter { it.height!! <= maxHeight }
                .maxWithOrNull(compareBy<Variant>({ it.height }, { it.bandwidth ?: 0L }))
                ?: withHeight.minWith(compareBy<Variant>({ it.height }, { it.bandwidth ?: 0L }))
        }
        return variants
            .filter { (it.bandwidth ?: 0L) <= maxBandwidth }
            .maxByOrNull { it.bandwidth ?: 0L }
            ?: variants.minBy { it.bandwidth ?: Long.MAX_VALUE }
    }

    fun resolve(base: String, reference: String): String = runCatching {
        URI(base).resolve(URI(reference.replace(" ", "%20"))).toString()
    }.getOrDefault(reference)
}
