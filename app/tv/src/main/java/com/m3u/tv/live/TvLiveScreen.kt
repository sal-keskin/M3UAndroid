package com.m3u.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel
import com.m3u.data.repository.linkupdate.LinkUpdateProgress
import com.m3u.i18n.R.plurals
import com.m3u.i18n.R.string
import com.m3u.tv.TvActionButton
import com.m3u.tv.TvColors
import com.m3u.tv.TvShapes
import com.m3u.tv.TvSpacing
import com.m3u.tv.TvType
import kotlinx.coroutines.yield

/**
 * The fork's home: the curated lineup in channel-number order, with link health and the
 * "Update links" / "Diagnostics" actions. Initial focus lands on the channel on air (or the last
 * one watched), otherwise on channel 1.
 */
@Composable
fun TvLiveScreen(
    state: TvLiveUiState,
    playingChannelId: Int?,
    recentChannelId: Int?,
    onPlay: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onUpdateLinks: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.Lg),
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = TvSpacing.SafeHorizontal,
                end = TvSpacing.SafeHorizontal,
                top = TvSpacing.SafeVertical,
                bottom = TvSpacing.Lg,
            ),
    ) {
        LiveHeader(
            state = state,
            onUpdateLinks = onUpdateLinks,
            onOpenDiagnostics = onOpenDiagnostics,
        )
        if (state.loaded && state.channels.isEmpty()) {
            LiveEmpty(modifier = Modifier.weight(1f))
        } else {
            LiveLineup(
                state = state,
                playingChannelId = playingChannelId,
                recentChannelId = recentChannelId,
                onPlay = onPlay,
                onToggleFavorite = onToggleFavorite,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LiveHeader(
    state: TvLiveUiState,
    onUpdateLinks: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TvSpacing.Md)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Md),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(TvSpacing.Xs),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(string.tv_live_title),
                    style = TvType.Headline,
                    color = TvColors.TextPrimary,
                    maxLines = 1,
                )
                Text(
                    text = liveSubtitle(state),
                    style = TvType.Body,
                    color = TvColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TvActionButton(
                text = stringResource(string.tv_action_update_links),
                icon = Icons.Rounded.Sync,
                onClick = onUpdateLinks,
            )
            TvActionButton(
                text = stringResource(string.tv_action_open_diagnostics),
                icon = Icons.Rounded.MonitorHeart,
                onClick = onOpenDiagnostics,
            )
        }
        (state.progress as? LinkUpdateProgress.Running)?.let { running ->
            LinkUpdateProgressLine(running, modifier = Modifier.fillMaxWidth(0.6f))
        }
    }
}

@Composable
fun liveSubtitle(state: TvLiveUiState): String {
    val count = pluralStringResource(plurals.tv_live_channel_count, state.channels.size, state.channels.size)
    val updated = state.lastReport
        ?.takeIf { it.validated }
        ?.let { stringResource(string.tv_live_updated, relativeTime(it.finishedAt)) }
        ?: stringResource(string.tv_live_never_updated)
    return "$count · $updated"
}

@Composable
private fun LiveLineup(
    state: TvLiveUiState,
    playingChannelId: Int?,
    recentChannelId: Int?,
    onPlay: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val initialFocus = remember { FocusRequester() }
    var focusedChannelId by remember { mutableStateOf<Int?>(null) }
    var initialFocusDone by remember { mutableStateOf(false) }
    val anchorId = playingChannelId ?: recentChannelId
    val anchorIndex = state.channels.indexOfFirst { it.channel.id == anchorId }.coerceAtLeast(0)

    LaunchedEffect(state.channels.isNotEmpty()) {
        if (state.channels.isEmpty() || initialFocusDone) return@LaunchedEffect
        listState.scrollToItem((anchorIndex - 2).coerceAtLeast(0))
        yield()
        runCatching { initialFocus.requestFocus() }
        initialFocusDone = true
    }

    val focused = state.channels.firstOrNull { it.channel.id == focusedChannelId }
        ?: state.channels.getOrNull(anchorIndex)

    Row(
        horizontalArrangement = Arrangement.spacedBy(TvSpacing.Xl),
        modifier = modifier.fillMaxWidth(),
    ) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(TvSpacing.Xs + TvSpacing.Xxs),
            contentPadding = PaddingValues(vertical = TvSpacing.Sm),
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight()
                .focusGroup(),
        ) {
            itemsIndexed(state.channels, key = { _, item -> item.channel.id }) { index, item ->
                TvChannelRow(
                    item = item,
                    playing = item.channel.id == playingChannelId,
                    onClick = { onPlay(item.channel) },
                    onLongClick = { onToggleFavorite(item.channel) },
                    onFocused = { focusedChannelId = item.channel.id },
                    focusRequester = if (index == anchorIndex) initialFocus else null,
                )
            }
        }
        focused?.let { item ->
            ChannelPreview(
                item = item,
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight(),
            )
        }
    }
}

/**
 * Non-focusable detail of the focused row; reading it never costs a key press.
 */
@Composable
private fun ChannelPreview(item: LiveChannel, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.Lg),
        modifier = modifier
            .padding(vertical = TvSpacing.Sm)
            .background(TvColors.Surface.copy(alpha = 0.72f), TvShapes.Lg)
            .padding(TvSpacing.Xl),
    ) {
        ChannelLogo(url = item.channel.cover, size = 96.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Md),
        ) {
            ChannelNumberBadge(number = item.number, emphasized = true)
            Text(
                text = item.channel.title,
                style = TvType.Title,
                color = TvColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.channel.favourite) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = TvColors.Live,
                )
            }
        }
        if (item.channel.category.isNotBlank()) {
            Text(text = item.channel.category, style = TvType.Body, color = TvColors.TextSecondary)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Sm),
        ) {
            StatusDot(item.status)
            Text(text = item.status.label(), style = TvType.Label, color = TvColors.TextPrimary)
        }
        sourceLine(item)?.let { line ->
            Text(
                text = line,
                style = TvType.Label,
                color = TvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.weight(1f))
        Text(
            text = stringResource(string.tv_live_press_ok),
            style = TvType.Label,
            color = TvColors.TextMuted,
        )
    }
}

@Composable
private fun sourceLine(item: LiveChannel): String? = listOfNotNull(
    item.sourceId,
    item.height?.let { stringResource(string.tv_diag_height, it) },
    item.latencyMs?.let { stringResource(string.tv_diag_latency, it.toInt()) },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

@Composable
private fun LiveEmpty(modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.Sm),
        modifier = modifier.padding(top = TvSpacing.Xxl),
    ) {
        Text(
            text = stringResource(string.tv_live_empty_title),
            style = TvType.Title,
            color = TvColors.TextPrimary,
        )
        Text(
            text = stringResource(string.tv_live_empty_body),
            style = TvType.Body,
            color = TvColors.TextSecondary,
        )
    }
}
