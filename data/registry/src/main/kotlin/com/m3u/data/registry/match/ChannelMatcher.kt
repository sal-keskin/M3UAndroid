package com.m3u.data.registry.match

import com.m3u.data.registry.model.RegistryChannelSpec

enum class MatchTier(val code: Int, val weight: Double) {
    // a manual override from channels.json
    PINNED(0, 1.0),
    // the normalized tvg-id equals the canonical id
    EXACT_ID(1, 1.0),
    // the normalized name equals the channel name or an alias
    ALIAS(2, 0.9),
    // guarded Jaro-Winkler similarity; lower priority, flagged in diagnostics
    FUZZY(3, 0.6);

    companion object {
        fun of(code: Int): MatchTier = entries.firstOrNull { it.code == code } ?: FUZZY
    }
}

data class MatchResult(
    val channelId: String,
    val tier: MatchTier,
    val score: Double,
)

/**
 * Maps a candidate (display name + optional tvg-id) to a registry channel.
 *
 * Order: tier 1 exact id, tier 2 alias, tier 3 fuzzy. The exclude list of a channel applies to
 * every tier. Fuzzy matching is deliberately strict:
 * - same number of tokens (no extra words: "Kanal 7 Avrupa" ≠ "Kanal 7"),
 * - identical numeric tokens ("TV8" ≠ "TV8,5", "TV 264" ≠ "TV2"),
 * - aligned tokens are equal, or both at least 4 letters long and similar,
 * - Jaro-Winkler over the whole name ≥ [fuzzyThreshold],
 * - never when the candidate carries a tvg-id that names another channel,
 * - never when two channels score within [AMBIGUITY_MARGIN] of each other.
 */
class ChannelMatcher(
    channels: List<RegistryChannelSpec>,
    private val fuzzyThreshold: Double = DEFAULT_FUZZY_THRESHOLD,
) {
    private class Entry(
        val spec: RegistryChannelSpec,
        val idKey: String?,
        val names: List<List<String>>,
        val excludes: List<List<String>>,
    )

    private val entries: List<Entry> = channels.map { spec ->
        Entry(
            spec = spec,
            idKey = NameNormalizer.normalizeId(spec.id),
            names = (listOf(spec.name) + spec.aliases)
                .map(NameNormalizer::tokens)
                .filter { it.isNotEmpty() }
                .distinct(),
            excludes = spec.exclude
                .map(NameNormalizer::tokens)
                .filter { it.isNotEmpty() },
        )
    }

    fun match(name: String, tvgId: String? = null, alternativeName: String? = null): MatchResult? {
        val idKey = NameNormalizer.normalizeId(tvgId)
        val names = listOfNotNull(name, alternativeName)
            .map(NameNormalizer::tokens)
            .filter { it.isNotEmpty() }
            .distinct()

        // tier 1: exact id
        if (idKey != null) {
            entries.firstOrNull { it.idKey == idKey }?.let { entry ->
                return if (names.none { entry.isExcluded(it) }) {
                    MatchResult(entry.spec.id, MatchTier.EXACT_ID, 1.0)
                } else {
                    null
                }
            }
        }
        if (names.isEmpty()) return null

        // tier 2: alias
        val aliasHits = entries.filter { entry ->
            names.none { entry.isExcluded(it) } && names.any { it in entry.names }
        }
        when (aliasHits.size) {
            0 -> Unit
            1 -> return MatchResult(aliasHits.single().spec.id, MatchTier.ALIAS, 1.0)
            else -> return null
        }

        // tier 3: fuzzy, only for candidates without a foreign tvg-id
        if (idKey != null) return null
        val scored = entries
            .filter { entry -> names.none { entry.isExcluded(it) } }
            .mapNotNull { entry ->
                val best = names.maxOfOrNull { candidate ->
                    entry.names.maxOfOrNull { target -> fuzzyScore(candidate, target) } ?: 0.0
                } ?: 0.0
                if (best >= fuzzyThreshold) entry to best else null
            }
            .sortedByDescending { it.second }
        val first = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)
        if (second != null && first.second - second.second < AMBIGUITY_MARGIN) return null
        return MatchResult(first.first.spec.id, MatchTier.FUZZY, first.second)
    }

    private fun Entry.isExcluded(candidate: List<String>): Boolean =
        excludes.any { exclude -> candidate == exclude || candidate.containsRun(exclude) }

    companion object {
        const val DEFAULT_FUZZY_THRESHOLD = 0.92
        const val AMBIGUITY_MARGIN = 0.01
        private const val TOKEN_THRESHOLD = 0.88
        private const val MIN_FUZZY_TOKEN_LENGTH = 4

        /**
         * 0.0 when a guard fails, otherwise the Jaro-Winkler similarity of the joined names.
         */
        fun fuzzyScore(candidate: List<String>, target: List<String>): Double {
            if (candidate.size != target.size) return 0.0
            if (NameNormalizer.numericTokens(candidate) != NameNormalizer.numericTokens(target)) return 0.0
            val tokensAgree = candidate.zip(target).all { (a, b) ->
                a == b || (
                    a.length >= MIN_FUZZY_TOKEN_LENGTH &&
                        b.length >= MIN_FUZZY_TOKEN_LENGTH &&
                        a.any(Char::isLetter) &&
                        JaroWinkler.similarity(a, b) >= TOKEN_THRESHOLD
                    )
            }
            if (!tokensAgree) return 0.0
            return JaroWinkler.similarity(candidate.joinToString(" "), target.joinToString(" "))
        }

        /**
         * True when [run] appears as consecutive tokens that are not glued to a neighbouring
         * number: "atv avrupa hd" contains "atv avrupa", but "tv 8 5" does not contain "tv 8".
         */
        private fun List<String>.containsRun(run: List<String>): Boolean {
            if (run.size > size) return false
            for (start in 0..size - run.size) {
                if (subList(start, start + run.size) != run) continue
                val before = getOrNull(start - 1)
                val after = getOrNull(start + run.size)
                val gluedToNumber = listOfNotNull(before, after).any { token -> token.all(Char::isDigit) }
                if (!gluedToNumber) return true
            }
            return false
        }
    }
}
