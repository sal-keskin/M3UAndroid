package com.m3u.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.m3u.data.registry.validate.StreamStatus
import com.m3u.data.repository.linkupdate.ChannelDiagnostics
import com.m3u.data.repository.linkupdate.LinkUpdateProgress
import com.m3u.data.repository.linkupdate.StreamDiagnostics
import com.m3u.i18n.R.string
import com.m3u.tv.FocusFrame
import com.m3u.tv.TvActionButton
import com.m3u.tv.TvColors
import com.m3u.tv.TvMotion
import com.m3u.tv.TvShapes
import com.m3u.tv.TvSpacing
import com.m3u.tv.TvType
import kotlinx.coroutines.yield

/**
 * Per-channel link report: primary and fallback urls, source, match tier, last check and the
 * failure reason. ◀ ▶ moves between the channel list and the Repair action.
 */
@Composable
fun TvDiagnosticsScreen(
    state: TvLiveUiState,
    onRevalidate: () -> Unit,
    onRepair: (canonicalId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focusedId by remember { mutableStateOf<String?>(null) }
    val firstRow = remember { FocusRequester() }
    val selected = state.diagnostics.firstOrNull { it.canonicalId == focusedId } ?: state.diagnostics.firstOrNull()

    LaunchedEffect(state.diagnostics.isNotEmpty()) {
        if (state.diagnostics.isEmpty()) return@LaunchedEffect
        yield()
        runCatching { firstRow.requestFocus() }
    }

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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Md),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(TvSpacing.Xs),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(string.tv_diagnostics_title),
                    style = TvType.Headline,
                    color = TvColors.TextPrimary,
                )
                Text(
                    text = reportLine(state),
                    style = TvType.Body,
                    color = TvColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TvActionButton(
                text = stringResource(string.tv_action_revalidate),
                icon = Icons.Rounded.Sync,
                onClick = onRevalidate,
            )
        }
        (state.progress as? LinkUpdateProgress.Running)?.let { running ->
            LinkUpdateProgressLine(running, modifier = Modifier.fillMaxWidth(0.6f))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Xl),
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(TvSpacing.Xs),
                contentPadding = PaddingValues(vertical = TvSpacing.Sm),
                modifier = Modifier
                    .weight(0.46f)
                    .fillMaxHeight()
                    .focusGroup(),
            ) {
                itemsIndexed(state.diagnostics, key = { _, item -> item.canonicalId }) { index, item ->
                    DiagnosticsRow(
                        item = item,
                        onFocused = { focusedId = item.canonicalId },
                        focusRequester = if (index == 0) firstRow else null,
                    )
                }
            }
            selected?.let { item ->
                DiagnosticsDetail(
                    item = item,
                    repairing = state.repairingChannelId == item.canonicalId,
                    onRepair = { onRepair(item.canonicalId) },
                    modifier = Modifier
                        .weight(0.54f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun reportLine(state: TvLiveUiState): String {
    val report = state.lastReport ?: return stringResource(string.tv_diagnostics_subtitle)
    val summary = stringResource(
        string.tv_link_summary,
        report.updated,
        report.unchanged,
        report.failing,
        report.newFuzzy,
    )
    val errors = report.sourceErrors.keys.takeIf { it.isNotEmpty() }
        ?.let { stringResource(string.tv_diag_source_errors, it.joinToString(", ")) }
    return listOfNotNull(relativeTime(report.finishedAt), summary, errors).joinToString(" · ")
}

@Composable
private fun DiagnosticsRow(
    item: ChannelDiagnostics,
    onFocused: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val status = item.status()
    FocusFrame(
        onClick = {},
        onFocus = onFocused,
        focusRequester = focusRequester,
        shape = TvShapes.Md,
        focusedScale = TvMotion.RowFocusScale,
        focusedBorderWidth = 0.dp,
        semanticRole = Role.Button,
        semanticsLabel = "${item.rank}. ${item.title}",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
    ) { focused ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Md),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = TvSpacing.Md, vertical = TvSpacing.Sm),
        ) {
            ChannelNumberBadge(number = item.rank, onFocusContainer = focused)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TvSpacing.Xxs)) {
                Text(
                    text = item.title,
                    style = TvType.TitleSmall,
                    color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.primary?.let { primary ->
                        listOfNotNull(primary.sourceId, primary.tier.label()).joinToString(" · ")
                    } ?: status.label(),
                    style = TvType.Label,
                    color = if (focused) TvColors.OnFocus.copy(alpha = 0.66f) else TvColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusDot(status)
        }
    }
}

