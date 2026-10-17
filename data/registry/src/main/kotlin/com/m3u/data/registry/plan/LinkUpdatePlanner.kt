package com.m3u.data.registry.plan

import com.m3u.data.registry.match.ChannelMatcher
import com.m3u.data.registry.match.MatchTier
import com.m3u.data.registry.model.RegistryChannelSpec
import com.m3u.data.registry.rank.StreamRanker
import com.m3u.data.registry.source.PlaybackHeaders
import com.m3u.data.registry.source.StreamCandidate
import com.m3u.data.registry.validate.StreamStatus
import com.m3u.data.registry.validate.ValidationResult
import java.net.URI
import java.util.Locale

/**
 * A stream url as stored for a channel, before or after an update.
 */
data class StreamRecord(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val sourceId: String,
    val tier: MatchTier,
    val matchScore: Double,
    val trust: Double,
    val status: StreamStatus = StreamStatus.UNTESTED,
    val latencyMs: Long? = null,
    val height: Int? = null,
    val failureReason: String? = null,
    val checkedAt: Long = 0L,
    val lastOkAt: Long = 0L,
    val failureCount: Int = 0,
    val firstSeenAt: Long = 0L,
)

/**
 * A candidate url matched to a registry channel.
 */
data class MatchedCandidate(
    val channelId: String,
    val candidate: StreamCandidate,
    val tier: MatchTier,
    val score: Double,
)

data class ValidationTarget(
    val url: String,
    val headers: Map<String, String>,
)

data class ChannelPlan(
    val channelId: String,
    // primary first, then fallbacks in playback order
    val streams: List<StreamRecord>,
    // nothing validated this time; the last known good streams were kept
    val stale: Boolean,
    val primaryChanged: Boolean,
    // validation ran and no url works
    val failing: Boolean,
    // fuzzy matches that were not stored before; worth a look in diagnostics
    val newFuzzyUrls: List<String>,
) {
    val primary: StreamRecord? get() = streams.firstOrNull()
}

data class LinkUpdateSummary(
    val updated: Int,
    val unchanged: Int,
    val failing: Int,
    val newFuzzy: Int,
) {
    companion object {
        fun of(plans: Collection<ChannelPlan>) = LinkUpdateSummary(
            updated = plans.count { it.primaryChanged },
            unchanged = plans.count { !it.primaryChanged && !it.failing },
            failing = plans.count { it.failing },
            newFuzzy = plans.sumOf { it.newFuzzyUrls.size },
        )
    }
}

/**
 * Pure orchestration of a link update: matching, choosing what to validate and building the new
 * stream list for each channel. Network and persistence live in the callers.
 */
