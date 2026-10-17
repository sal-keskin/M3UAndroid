package com.m3u.data.repository.linkupdate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Small persisted state of the link updater: whether the first-run seed happened, when the last
 * full update ran, and its report. Stored as JSON next to the fetch cache, not in user settings.
 */
internal class LinkUpdateStateStore(private val file: File) {
    @Serializable
    data class State(
        val bootstrapped: Boolean = false,
        val lastFullRunAt: Long = 0L,
        val lastReport: LinkUpdateReport? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val state = MutableStateFlow(read())

    val current: StateFlow<State> = state.asStateFlow()

    suspend fun update(transform: (State) -> State) = mutex.withLock {
        val next = transform(state.value)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(State.serializer(), next))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
        state.value = next
    }

    private fun read(): State = runCatching {
        if (file.isFile) json.decodeFromString(State.serializer(), file.readText()) else State()
    }.getOrDefault(State())
}
