package com.m3u.data.registry.plan

import com.m3u.data.registry.match.MatchTier
import com.m3u.data.registry.match.NameNormalizer
import com.m3u.data.registry.model.RegistryChannelSpec
import com.m3u.data.registry.source.StreamCandidate
import com.m3u.data.registry.validate.StreamStatus
import com.m3u.data.registry.validate.ValidationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkUpdatePlannerTest {
    private val planner = LinkUpdatePlanner()
    private val now = 1_800_000_000_000L

    private val atv = RegistryChannelSpec(
        id = "ATV.tr",
        rank = 1,
        name = "ATV",
        aliases = listOf("ATV Türkiye"),
        exclude = listOf("ATV Avrupa"),
        headers = mapOf("Referer" to "https://www.atv.com.tr/"),
    )

    private fun candidate(url: String, source: String, trust: Double, name: String = "ATV", tvgId: String? = "ATV.tr") =
        StreamCandidate(sourceId = source, trust = trust, url = url, name = name, tvgId = tvgId)

    private fun ok(latency: Long, height: Int? = 1080) =
        ValidationResult(ok = true, checkedAt = now, latencyMs = latency, height = height)

    private fun failed(reason: String) = ValidationResult(ok = false, checkedAt = now, failureReason = reason)

    @Test
    fun `pinned url beats everything when it works`() {
        val spec = atv.copy(pinnedUrls = listOf("https://pinned.example/atv.m3u8"))
        val matched = planner.match(
            listOf(spec),
            listOf(
                candidate("https://self.example/atv.m3u8", "self", 1.0),
                candidate("https://iptvorg.example/atv.m3u8", "iptvorg-tr", 0.9),
            ),
        ).getValue("ATV.tr")

        val plan = planner.plan(
            spec = spec,
            matched = matched,
            known = emptyList(),
            validations = mapOf(
                "https://pinned.example/atv.m3u8" to ok(latency = 900),
                "https://self.example/atv.m3u8" to ok(latency = 100),
                "https://iptvorg.example/atv.m3u8" to ok(latency = 50),
            ),
            now = now,
        )

        assertEquals(
            listOf(
                "https://pinned.example/atv.m3u8",
                "https://self.example/atv.m3u8",
                "https://iptvorg.example/atv.m3u8",
            ),
            plan.streams.map { it.url },
        )
        assertEquals(MatchTier.PINNED, plan.primary?.tier)
        // channel headers apply to every url
        assertEquals(mapOf("Referer" to "https://www.atv.com.tr/"), plan.primary?.headers)
        assertTrue(plan.primaryChanged)
        assertFalse(plan.failing)
    }

    @Test
    fun `trust, tier and latency order the working urls`() {
        val matched = planner.match(
            listOf(atv),
            listOf(
                candidate("https://fuzzy.example/a.m3u8", "self", 1.0, name = "ATV Turkye", tvgId = null),
                candidate("https://slow.example/a.m3u8", "iptvorg-tr", 0.9),
                candidate("https://fast.example/a.m3u8", "iptvorg-tr", 0.9),
                candidate("https://freetv.example/a.m3u8", "free-tv", 0.7),
            ),
        ).getValue("ATV.tr")

        val plan = planner.plan(
            spec = atv,
            matched = matched,
            known = emptyList(),
            validations = mapOf(
                "https://fuzzy.example/a.m3u8" to ok(10),
                "https://slow.example/a.m3u8" to ok(800),
                "https://fast.example/a.m3u8" to ok(80),
                "https://freetv.example/a.m3u8" to ok(5),
            ),
            now = now,
        )
        assertEquals(
            listOf(
                "https://fast.example/a.m3u8",
                "https://slow.example/a.m3u8",
                "https://freetv.example/a.m3u8",
                "https://fuzzy.example/a.m3u8",
            ),
            plan.streams.map { it.url },
        )
        assertEquals(listOf("https://fuzzy.example/a.m3u8"), plan.newFuzzyUrls)
    }

    @Test
    fun `last known good is kept and marked stale when every candidate fails`() {
        val known = listOf(
            StreamRecord(
                url = "https://good.example/atv.m3u8",
                sourceId = "self",
                tier = MatchTier.EXACT_ID,
                matchScore = 1.0,
                trust = 1.0,
                status = StreamStatus.OK,
                lastOkAt = now - 86_400_000,
                firstSeenAt = now - 864_000_000,
            ),
            StreamRecord(
                url = "https://never.example/atv.m3u8",
                sourceId = "free-tv",
                tier = MatchTier.ALIAS,
                matchScore = 1.0,
                trust = 0.7,
                status = StreamStatus.FAILED,
            ),
        )
        val matched = planner.match(
            listOf(atv),
            listOf(candidate("https://new.example/atv.m3u8", "iptvorg-tr", 0.9)),
        ).getValue("ATV.tr")

        val plan = planner.plan(
            spec = atv,
            matched = matched,
            known = known,
            validations = mapOf(
                "https://good.example/atv.m3u8" to failed("HTTP 403"),
                "https://never.example/atv.m3u8" to failed("timeout"),
                "https://new.example/atv.m3u8" to failed("HTTP 404"),
            ),
            now = now,
        )

        assertEquals("https://good.example/atv.m3u8", plan.primary?.url)
        assertEquals(StreamStatus.STALE, plan.primary?.status)
        assertEquals(now - 86_400_000, plan.primary?.lastOkAt)
        assertEquals("HTTP 403", plan.primary?.failureReason)
        assertTrue(plan.stale)
        assertTrue(plan.failing)
        assertFalse(plan.primaryChanged)
    }

    @Test
    fun `last known good stays primary while it still works`() {
        val known = listOf(
            StreamRecord(
                url = "https://good.example/atv.m3u8",
                sourceId = "free-tv",
                tier = MatchTier.ALIAS,
                matchScore = 1.0,
                trust = 0.7,
                status = StreamStatus.OK,
                lastOkAt = now - 1000,
            ),
        )
        val matched = planner.match(
            listOf(atv),
            listOf(candidate("https://better.example/atv.m3u8", "self", 1.0)),
        ).getValue("ATV.tr")
        val plan = planner.plan(
            spec = atv,
            matched = matched,
            known = known,
            validations = mapOf(
                "https://good.example/atv.m3u8" to ok(500),
                "https://better.example/atv.m3u8" to ok(50),
            ),
            now = now,
        )
        assertEquals(listOf("https://good.example/atv.m3u8", "https://better.example/atv.m3u8"), plan.streams.map { it.url })
        assertFalse(plan.primaryChanged)
    }

    @Test
    fun `without validation the snapshot order is kept by priority`() {
        val matched = planner.match(
            listOf(atv),
            listOf(
                candidate("https://iptvorg.example/atv.m3u8", "iptvorg-tr", 0.9),
                candidate("https://self.example/atv.m3u8", "self", 1.0),
                candidate("https://avrupa.example/atv.m3u8", "self", 1.0, name = "ATV Avrupa", tvgId = null),
            ),
        ).getValue("ATV.tr")
        val plan = planner.plan(atv, matched, known = emptyList(), validations = null, now = now)
        assertEquals(listOf("https://self.example/atv.m3u8", "https://iptvorg.example/atv.m3u8"), plan.streams.map { it.url })
        assertTrue(plan.streams.all { it.status == StreamStatus.UNTESTED })
        assertFalse(plan.failing)
    }

    @Test
    fun `blocked hosts are ignored and validation targets are capped`() {
        val candidates = (1..12).map { candidate("https://cdn$it.example/atv.m3u8", "iptvorg-tr", 0.9) } +
            candidate("https://evil.blocked.example/atv.m3u8", "iptvorg-tr", 0.9)
        val matched = planner.match(listOf(atv), candidates, blockedHosts = listOf("blocked.example")).getValue("ATV.tr")
        assertTrue(matched.none { it.candidate.url.contains("blocked") })
        val targets = planner.validationTargets(atv.copy(pinnedUrls = listOf("https://pin.example/a.m3u8")), matched, emptyList())
        assertEquals(LinkUpdatePlanner.DEFAULT_MAX_VALIDATIONS, targets.size)
        assertEquals("https://pin.example/a.m3u8", targets.first().url)
    }

    @Test
    fun `summary counts updated unchanged failing and fuzzy`() {
        val plans = listOf(
            ChannelPlan("a", emptyList(), stale = false, primaryChanged = true, failing = false, newFuzzyUrls = listOf("x")),
            ChannelPlan("b", emptyList(), stale = false, primaryChanged = false, failing = false, newFuzzyUrls = emptyList()),
            ChannelPlan("c", emptyList(), stale = true, primaryChanged = false, failing = true, newFuzzyUrls = emptyList()),
        )
        assertEquals(LinkUpdateSummary(updated = 1, unchanged = 1, failing = 1, newFuzzy = 1), LinkUpdateSummary.of(plans))
    }

    @Test
    fun `equal sources prefer the tallest picture up to 1080p`() {
        val matched = planner.match(
            listOf(atv),
            listOf(
                candidate("http://cdn.example/atv_360p.m3u8", "iptvorg-tr", 0.9, name = "ATV (360p)"),
                candidate("https://cdn.example/atv_1080p.m3u8", "iptvorg-tr", 0.9, name = "ATV (1080p)"),
                candidate("https://cdn.example/atv_1440p.m3u8", "iptvorg-tr", 0.9, name = "ATV (1440p)"),
            ).map { it.copy(heightHint = NameNormalizer.heightHint(it.name)) },
        ).getValue("ATV.tr")
        val plan = planner.plan(atv, matched, known = emptyList(), validations = null, now = now)
        assertEquals(
            listOf(
                "https://cdn.example/atv_1080p.m3u8",
                "http://cdn.example/atv_360p.m3u8",
                "https://cdn.example/atv_1440p.m3u8",
            ),
            plan.streams.map { it.url },
        )
    }

    @Test
    fun `restreams rank below the broadcaster's own cdn`() {
        val matched = planner.match(
            listOf(atv),
            listOf(
                candidate("http://restream.example.top/atv/video.m3u8", "iptvorg-tr", 0.9, name = "ATV (1080p)"),
                candidate("https://atv.ercdn.net/atv/atv_720p.m3u8", "iptvorg-tr", 0.9, name = "ATV (720p)"),
            ).map { it.copy(heightHint = NameNormalizer.heightHint(it.name)) },
            officialHosts = listOf("ercdn.net"),
        ).getValue("ATV.tr")
        val plan = planner.plan(atv, matched, known = emptyList(), validations = null, now = now)
        assertEquals("https://atv.ercdn.net/atv/atv_720p.m3u8", plan.primary?.url)
        assertEquals(0.9 * LinkUpdatePlanner.UNOFFICIAL_HOST_FACTOR, plan.streams.last().trust)
    }

    @Test
    fun `failed urls only fill spare fallback slots`() {
        val matched = planner.match(
            listOf(atv),
            listOf(
                candidate("https://dead.example/atv.m3u8", "self", 1.0),
                candidate("https://good.example/atv.m3u8", "iptvorg-tr", 0.9),
            ),
        ).getValue("ATV.tr")
        val plan = planner.plan(
            spec = atv,
            matched = matched,
            known = emptyList(),
            validations = mapOf(
                "https://dead.example/atv.m3u8" to failed("HTTP 404"),
                "https://good.example/atv.m3u8" to ok(300),
            ),
            now = now,
        )
        assertEquals(listOf("https://good.example/atv.m3u8", "https://dead.example/atv.m3u8"), plan.streams.map { it.url })
        assertEquals(StreamStatus.FAILED, plan.streams.last().status)
        assertEquals("HTTP 404", plan.streams.last().failureReason)
        assertFalse(plan.failing)
    }
}