@Composable
private fun DiagnosticsDetail(
    item: ChannelDiagnostics,
    repairing: Boolean,
    onRepair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.Md),
        modifier = modifier
            .padding(vertical = TvSpacing.Sm)
            .background(TvColors.Surface.copy(alpha = 0.72f), TvShapes.Lg)
            .padding(TvSpacing.Lg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Md),
        ) {
            ChannelNumberBadge(number = item.rank, emphasized = true)
            Text(
                text = item.title,
                style = TvType.Title,
                color = TvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TvActionButton(
                text = if (repairing) {
                    stringResource(string.tv_link_repairing, item.title)
                } else {
                    stringResource(string.tv_action_repair_channel)
                },
                icon = Icons.Rounded.Build,
                onClick = onRepair,
            )
        }
        item.officialLivePage?.let { page ->
            Text(
                text = stringResource(string.tv_diag_official_page, page),
                style = TvType.Label,
                color = TvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        if (item.streams.isEmpty()) {
            Text(text = stringResource(string.tv_diag_empty), style = TvType.Body, color = TvColors.TextSecondary)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(TvSpacing.Sm)) {
            items(item.streams, key = { it.url }) { stream ->
                StreamBlock(stream)
            }
        }
    }
}

@Composable
private fun StreamBlock(stream: StreamDiagnostics) {
    Column(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.Xxs),
        modifier = Modifier
            .fillMaxWidth()
            .background(TvColors.SurfaceRaised.copy(alpha = 0.6f), TvShapes.Md)
            .padding(TvSpacing.Md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Sm),
        ) {
            StatusDot(stream.status.liveStatus(), size = 8.dp)
            Text(
                text = if (stream.position == 0) {
                    stringResource(string.tv_diag_primary)
                } else {
                    stringResource(string.tv_diag_fallback, stream.position)
                },
                style = TvType.Caption,
                color = TvColors.TextPrimary,
            )
            Text(
                text = listOfNotNull(
                    stream.sourceId,
                    stream.tier.label(),
                    stream.height?.let { stringResource(string.tv_diag_height, it) },
                    stream.latencyMs?.let { stringResource(string.tv_diag_latency, it.toInt()) },
                    if (stream.hasHeaders) stringResource(string.tv_diag_headers) else null,
                ).joinToString(" · "),
                style = TvType.Label,
                color = TvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stream.url,
            style = TvType.Label,
            color = TvColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        Text(
            text = listOfNotNull(
                if (stream.checkedAt > 0L) {
                    stringResource(string.tv_diag_checked, relativeTime(stream.checkedAt))
                } else {
                    stringResource(string.tv_diag_never_checked)
                },
                stream.failureReason?.let { stringResource(string.tv_diag_reason, it) },
            ).joinToString(" · "),
            style = TvType.Label,
            color = if (stream.status == StreamStatus.FAILED) TvColors.Danger else TvColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

fun ChannelDiagnostics.status(): LiveStatus {
    val primary = primary ?: return LiveStatus.NO_LINK
    if (working) return if (primary.status == StreamStatus.OK) LiveStatus.OK else LiveStatus.STALE
    return primary.status.liveStatus()
}

fun StreamStatus.liveStatus(): LiveStatus = when (this) {
    StreamStatus.OK -> LiveStatus.OK
    StreamStatus.STALE -> LiveStatus.STALE
    StreamStatus.UNTESTED -> LiveStatus.UNTESTED
    StreamStatus.FAILED -> LiveStatus.FAILING
}
