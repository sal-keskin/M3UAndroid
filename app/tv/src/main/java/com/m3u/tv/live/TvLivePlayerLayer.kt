package com.m3u.tv.live

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel
import com.m3u.data.service.PlaybackSourceState
import com.m3u.i18n.R.string
import com.m3u.tv.TvActionButton
import com.m3u.tv.TvColors
import com.m3u.tv.TvMotion
import com.m3u.tv.TvShapes
import com.m3u.tv.TvSpacing
import com.m3u.tv.TvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import java.util.Date

/**
 * Full-screen playback of a live (registry) channel, remote-first:
 * ▲ ▼ / CH± change channel · digits jump to a number · OK opens the channel list ·
 * ◀ ▶ rewind / forward 10 s inside the timeshift window (OK then pauses) · Back returns to the
 * previous channel, and leaves when there is none. Overlays never take focus from the video
 * except the channel list and the error card.
 */
@Composable
fun TvLivePlayerLayer(
    player: Player?,
    channel: Channel?,
    lineup: List<LiveChannel>,
    numberInput: ChannelZapper.NumberInput?,
    sourceState: PlaybackSourceState?,
    failed: Boolean,
    repairing: Boolean,
    isPlaying: Boolean,
    playbackState: Int,
    onZap: (Int) -> Unit,
    onDigit: (Int) -> Unit,
    onBack: () -> Boolean,
    onClose: () -> Unit,
    onPlay: (Channel) -> Unit,
    onTogglePause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onGoLive: () -> Unit,
    onRepair: () -> Unit,
    onNextChannel: () -> Unit,
    externalChannel: Channel? = null,
    onOpenExternal: () -> Unit = {},
) {
    val rootFocus = remember { FocusRequester() }
    // the hand-off card takes decisions like the error card does
    val failed = failed || externalChannel != null
    val channel = externalChannel ?: channel
    var listOpen by remember { mutableStateOf(false) }
    var bannerUntil by remember { mutableLongStateOf(0L) }
    var transportUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val timeline = rememberLiveTimeline(player)
    val paused = !isPlaying && playbackState == Player.STATE_READY
    val current = lineup.firstOrNull { it.channel.id == channel?.id }
    val bannerVisible = now < bannerUntil || paused
    val transportVisible = now < transportUntil || paused

    fun showBanner() {
        bannerUntil = System.currentTimeMillis() + TvMotion.BannerHoldMillis
        now = System.currentTimeMillis()
    }

    fun showTransport() {
        showBanner()
        transportUntil = System.currentTimeMillis() + TvMotion.TimeshiftHoldMillis
    }

    LaunchedEffect(channel?.id) { showBanner() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(listOpen, failed) {
        if (!listOpen && !failed) {
            yield()
            runCatching { rootFocus.requestFocus() }
        }
    }

    val currentTimeline by rememberUpdatedState(timeline)
    val keyHandler: (KeyEvent) -> Boolean = handler@{ event ->
        val down = event.type == KeyEventType.KeyDown
        if (listOpen) {
            if (event.key == Key.Back) {
                if (!down) listOpen = false
                return@handler true
            }
            return@handler false
        }
        val digit = event.key.digit()
        val handled = when {
            digit != null -> {
                if (down) onDigit(digit)
                true
            }

            event.key == Key.DirectionUp || event.key == Key.ChannelDown -> {
                if (down) onZap(-1)
                true
            }

            event.key == Key.DirectionDown || event.key == Key.ChannelUp -> {
                if (down) onZap(+1)
                true
            }

            failed -> event.key == Key.Back && run {
                if (!down && !onBack()) onClose()
                true
            }

            event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                if (!down) {
                    if (transportVisible) {
                        onTogglePause()
                        showTransport()
                    } else {
                        listOpen = true
                    }
                }
                true
            }

            event.key == Key.DirectionLeft || event.key == Key.MediaRewind -> {
                if (down && currentTimeline.isLive) {
                    onSeekBy(-SEEK_STEP_MILLIS)
                    showTransport()
                }
                true
            }

            event.key == Key.DirectionRight || event.key == Key.MediaFastForward -> {
                if (down && currentTimeline.isLive) {
                    if (currentTimeline.behindLiveMillis <= SEEK_STEP_MILLIS) onGoLive() else onSeekBy(SEEK_STEP_MILLIS)
                    showTransport()
                }
                true
            }

            event.key == Key.MediaPlayPause || event.key == Key.MediaPlay || event.key == Key.MediaPause ||
                event.key == Key.Spacebar -> {
                if (down) {
                    onTogglePause()
                    showTransport()
                }
                true
            }

            event.key == Key.Info || event.key == Key.Menu || event.key == Key.Guide -> {
                if (down) showBanner()
                true
            }

            event.key == Key.Back -> {
                if (!down) {
                    when {
                        transportVisible && !paused -> transportUntil = 0L
                        onBack() -> Unit
                        else -> onClose()
                    }
                }
                true
            }

            else -> false
        }
        if (handled && down && !failed) showBanner()
        handled
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent(keyHandler)
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        if (player != null) {
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = bannerVisible && !failed,
            enter = fadeIn(tween(TvMotion.OverlayMillis)),
            exit = fadeOut(tween(TvMotion.OverlayMillis)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            InfoBanner(
                item = current,
                channel = channel,
                timeline = timeline,
                sourceState = sourceState,
                transport = transportVisible,
                now = now,
            )
        }

        if (paused && !failed) {
            PausedPill(Modifier.align(Alignment.TopCenter).padding(top = TvSpacing.SafeVertical))
        }

        numberInput?.let { input ->
            NumberEntry(
                input = input,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = TvSpacing.SafeVertical, end = TvSpacing.SafeHorizontal),
            )
        }

        AnimatedVisibility(
            visible = listOpen,
            enter = fadeIn(tween(TvMotion.OverlayMillis)) + slideInHorizontally(tween(TvMotion.OverlayMillis)) { -it / 6 },
            exit = fadeOut(tween(TvMotion.OverlayMillis)) + slideOutHorizontally(tween(TvMotion.OverlayMillis)) { -it / 6 },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            ChannelListOverlay(
                lineup = lineup,
                playingChannelId = channel?.id,
                onSelect = {
                    listOpen = false
                    onPlay(it)
                },
            )
        }

        if (externalChannel != null && !listOpen) {
            HandoffCard(
                title = externalChannel.title,
                onOpen = onOpenExternal,
                onNextChannel = onNextChannel,
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (failed && !listOpen) {
            ErrorCard(
                title = channel?.title.orEmpty(),
                noLink = sourceState?.count == 0,
                repairing = repairing,
                onRepair = onRepair,
                onNextChannel = onNextChannel,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Immutable
data class LiveTimeline(
    val isLive: Boolean = false,
    // distance from the default live position
    val behindLiveMillis: Long = 0L,
    // seekable range up to the default live position
    val windowMillis: Long = 0L,
)

@Composable
private fun rememberLiveTimeline(player: Player?): LiveTimeline {
    var timeline by remember(player) { mutableStateOf(LiveTimeline()) }
    LaunchedEffect(player) {
        val window = Timeline.Window()
        while (player != null) {
            timeline = player.liveTimeline(window)
            delay(500)
        }
    }
    return timeline
}

private fun Player.liveTimeline(window: Timeline.Window): LiveTimeline {
    if (!isCurrentMediaItemLive || currentTimeline.isEmpty) return LiveTimeline()
    currentTimeline.getWindow(currentMediaItemIndex, window)
    val livePosition = window.defaultPositionMs.takeIf { it != C.TIME_UNSET } ?: return LiveTimeline(isLive = true)
    return LiveTimeline(
        isLive = true,
        behindLiveMillis = (livePosition - currentPosition).coerceAtLeast(0L),
        windowMillis = livePosition,
    )
}

@Composable
private fun InfoBanner(
    item: LiveChannel?,
    channel: Channel?,
    timeline: LiveTimeline,
    sourceState: PlaybackSourceState?,
    transport: Boolean,
    now: Long,
) {
    val context = LocalContext.current
    val clock = remember(now / 30_000) { DateFormat.getTimeFormat(context).format(Date(now)) }
    Column(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.Md),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                // controlled contrast over busy lower thirds and tickers
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.22f to Color.Black.copy(alpha = 0.78f),
                    1f to Color.Black.copy(alpha = 0.96f),
                )
            )
            .padding(
                start = TvSpacing.SafeHorizontal,
                end = TvSpacing.SafeHorizontal,
                top = TvSpacing.Xxxl,
                bottom = TvSpacing.SafeVertical,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Lg),
        ) {
            item?.let { ChannelNumberBadge(number = it.number, emphasized = true) }
            ChannelLogo(url = channel?.cover, size = 56.dp)
            Column(
                verticalArrangement = Arrangement.spacedBy(TvSpacing.Xxs),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = channel?.title.orEmpty(),
                    style = TvType.Title,
                    color = TvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        channel?.category?.takeIf(String::isNotBlank),
                        sourceState?.sourceId,
                        sourceState?.height?.let { stringResource(string.tv_diag_height, it) },
                        sourceState?.takeIf { it.count > 1 }?.let { "${it.index + 1}/${it.count}" },
                    ).joinToString(" · "),
                    style = TvType.Label,
                    color = TvColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (timeline.isLive) LiveBadge(behindLiveMillis = timeline.behindLiveMillis)
            Text(text = clock, style = TvType.Title, color = TvColors.TextPrimary, maxLines = 1)
        }
        if (transport && timeline.isLive) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TvSpacing.Lg),
            ) {
                Text(
                    text = "−${formatDuration(timeline.windowMillis)}",
                    style = TvType.Label,
                    color = TvColors.TextSecondary,
                )
                TimeshiftBar(
                    windowMillis = timeline.windowMillis,
                    behindLiveMillis = timeline.behindLiveMillis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (timeline.behindLiveMillis < LIVE_EDGE_TOLERANCE_MILLIS) {
                        stringResource(string.tv_live_badge)
                    } else {
                        stringResource(string.tv_live_behind, formatDuration(timeline.behindLiveMillis))
                    },
                    style = TvType.Label,
                    color = TvColors.TextPrimary,
                )
            }
        }
        Text(
            text = stringResource(if (transport) string.tv_player_hint_timeshift else string.tv_player_hint_live),
            style = TvType.Label,
            color = TvColors.TextMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun PausedPill(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvSpacing.Sm),
        modifier = modifier
            .background(TvColors.Scrim, TvShapes.Pill)
            .padding(horizontal = TvSpacing.Lg, vertical = TvSpacing.Sm),
    ) {
        Icon(Icons.Rounded.Pause, contentDescription = null, tint = TvColors.TextPrimary)
        Text(text = stringResource(string.tv_player_paused), style = TvType.TitleSmall, color = TvColors.TextPrimary)
    }
}