class LinkUpdatePlanner(
    private val maxStreams: Int = DEFAULT_MAX_STREAMS,
    private val maxValidationsPerChannel: Int = DEFAULT_MAX_VALIDATIONS,
) {
    fun match(
        channels: List<RegistryChannelSpec>,
        candidates: List<StreamCandidate>,
        blockedHosts: List<String> = emptyList(),
        officialHosts: List<String> = emptyList(),
    ): Map<String, List<MatchedCandidate>> {
        val matcher = ChannelMatcher(channels)
        return candidates
            .asSequence()
            .filterNot { candidate -> hostMatches(candidate.url, blockedHosts) }
            .mapNotNull { candidate ->
                matcher.match(candidate.name, candidate.tvgId, candidate.alternativeName)?.let { result ->
                    MatchedCandidate(
                        channelId = result.channelId,
                        candidate = candidate.copy(trust = hostTrust(candidate.url, candidate.trust, officialHosts)),
                        tier = result.tier,
                        score = result.score,
                    )
                }
            }
            .groupBy { it.channelId }
    }

    /**
     * Pinned urls first, then previously stored streams, then the best new candidates, capped at
     * [maxValidationsPerChannel]. Previously stored streams are always rechecked so a fallback
     * that died is noticed.
     */
    fun validationTargets(
        spec: RegistryChannelSpec,
        matched: List<MatchedCandidate>,
        known: List<StreamRecord>,
    ): List<ValidationTarget> {
        val pool = pool(spec, matched, known)
        val pinned = pool.filter { it.tier == MatchTier.PINNED }
        val knownUrls = known.map { it.url }.toSet()
        val stored = pool.filter { it.url in knownUrls && it.tier != MatchTier.PINNED }
        val fresh = pool
            .filter { it.url !in knownUrls && it.tier != MatchTier.PINNED }
            .sortedWith(
                compareByDescending<StreamRecord> { StreamRanker.priority(it.trust, it.tier, it.height) }
                    .thenByDescending { StreamRanker.heightPreference(it.height) }
            )
        return (pinned + stored + fresh)
            .distinctBy { it.url }
            .take(maxValidationsPerChannel.coerceAtLeast(pinned.size + stored.size))
            .map { ValidationTarget(it.url, it.headers) }
    }

    /**
     * @param validations results by url, or null when this update did not validate anything
     * (bootstrap from the bundled snapshot, metered network).
     */
    fun plan(
        spec: RegistryChannelSpec,
        matched: List<MatchedCandidate>,
        known: List<StreamRecord>,
        validations: Map<String, ValidationResult>?,
        now: Long,
    ): ChannelPlan {
        val knownByUrl = known.associateBy { it.url }
        val lastKnownGoodUrl = known
            .filter { it.lastOkAt > 0L }
            .maxByOrNull { it.lastOkAt }
            ?.url
        val pool = pool(spec, matched, known).map { record ->
            val previous = knownByUrl[record.url]
            val validation = validations?.get(record.url)
            record.copy(
                firstSeenAt = previous?.firstSeenAt?.takeIf { it > 0L } ?: now,
                lastOkAt = previous?.lastOkAt ?: 0L,
                failureCount = previous?.failureCount ?: 0,
            ).withValidation(validation, previous)
        }
        val previousPrimary = known.firstOrNull()?.url

        fun rankable(record: StreamRecord) = StreamRanker.Rankable(
            url = record.url,
            trust = record.trust,
            tier = record.tier,
            pinned = record.tier == MatchTier.PINNED,
            lastKnownGood = record.url == lastKnownGoodUrl,
            height = record.height,
            latencyMs = record.latencyMs,
        )

        val working = pool.filter { it.status == StreamStatus.OK }
        val streams: List<StreamRecord>
        val stale: Boolean
        val failing: Boolean
        when {
            validations == null -> {
                streams = StreamRanker.order(pool.filter { it.status != StreamStatus.FAILED }, ::rankable)
                    .ifEmpty { StreamRanker.order(pool, ::rankable) }
                    .take(maxStreams)
                stale = false
                failing = false
            }

            working.isNotEmpty() -> {
                // failed urls only fill spare slots: a last resort for playback, a reason in diagnostics
                val untested = pool.filter { it.status == StreamStatus.UNTESTED }
                val failed = pool.filter { it.status == StreamStatus.FAILED }
                streams = (
                    StreamRanker.order(working, ::rankable) +
                        StreamRanker.order(untested, ::rankable) +
                        StreamRanker.order(failed, ::rankable)
                    ).take(maxStreams)
                stale = false
                failing = false
            }

            known.isNotEmpty() -> {
                // never drop the last known good url because every candidate failed this time
                val poolByUrl = pool.associateBy { it.url }
                val kept = known.map { previous ->
                    val current = poolByUrl[previous.url] ?: previous
                    if (previous.lastOkAt > 0L) {
                        current.copy(status = StreamStatus.STALE, lastOkAt = previous.lastOkAt)
                    } else {
                        current
                    }
                }
                streams = (kept.filter { it.url == lastKnownGoodUrl } + kept.filter { it.url != lastKnownGoodUrl })
                    .take(maxStreams)
                stale = streams.any { it.status == StreamStatus.STALE }
                failing = true
            }

            else -> {
                // nothing ever worked: keep the failed candidates so diagnostics can explain why
                streams = StreamRanker.order(pool, ::rankable).take(maxStreams)
                stale = false
                failing = true
            }
        }
        val newPrimary = streams.firstOrNull()?.url
        return ChannelPlan(
            channelId = spec.id,
            streams = streams,
            stale = stale,
            primaryChanged = newPrimary != null && newPrimary != previousPrimary,
            failing = failing,
            newFuzzyUrls = streams
                .filter { it.tier == MatchTier.FUZZY && it.url !in knownByUrl }
                .map { it.url },
        )
    }

    private val StreamRecord.priority: Double
        get() = StreamRanker.priority(trust, tier, height)

    /**
     * Pinned urls, matched candidates and previously stored streams, one record per url.
     * When several sources offer the same url the highest priority wins.
     */
    private fun pool(
        spec: RegistryChannelSpec,
        matched: List<MatchedCandidate>,
        known: List<StreamRecord>,
    ): List<StreamRecord> {
        val channelHeaders = PlaybackHeaders.normalize(spec.headers)
        val pinned = spec.pinnedUrls.mapNotNull { raw ->
            val split = PlaybackHeaders.splitKodiUrl(raw)
            split.url.takeIf(String::isNotBlank)?.let { url ->
                StreamRecord(
                    url = url,
                    headers = PlaybackHeaders.merge(channelHeaders, split.headers),
                    sourceId = SOURCE_PINNED,
                    tier = MatchTier.PINNED,
                    matchScore = 1.0,
                    trust = 1.0,
                )
            }
        }
        val candidates = matched.map { match ->
            StreamRecord(
                url = match.candidate.url,
                headers = PlaybackHeaders.merge(channelHeaders, match.candidate.headers),
                sourceId = match.candidate.sourceId,
                tier = match.tier,
                matchScore = match.score,
                trust = match.candidate.trust,
                height = match.candidate.heightHint,
            )
        }
        val byUrl = linkedMapOf<String, StreamRecord>()
        (pinned + candidates).forEach { record ->
            val existing = byUrl[record.url]
            if (existing == null || record.tier == MatchTier.PINNED || record.priority > existing.priority) {
                byUrl[record.url] = if (existing != null && existing.tier == MatchTier.PINNED) existing else record
            }
        }
        known.forEach { previous ->
            if (previous.url !in byUrl) byUrl[previous.url] = previous
        }
        return byUrl.values.toList()
    }

    private fun StreamRecord.withValidation(
        validation: ValidationResult?,
        previous: StreamRecord?,
    ): StreamRecord = when {
        validation == null -> copy(
            status = previous?.status ?: StreamStatus.UNTESTED,
            latencyMs = previous?.latencyMs ?: latencyMs,
            height = previous?.height ?: height,
            failureReason = previous?.failureReason,
            checkedAt = previous?.checkedAt ?: 0L,
        )

        validation.ok -> copy(
            status = StreamStatus.OK,
            latencyMs = validation.latencyMs,
            height = validation.height ?: height,
            failureReason = null,
            checkedAt = validation.checkedAt,
            lastOkAt = validation.checkedAt,
        )

        else -> copy(
            status = StreamStatus.FAILED,
            latencyMs = validation.latencyMs,
            failureReason = validation.failureReason,
            checkedAt = validation.checkedAt,
            failureCount = failureCount + 1,
        )
    }

    companion object {
        const val DEFAULT_MAX_STREAMS = 4
        const val DEFAULT_MAX_VALIDATIONS = 8
        const val SOURCE_PINNED = "pinned"
        const val UNOFFICIAL_HOST_FACTOR = 0.75

        /**
         * Source trust, lowered for urls outside the broadcasters' own CDNs when [officialHosts]
         * is configured: a restream of the same channel never outranks the official stream.
         */
        fun hostTrust(url: String, trust: Double, officialHosts: List<String>): Double =
            if (officialHosts.isEmpty() || hostMatches(url, officialHosts)) trust else trust * UNOFFICIAL_HOST_FACTOR

        fun hostMatches(url: String, hosts: List<String>): Boolean {
            val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
            return hosts.any { entry ->
                val suffix = entry.trim().lowercase(Locale.ROOT)
                suffix.isNotEmpty() && (host == suffix || host.endsWith(".$suffix"))
            }
        }
    }
}
