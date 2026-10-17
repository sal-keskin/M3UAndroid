package com.m3u.data.registry.rank

import com.m3u.data.registry.match.MatchTier

/**
 * Ordering of usable stream urls for one channel:
 * pinned > last known good > source trust × match tier weight > taller picture up to 1080p >
 * lower latency.
 * Streams taller than [MAX_PREFERRED_HEIGHT] lose a little priority so 1080p wins over 1440p on
 * small TV boxes.
 */
object StreamRanker {
    const val MAX_PREFERRED_HEIGHT = 1080
    private const val TALL_STREAM_PENALTY = 0.85

    // an unknown height ranks like 720p: above SD variants, below a known 1080p
    private const val UNKNOWN_HEIGHT_PREFERENCE = 720

    data class Rankable(
        val url: String,
        val trust: Double,
        val tier: MatchTier,
        val pinned: Boolean,
        val lastKnownGood: Boolean,
        val height: Int?,
        val latencyMs: Long?,
    )

    fun priority(trust: Double, tier: MatchTier, height: Int?): Double {
        val heightFactor = if (height != null && height > MAX_PREFERRED_HEIGHT) TALL_STREAM_PENALTY else 1.0
        return trust * tier.weight * heightFactor
    }

    fun heightPreference(height: Int?): Int = when {
        height == null -> UNKNOWN_HEIGHT_PREFERENCE
        height <= MAX_PREFERRED_HEIGHT -> height
        else -> MAX_PREFERRED_HEIGHT - 1
    }

    val comparator: Comparator<Rankable> = compareByDescending<Rankable> { it.pinned }
        .thenByDescending { it.lastKnownGood }
        .thenByDescending { priority(it.trust, it.tier, it.height) }
        .thenByDescending { heightPreference(it.height) }
        .thenBy { it.latencyMs ?: Long.MAX_VALUE }
        .thenBy { it.url }

    fun <T> order(items: List<T>, rankable: (T) -> Rankable): List<T> =
        items.sortedWith(compareBy(comparator, rankable))
}