@Composable
private fun NumberEntry(input: ChannelZapper.NumberInput, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(TvSpacing.Xs),
        modifier = modifier
            .widthIn(min = 160.dp)
            .background(TvColors.Scrim, TvShapes.Lg)
            .padding(horizontal = TvSpacing.Xl, vertical = TvSpacing.Lg),
    ) {
        Text(
            text = input.digits + if (input.final) "" else "_",
            style = TvType.Numeral,
            color = if (input.match != null || !input.final) TvColors.TextPrimary else TvColors.Danger,
        )
        Text(
            text = input.match?.let { "" } ?: if (input.final) stringResource(string.tv_number_not_found, input.number) else "",
            style = TvType.Label,
            color = TvColors.TextSecondary,
        )
    }
}

@Composable
private fun ChannelListOverlay(
    lineup: List<LiveChannel>,
    playingChannelId: Int?,
    onSelect: (Channel) -> Unit,
) {
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    val currentIndex = lineup.indexOfFirst { it.channel.id == playingChannelId }.coerceAtLeast(0)
    LaunchedEffect(Unit) {
        listState.scrollToItem((currentIndex - 3).coerceAtLeast(0))
        yield()
        runCatching { currentFocus.requestFocus() }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.Md),
        modifier = Modifier
            .width(400.dp)
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(
                    0f to TvColors.Background.copy(alpha = 0.97f),
                    0.85f to TvColors.Background.copy(alpha = 0.9f),
                    1f to Color.Transparent,
                )
            )
            .padding(start = TvSpacing.SafeHorizontal, top = TvSpacing.SafeVertical, end = TvSpacing.Xl),
    ) {
        Text(text = stringResource(string.tv_channel_list_title), style = TvType.Title, color = TvColors.TextPrimary)
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(TvSpacing.Xs),
            contentPadding = PaddingValues(bottom = TvSpacing.SafeVertical),
            modifier = Modifier.focusGroup(),
        ) {
            itemsIndexed(lineup, key = { _, item -> item.channel.id }) { index, item ->
                TvChannelRow(
                    item = item,
                    playing = item.channel.id == playingChannelId,
                    onClick = { onSelect(item.channel) },
                    focusRequester = if (index == currentIndex) currentFocus else null,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(
    title: String,
    noLink: Boolean,
    repairing: Boolean,
    onRepair: () -> Unit,
    onNextChannel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repairFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        yield()
        runCatching { repairFocus.requestFocus() }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.Lg),
        modifier = modifier
            .widthIn(max = 560.dp)
            .background(TvColors.Surface.copy(alpha = 0.96f), TvShapes.Lg)
            .padding(TvSpacing.Xl)
            .focusGroup(),
    ) {
        Text(
            text = stringResource(string.tv_player_error_title, title),
            style = TvType.Title,
            color = TvColors.TextPrimary,
        )
        Text(
            text = stringResource(if (noLink) string.tv_player_error_no_link else string.tv_player_error_body),
            style = TvType.Body,
            color = TvColors.TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.Md)) {
            TvActionButton(
                text = if (repairing) {
                    stringResource(string.tv_link_repairing, title)
                } else {
                    stringResource(string.tv_action_repair_channel)
                },
                icon = Icons.Rounded.Build,
                onClick = onRepair,
                focusRequester = repairFocus,
            )
            TvActionButton(
                text = stringResource(string.tv_action_next_channel),
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                onClick = onNextChannel,
            )
        }
    }
}

/**
 * The channel's official live is on YouTube: offer the YouTube app, never play it here.
 */
@Composable
private fun HandoffCard(
    title: String,
    onOpen: () -> Unit,
    onNextChannel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openFocus = remember { FocusRequester() }
    LaunchedEffect(title) {
        yield()
        runCatching { openFocus.requestFocus() }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.Lg),
        modifier = modifier
            .widthIn(max = 560.dp)
            .background(TvColors.Surface.copy(alpha = 0.96f), TvShapes.Lg)
            .padding(TvSpacing.Xl)
            .focusGroup(),
    ) {
        Text(
            text = stringResource(string.tv_handoff_title, title),
            style = TvType.Title,
            color = TvColors.TextPrimary,
        )
        Text(
            text = stringResource(string.tv_handoff_body),
            style = TvType.Body,
            color = TvColors.TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.Md)) {
            TvActionButton(
                text = stringResource(string.tv_action_open_youtube),
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                onClick = onOpen,
                focusRequester = openFocus,
            )
            TvActionButton(
                text = stringResource(string.tv_action_next_channel),
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                onClick = onNextChannel,
            )
        }
    }
}

private fun Key.digit(): Int? = when (this) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

private const val SEEK_STEP_MILLIS = 10_000L
