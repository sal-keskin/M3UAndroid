package com.m3u.tv.live

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import com.m3u.core.foundation.architecture.preferences.PreferencesKeys
import com.m3u.core.foundation.architecture.preferences.Settings
import com.m3u.core.foundation.architecture.preferences.flowOf
import com.m3u.core.foundation.architecture.preferences.get
import com.m3u.core.foundation.architecture.preferences.set
import com.m3u.data.database.model.Channel
import com.m3u.data.registry.resolve.OfficialPageScanner
import com.m3u.data.registry.validate.StreamStatus
import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.repository.linkupdate.ChannelDiagnostics
import com.m3u.data.repository.linkupdate.LinkUpdateProgress
import com.m3u.data.repository.linkupdate.LinkUpdateReport
import com.m3u.data.repository.linkupdate.LinkUpdateRepository
import com.m3u.data.repository.linkupdate.isRegistryPlaylistUrl
import com.m3u.data.service.MediaCommand
import com.m3u.data.service.PlaybackSourceEvent
import com.m3u.data.service.PlaybackSourceState
import com.m3u.data.service.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class LiveStatus { OK, STALE, UNTESTED, FAILING, NO_LINK, EXTERNAL }

@Immutable
data class LiveChannel(
    val number: Int,
    val channel: Channel,
    val status: LiveStatus,
    val sourceId: String?,
    val height: Int?,
    val latencyMs: Long?,
)

@Immutable
data class TvLiveUiState(
    val loaded: Boolean = false,
    val channels: List<LiveChannel> = emptyList(),
    val diagnostics: List<ChannelDiagnostics> = emptyList(),
    val progress: LinkUpdateProgress = LinkUpdateProgress.Idle,
    val lastReport: LinkUpdateReport? = null,
    val baseUrl: String = "",
    val startOnLastChannel: Boolean = false,
    val autoUpdate: Boolean = true,
    val repairingChannelId: String? = null,
    val numberInput: ChannelZapper.NumberInput? = null,
    val officialLinks: Boolean = false,
    // a channel whose official live is on YouTube: offered to the YouTube app, not played here
    val externalChannel: Channel? = null,
) {
    val updating: Boolean get() = progress is LinkUpdateProgress.Running

    fun numberOf(channelId: Int?): Int? = channels.firstOrNull { it.channel.id == channelId }?.number
}

@Immutable
sealed interface LiveNotice {
    data class SwitchingSource(val index: Int, val count: Int) : LiveNotice
    data object BackToLive : LiveNotice
    data class UpdateFinished(val report: LinkUpdateReport) : LiveNotice
    data object UpdateFailed : LiveNotice
    data class Repaired(val title: String) : LiveNotice
    data class RepairFailed(val title: String) : LiveNotice
    data class NoChannelNumber(val number: Int) : LiveNotice
    data object ExternalAppMissing : LiveNotice
}

@Immutable
sealed interface LiveEvent {
    data class OpenExternal(val url: String) : LiveEvent
}

/**
 * Live TV state for the curated registry lineup: channel numbers, link health, updates and
 * repairs, remote-control zapping and number entry.
 */
