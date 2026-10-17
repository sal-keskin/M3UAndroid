package com.m3u.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel
import com.m3u.data.tv.model.keyCode
import com.m3u.i18n.R.string
import com.m3u.tv.live.LiveEvent
import com.m3u.tv.live.LiveNotice
import com.m3u.tv.live.RegistryUrlPanel
import com.m3u.tv.live.TvDiagnosticsScreen
import com.m3u.tv.live.TvLivePlayerLayer
import com.m3u.tv.live.TvLiveScreen
import com.m3u.tv.live.TvLiveSettingsSection
import com.m3u.tv.live.TvLiveViewModel
import com.m3u.tv.live.TvToast
import kotlinx.coroutines.delay

@Composable
fun App(
    viewModel: TvHomeViewModel = hiltViewModel(),
    live: TvLiveViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val remoteControlCode by viewModel.remoteControlCode.collectAsStateWithLifecycle()
    val view = LocalView.current
    val localeTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val context = LocalContext.current
    val diagnosticsShareTitle = stringResource(string.feat_setting_extension_diagnostics_share_title)
    val currentDiagnosticsShareTitle by rememberUpdatedState(diagnosticsShareTitle)
    val liveState by live.state.collectAsStateWithLifecycle()
    val sourceState by live.sourceState.collectAsStateWithLifecycle()
    val playbackException by live.playbackException.collectAsStateWithLifecycle()
    var destination by remember {
        mutableStateOf(if (live.enabled) TvDestination.Live else TvDestination.Home)
    }
    var surface by remember { mutableStateOf(TvSurface.Browse) }
    // the live (registry) player layer stays up while zapping, even between channels
    var livePlayback by remember { mutableStateOf(false) }
    var editingRegistryUrl by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<LiveNotice?>(null) }
    var startupHandled by rememberSaveable { mutableStateOf(false) }
    val closePlayer = {
        viewModel.releasePlayer()
        live.onPlayerClosed()
        livePlayback = false
        surface = TvSurface.Browse
    }
    val playLive: (Channel) -> Unit = { channel ->
        live.play(channel)
        livePlayback = true
        surface = TvSurface.Player
    }

    LaunchedEffect(live) {
        live.notices.collect { notice = it }
    }
    LaunchedEffect(live, context) {
        live.events.collect { event ->
            when (event) {
                is LiveEvent.OpenExternal -> {
                    // Android TV sends a plain https link to a do-nothing browser stub: name the app
                    val opened = YOUTUBE_PACKAGES.any { pkg ->
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(event.url))
                                    .setPackage(pkg)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            true
                        } catch (_: ActivityNotFoundException) {
                            false
                        }
                    }
                    if (!opened) live.onExternalAppMissing()
                }
            }
        }
    }
    LaunchedEffect(notice) {
        val shown = notice ?: return@LaunchedEffect
        delay(if (shown is LiveNotice.UpdateFinished) TvMotion.ToastHoldMillis * 3 else TvMotion.ToastHoldMillis)
        notice = null
    }
    LaunchedEffect(live) {
        if (startupHandled) return@LaunchedEffect
        startupHandled = true
        live.startupChannel()?.let { channel ->
            if (live.isLiveChannel(channel)) playLive(channel) else {
                viewModel.play(channel)
                surface = TvSurface.Player
            }
        }
    }
    BackHandler(enabled = destination == TvDestination.Diagnostics && surface == TvSurface.Browse) {
        destination = TvDestination.Live
    }

    val backTarget = tvAppBackTarget(
        playerVisible = surface == TvSurface.Player,
        providerSubscriptionVisible = state.providerSubscriptionForm != null,
        extensionSettingsVisible = state.extensionSettings != null,
    )
    BackHandler(enabled = backTarget != TvAppBackTarget.ACTIVITY) {
        when (backTarget) {
            TvAppBackTarget.PLAYER -> closePlayer()
            TvAppBackTarget.PROVIDER_SUBSCRIPTION -> viewModel.closeProviderSubscription()
            TvAppBackTarget.EXTENSION_SETTINGS -> viewModel.closeExtensionSettings()
            TvAppBackTarget.ACTIVITY -> Unit
        }
    }

    LaunchedEffect(view) {
        viewModel.remoteDirections.collect { direction ->
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, direction.keyCode))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, direction.keyCode))
        }
    }
    LaunchedEffect(viewModel, localeTag) {
        viewModel.updateLocale(localeTag)
    }
    LaunchedEffect(viewModel, context) {
        viewModel.extensionDiagnostics.collect { payload ->
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, payload)
                    },
                    currentDiagnosticsShareTitle,
                )
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(Modifier.fillMaxSize()) {
            TvNavigationRail(
                selected = if (destination == TvDestination.Diagnostics) TvDestination.Live else destination,
                onSelect = { destination = it }
            )
            when (destination) {
                TvDestination.Live -> TvLiveScreen(
                    state = liveState,
                    playingChannelId = currentChannel?.id,
                    recentChannelId = state.recent?.id,
                    onPlay = playLive,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onUpdateLinks = live::updateLinks,
                    onOpenDiagnostics = { destination = TvDestination.Diagnostics },
                )

                TvDestination.Diagnostics -> TvDiagnosticsScreen(
                    state = liveState,
                    onRevalidate = live::updateLinks,
                    onRepair = { canonicalId ->
                        liveState.channels
                            .firstOrNull { it.channel.relationId == canonicalId }
                            ?.let { live.repair(it.channel) }
                    },
                )

                else -> Column(Modifier.fillMaxSize()) {
                    if (destination == TvDestination.Status && live.enabled) {
                        TvLiveSettingsSection(
                            state = liveState,
                            onUpdateLinks = live::updateLinks,
                            onOpenDiagnostics = { destination = TvDestination.Diagnostics },
                            onStartOnLastChannel = live::setStartOnLastChannel,
                            onAutoUpdate = live::setAutoUpdate,
                            onEditRegistryUrl = { editingRegistryUrl = true },
                            onOfficialLinks = live::setOfficialLinks,
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        TvBrowsePane(
                            destination = destination,
                            state = state,
                            onOpenLibrary = { destination = TvDestination.Library },
                            onPlaylist = {
                                viewModel.selectPlaylist(it)
                                destination = TvDestination.Library
                            },
                            onRefresh = viewModel::refreshSelectedPlaylist,
                            onPlay = {
                                viewModel.play(it)
                                surface = TvSurface.Player
                            },
                            onPlayRecent = {
                                viewModel.playRecent()
                                surface = TvSurface.Player
                            },
                            onExternalExtensionsEnabled = viewModel::setExternalExtensionsEnabled,
                            onEnableExtension = viewModel::enableExtensionPlugin,
                            onReauthorizeExtension = viewModel::reauthorizeExtensionPlugin,
                            onDisableExtension = viewModel::disableExtensionPlugin,
                            onRevokeExtension = viewModel::revokeExtensionPlugin,
                            onClearExtensionData = viewModel::clearExtensionData,
                            onExportExtensionDiagnostics = viewModel::exportExtensionDiagnostics,
                            onOpenExtensionSettings = { extensionId ->
                                viewModel.openExtensionSettings(extensionId, localeTag)
                            },
                            onCloseExtensionSettings = viewModel::closeExtensionSettings,
                            onUpdateExtensionSetting = { sectionId, fieldKey, editToken, value ->
                                viewModel.updateExtensionSetting(
                                    sectionId,
                                    fieldKey,
                                    editToken,
                                    value,
                                    localeTag,
                                )
                            },
                            onRefreshProviders = viewModel::refreshSubscriptionProviders,
                            onOpenProviderSubscription = viewModel::openProviderSubscription,
                            onReauthenticateProvider = viewModel::reauthenticateProviderAccount,
                            onCloseProviderSubscription = viewModel::closeProviderSubscription,
                            onUpdateProviderTitle = viewModel::updateProviderSubscriptionTitle,
                            onSelectProviderKind = viewModel::selectProviderKind,
                            onUpdateProviderSetting = viewModel::updateProviderSetting,
                            onSubmitProviderSubscription = viewModel::submitProviderSubscription,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = surface == TvSurface.Player,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            if (livePlayback) {
                TvLivePlayerLayer(
                    player = player,
                    channel = currentChannel,
                    lineup = liveState.channels,
                    numberInput = liveState.numberInput,
                    sourceState = sourceState,
                    failed = playbackException != null,
                    repairing = liveState.repairingChannelId != null &&
                        liveState.repairingChannelId == currentChannel?.relationId,
                    isPlaying = isPlaying,
                    playbackState = playbackState,
                    onZap = live::zap,
                    onDigit = live::typeDigit,
                    onBack = live::back,
                    onClose = closePlayer,
                    onPlay = playLive,
                    onTogglePause = { live.togglePause(isPlaying) },
                    onSeekBy = live::seekBy,
                    onGoLive = live::goLive,
                    onRepair = live::repairCurrentChannel,
                    onNextChannel = live::nextChannel,
                    externalChannel = liveState.externalChannel,
                    onOpenExternal = live::openExternal,
                )
            } else {
                TvPlayerScreen(
                    player = player,
                    channel = currentChannel,
                    isPlaying = isPlaying,
                    playbackState = playbackState,
                    onPlayPause = { viewModel.pauseOrContinue(!isPlaying) },
                    onBack = closePlayer,
                    onClose = closePlayer
                )
            }
        }

        if (editingRegistryUrl) {
            RegistryUrlPanel(
                currentUrl = liveState.baseUrl,
                onSave = { url ->
                    live.setBaseUrl(url)
                    editingRegistryUrl = false
                },
                onReset = {
                    live.setBaseUrl("")
                    editingRegistryUrl = false
                },
                onDismiss = { editingRegistryUrl = false },
            )
        }

        notice?.let { shown ->
            // over video: top end, clear of the info banner; while browsing: bottom end, clear of
            // the header actions that usually hold focus
            TvToast(
                text = shown.text(),
                accent = shown.accent(),
                modifier = Modifier
                    .align(if (surface == TvSurface.Player) Alignment.TopEnd else Alignment.BottomEnd)
                    .padding(vertical = TvSpacing.SafeVertical, horizontal = TvSpacing.SafeHorizontal),
            )
        }

        remoteControlCode?.takeIf { surface == TvSurface.Browse }?.let { code ->
            val displayCode = code.toString().padStart(6, '0')
            val spokenCode = displayCode.toCharArray().joinToString(separator = " ")
            val pairingCodeDescription =
                stringResource(string.ui_remote_control_pairing_code, spokenCode)
            // phone-remote pairing pin: small, under the rail, clear of every screen's content
            Text(
                text = displayCode,
                color = TvColors.TextSecondary,
                style = TvType.Number,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = TvSpacing.Lg, bottom = TvSpacing.Lg)
                    .background(TvColors.Surface.copy(alpha = 0.86f), TvShapes.Pill)
                    .padding(horizontal = TvSpacing.Md, vertical = TvSpacing.Xs + TvSpacing.Xxs)
                    .clearAndSetSemantics {
                        contentDescription = pairingCodeDescription
                        liveRegion = LiveRegionMode.Polite
                    }
            )
        }
    }
}

@Composable
private fun LiveNotice.text(): String = when (this) {
    is LiveNotice.SwitchingSource -> stringResource(string.tv_link_switching_source, index, count)
    LiveNotice.BackToLive -> stringResource(string.tv_link_back_to_live)
    is LiveNotice.UpdateFinished -> stringResource(
        string.tv_link_summary,
        report.updated,
        report.unchanged,
        report.failing,
        report.newFuzzy,
    )

    LiveNotice.UpdateFailed -> stringResource(string.tv_link_update_failed)
    LiveNotice.ExternalAppMissing -> stringResource(string.tv_youtube_missing)
    is LiveNotice.Repaired -> stringResource(string.tv_link_repaired, title)
    is LiveNotice.RepairFailed -> stringResource(string.tv_link_repair_failed, title)
    is LiveNotice.NoChannelNumber -> stringResource(string.tv_number_not_found, number)
}

// YouTube for Android TV first, then the phone app some boxes ship instead
private val YOUTUBE_PACKAGES = listOf("com.google.android.youtube.tv", "com.google.android.youtube")

private fun LiveNotice.accent() = when (this) {
    is LiveNotice.SwitchingSource, LiveNotice.BackToLive -> TvColors.Accent
    is LiveNotice.UpdateFinished, is LiveNotice.Repaired -> TvColors.Success
    LiveNotice.UpdateFailed, is LiveNotice.RepairFailed, is LiveNotice.NoChannelNumber,
    LiveNotice.ExternalAppMissing -> TvColors.Danger
}
