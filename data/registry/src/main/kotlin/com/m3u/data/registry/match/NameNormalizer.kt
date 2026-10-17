package com.m3u.data.registry.match

import java.text.Normalizer
import java.util.Locale

/**
 * Turns channel display names into comparable token lists.
 *
 * Rules (mirrored by registry-template/scripts/registry_rules.py, keep both in sync):
 * 1. Turkish-aware folding: İ/I/ı → i, ş → s, ğ → g, ü → u, ö → o, ç → c, â/î/û → a/i/u,
 *    then lowercase and drop remaining combining marks.
 * 2. Bracketed notes are removed: "(720p)", "[Not 24/7]", "[Geo-blocked]".
 * 3. Every character outside a-z and 0-9 becomes a separator ("TV8,5" → "tv8 5", "Ⓖ" → "").
 * 4. Quality and noise tokens are dropped: hd, fhd, uhd, sd, hq, 4k, hevc, h264, h265 and
 *    resolution tokens such as 1080p.
 * 5. Letter/digit boundaries split tokens: "a2tv" → "a 2 tv", "tv8" → "tv 8".
 * 6. A trailing "tv" token is dropped when other tokens remain: "habertürk tv" → "haberturk".
 */
object NameNormalizer {
    private val turkishFold = mapOf(
        'İ' to 'i', 'I' to 'i', 'ı' to 'i',
        'Ş' to 's', 'ş' to 's',
        'Ğ' to 'g', 'ğ' to 'g',
        'Ü' to 'u', 'ü' to 'u',
        'Ö' to 'o', 'ö' to 'o',
        'Ç' to 'c', 'ç' to 'c',
        'Â' to 'a', 'â' to 'a',
        'Î' to 'i', 'î' to 'i',
        'Û' to 'u', 'û' to 'u',
    )
    private val combiningMarks = Regex("\\p{Mn}+")
    private val brackets = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val separators = Regex("[^a-z0-9]+")
    private val resolution = Regex("\\d{3,4}[pi]")
    private val letterDigit = Regex("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])")
    private val noise = setOf("hd", "fhd", "uhd", "sd", "hq", "4k", "8k", "hevc", "h264", "h265")
    private val heightHint = Regex("(\\d{3,4})[pi]\\b", RegexOption.IGNORE_CASE)

    fun normalize(raw: String): String = tokens(raw).joinToString(" ")

    fun tokens(raw: String): List<String> {
        val folded = fold(raw)
        val withoutNotes = brackets.replace(folded, " ")
        val coarse = separators.split(withoutNotes)
            .filter { it.isNotEmpty() && it !in noise && !resolution.matches(it) }
        val tokens = coarse.flatMap { letterDigit.split(it) }.filter { it.isNotEmpty() }
        return if (tokens.size > 1 && tokens.last() == "tv") tokens.dropLast(1) else tokens
    }

    fun fold(raw: String): String {
        val mapped = buildString(raw.length) {
            raw.forEach { char -> append(turkishFold[char] ?: char) }
        }
        val lower = mapped.lowercase(Locale.ROOT)
        return combiningMarks.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
    }

    /**
     * Canonical channel ids compare case-insensitively without the iptv-org feed suffix:
     * "ATV.tr@SD" → "atv.tr".
     */
    fun normalizeId(id: String?): String? = id
        ?.substringBefore('@')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }

    /**
     * "ATV (1080p)" → 1080, "4K" → 2160, otherwise null.
     */
    fun heightHint(raw: String): Int? {
        heightHint.find(raw)?.let { return it.groupValues[1].toIntOrNull() }
        return if (Regex("\\b4k\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)) 2160 else null
    }

    fun numericTokens(tokens: List<String>): List<String> = tokens.filter { token -> token.all(Char::isDigit) }
}