@HiltViewModel
class TvLiveViewModel @Inject constructor(
    private val linkUpdateRepository: LinkUpdateRepository,
    private val playerManager: PlayerManager,
    private val channelRepository: ChannelRepository,
    private val settings: Settings,
) : ViewModel() {
    val enabled: Boolean = linkUpdateRepository.enabled

    private val zapper = ChannelZapper()
    private val _state = MutableStateFlow(TvLiveUiState())
    val state: StateFlow<TvLiveUiState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<LiveNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<LiveNotice> = _notices.asSharedFlow()

    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    val sourceState: StateFlow<PlaybackSourceState?> = playerManager.sourceState
    val playbackException: StateFlow<PlaybackException?> = playerManager.playbackException

    private var numberJob: Job? = null

    init {
        if (enabled) {
            observeChannels()
            observeUpdates()
            observeSettings()
            observeSourceEvents()
        }
    }

    fun isLiveChannel(channel: Channel?): Boolean = enabled && isRegistryPlaylistUrl(channel?.playlistUrl)

    fun isExternal(channel: Channel?): Boolean =
        isLiveChannel(channel) && OfficialPageScanner.isYoutubeUrl(channel?.url)

    fun play(channel: Channel, fromBack: Boolean = false) {
        if (isLiveChannel(channel)) zapper.onPlayed(channel.id, fromBack)
        if (isExternal(channel)) {
            // never auto-launch another app while zapping: show the hand-off card instead
            playerManager.release()
            _state.update { it.copy(externalChannel = channel) }
            viewModelScope.launch {
                // the broadcaster may have started a new live video since the last update
                if (linkUpdateRepository.refreshOfficialLink(channel)) {
                    channelRepository.get(channel.id)?.let { fresh ->
                        _state.update { state ->
                            if (state.externalChannel?.id == fresh.id) state.copy(externalChannel = fresh) else state
                        }
                    }
                }
            }
            return
        }
        _state.update { it.copy(externalChannel = null) }
        viewModelScope.launch {
            playerManager.play(MediaCommand.Common(channel.id))
        }
    }

    fun openExternal() {
        val channel = _state.value.externalChannel ?: return
        _events.tryEmit(LiveEvent.OpenExternal(channel.url))
    }

    fun onExternalAppMissing() {
        _notices.tryEmit(LiveNotice.ExternalAppMissing)
    }

    /**
     * ▲ / ▼ and CH+ / CH−: [offset] channel numbers away, wrapping around.
     */
    fun zap(offset: Int) {
        cancelNumberInput()
        val entry = zapper.step(offset) ?: return
        channelOf(entry.channelId)?.let { play(it) }
    }

    /**
     * Back while watching: the previous channel, or false when the player should close.
     */
    fun back(): Boolean {
        if (_state.value.numberInput != null) {
            cancelNumberInput()
            return true
        }
        val entry = zapper.back() ?: return false
        val channel = channelOf(entry.channelId) ?: return false
        play(channel, fromBack = true)
        return true
    }

    fun onPlayerClosed() {
        cancelNumberInput()
        zapper.clear()
        _state.update { it.copy(externalChannel = null) }
    }

    fun typeDigit(digit: Int) {
        numberJob?.cancel()
        val input = zapper.typeDigit(_state.value.numberInput?.digits.orEmpty(), digit)
        _state.update { it.copy(numberInput = input) }
        if (input.final) {
            commitNumber(input)
        } else {
            numberJob = viewModelScope.launch {
                delay(ChannelZapper.NUMBER_COMMIT_DELAY_MILLIS)
                commitNumber(input)
            }
        }
    }

    private fun commitNumber(input: ChannelZapper.NumberInput) {
        numberJob = null
        _state.update { it.copy(numberInput = null) }
        val channel = input.match?.let { channelOf(it.channelId) }
        if (channel == null) {
            _notices.tryEmit(LiveNotice.NoChannelNumber(input.number))
        } else if (channel.id != zapper.current) {
            play(channel)
        }
    }

    private fun cancelNumberInput() {
        numberJob?.cancel()
        numberJob = null
        _state.update { it.copy(numberInput = null) }
    }

    fun nextChannel() = zap(+1)

    fun togglePause(isPlaying: Boolean) = playerManager.pauseOrContinue(!isPlaying)

    fun seekBy(deltaMs: Long) = playerManager.seekBy(deltaMs)

    fun goLive() = playerManager.seekToLive()

    fun updateLinks() {
        // a second press while an update runs would restart it from scratch
        if (!enabled || _state.value.updating) return
        val id = linkUpdateRepository.updateNow()
        viewModelScope.launch {
            when (val result = awaitRun(id)) {
                is LinkUpdateProgress.Finished -> _notices.tryEmit(LiveNotice.UpdateFinished(result.report))
                else -> _notices.tryEmit(LiveNotice.UpdateFailed)
            }
        }
    }

    /**
     * Runs the link pipeline for the playing channel only, then plays it again.
     */
    fun repairCurrentChannel() {
        val channel = playerManager.channel.value ?: return
        repair(channel)
    }

    fun repair(channel: Channel) {
        if (!isLiveChannel(channel)) return
        val canonicalId = channel.relationId ?: return
        if (_state.value.repairingChannelId == canonicalId) return
        _state.update { it.copy(repairingChannelId = canonicalId) }
        val id = linkUpdateRepository.repair(canonicalId)
        viewModelScope.launch {
            val result = awaitRun(id)
            _state.update { it.copy(repairingChannelId = null) }
            val repaired = result is LinkUpdateProgress.Finished && result.report.failing == 0
            _notices.tryEmit(
                if (repaired) LiveNotice.Repaired(channel.title) else LiveNotice.RepairFailed(channel.title)
            )
            if (playerManager.channel.value?.id == channel.id || playerManager.channel.value == null) {
                playerManager.play(MediaCommand.Common(channel.id))
            }
        }
    }

    fun setStartOnLastChannel(enabled: Boolean) {
        viewModelScope.launch { settings[PreferencesKeys.START_ON_LAST_CHANNEL] = enabled }
    }

    fun setAutoUpdate(enabled: Boolean) {
        viewModelScope.launch {
            settings[PreferencesKeys.LINK_AUTO_UPDATE] = enabled
            linkUpdateRepository.scheduleAutomaticUpdates()
        }
    }

    /**
     * Opt-in: read channels' own official live pages on this TV. Runs an update so links appear
     * (or disappear) right away.
     */
    fun setOfficialLinks(enabled: Boolean) {
        viewModelScope.launch {
            settings[PreferencesKeys.OFFICIAL_PAGE_LINKS] = enabled
            updateLinks()
        }
    }

    fun setBaseUrl(url: String) {
        viewModelScope.launch { linkUpdateRepository.setBaseUrl(url) }
    }

    /**
     * The channel to open on launch when "Start on last channel" is on.
     */
    suspend fun startupChannel(): Channel? {
        if (!enabled || !settings[PreferencesKeys.START_ON_LAST_CHANNEL]) return null
        return channelRepository.getPlayedRecently()
    }

    private suspend fun awaitRun(id: UUID): LinkUpdateProgress = linkUpdateRepository
        .observeRun(id)
        .first { it is LinkUpdateProgress.Finished || it is LinkUpdateProgress.Failed }

    private fun channelOf(channelId: Int): Channel? =
        _state.value.channels.firstOrNull { it.channel.id == channelId }?.channel

    private fun observeChannels() {
        viewModelScope.launch {
            combine(
                linkUpdateRepository.observeRankedChannels(),
                linkUpdateRepository.observeDiagnostics(),
            ) { ranked, diagnostics ->
                val byId = diagnostics.associateBy { it.canonicalId }
                val channels = ranked.map { item ->
                    val diagnostic = byId[item.channel.relationId]
                    val primary = diagnostic?.primary
                    LiveChannel(
                        number = item.rank,
                        channel = item.channel,
                        status = liveStatus(item.channel, diagnostic),
                        sourceId = primary?.sourceId,
                        height = primary?.height,
                        latencyMs = primary?.latencyMs,
                    )
                }
                channels to diagnostics
            }.collect { (channels, diagnostics) ->
                zapper.setLineup(channels.map { ChannelZapper.Entry(it.number, it.channel.id) })
                _state.update { it.copy(loaded = true, channels = channels, diagnostics = diagnostics) }
            }
        }
    }

    private fun observeUpdates() {
        viewModelScope.launch {
            linkUpdateRepository.observeProgress().collect { progress ->
                _state.update { it.copy(progress = progress) }
            }
        }
        viewModelScope.launch {
            linkUpdateRepository.observeLastReport().collect { report ->
                _state.update { it.copy(lastReport = report) }
            }
        }
        viewModelScope.launch {
            linkUpdateRepository.observeBaseUrl().collect { url ->
                _state.update { it.copy(baseUrl = url) }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settings.flowOf(PreferencesKeys.START_ON_LAST_CHANNEL).collect { value ->
                _state.update { it.copy(startOnLastChannel = value) }
            }
        }
        viewModelScope.launch {
            settings.flowOf(PreferencesKeys.LINK_AUTO_UPDATE).collect { value ->
                _state.update { it.copy(autoUpdate = value) }
            }
        }
        viewModelScope.launch {
            settings.flowOf(PreferencesKeys.OFFICIAL_PAGE_LINKS).collect { value ->
                _state.update { it.copy(officialLinks = value) }
            }
        }
    }

    private fun observeSourceEvents() {
        viewModelScope.launch {
            playerManager.sourceEvents.collect { event ->
                when (event) {
                    is PlaybackSourceEvent.Switching -> _notices.tryEmit(
                        LiveNotice.SwitchingSource(event.index + 1, event.count)
                    )

                    PlaybackSourceEvent.BackToLive -> _notices.tryEmit(LiveNotice.BackToLive)
                    PlaybackSourceEvent.Exhausted -> Unit
                }
            }
        }
    }

    private fun liveStatus(channel: Channel, diagnostic: ChannelDiagnostics?): LiveStatus {
        if (OfficialPageScanner.isYoutubeUrl(channel.url)) return LiveStatus.EXTERNAL
        val primary = diagnostic?.primary
            ?: return if (channel.url.isBlank()) LiveStatus.NO_LINK else LiveStatus.UNTESTED
        return when (primary.status) {
            StreamStatus.OK -> LiveStatus.OK
            StreamStatus.STALE -> LiveStatus.STALE
            StreamStatus.UNTESTED -> LiveStatus.UNTESTED
            StreamStatus.FAILED -> if (diagnostic.working) LiveStatus.OK else LiveStatus.FAILING
        }
    }
}
