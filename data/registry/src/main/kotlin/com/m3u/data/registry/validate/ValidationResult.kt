package com.m3u.data.registry.validate

enum class StreamStatus(val value: String) {
    // validated on the device during the last update
    OK("ok"),

    // validation or playback failed
    FAILED("failed"),

    // worked before, failed during the last update, kept because nothing better was found
    STALE("stale"),

    // never validated, e.g. seeded from the bundled snapshot
    UNTESTED("untested");

    companion object {
        fun of(value: String?): StreamStatus = entries.firstOrNull { it.value == value } ?: UNTESTED
    }
}

data class ValidationResult(
    val ok: Boolean,
    val checkedAt: Long,
    // time to the first playlist response
    val latencyMs: Long? = null,
    // height of the variant that would be played, when known
    val height: Int? = null,
    val bandwidth: Long? = null,
    val failureReason: String? = null,
)
