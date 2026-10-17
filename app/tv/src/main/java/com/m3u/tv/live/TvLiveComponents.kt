package com.m3u.tv.live

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.m3u.data.registry.match.MatchTier
import com.m3u.data.repository.linkupdate.LinkUpdateProgress
import com.m3u.i18n.R.string
import com.m3u.tv.FocusFrame
import com.m3u.tv.TvColors
import com.m3u.tv.TvMotion
import com.m3u.tv.TvShapes
import com.m3u.tv.TvSpacing
import com.m3u.tv.TvType
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Channel number in the broadcast accent. [emphasized] fills it (focused or on air).
 */
@Composable
fun ChannelNumberBadge(
    number: Int,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    onFocusContainer: Boolean = false,
) {
    val container = when {
        emphasized -> TvColors.Live
        onFocusContainer -> TvColors.OnFocus.copy(alpha = 0.08f)
        else -> TvColors.SurfaceRaised
    }
    val content = when {
        emphasized -> TvColors.OnLive
        onFocusContainer -> TvColors.OnFocus
        else -> TvColors.TextPrimary
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .widthIn(min = 44.dp)
            .height(32.dp)
            .clip(TvShapes.Sm)
            .background(container)
            .padding(horizontal = TvSpacing.Sm),
    ) {
        Text(text = number.toString(), style = TvType.Number, color = content, maxLines = 1)
    }
}

@Composable
fun StatusDot(status: LiveStatus, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(status.color())
    )
}

fun LiveStatus.color(): Color = when (this) {
    LiveStatus.OK, LiveStatus.EXTERNAL -> TvColors.Success
    LiveStatus.STALE, LiveStatus.UNTESTED -> TvColors.Accent
    LiveStatus.FAILING -> TvColors.Danger
    LiveStatus.NO_LINK -> TvColors.TextMuted
}

@Composable
fun LiveStatus.label(): String = stringResource(
    when (this) {
        LiveStatus.OK -> string.tv_status_ok
        LiveStatus.STALE -> string.tv_status_stale
        LiveStatus.UNTESTED -> string.tv_status_untested
        LiveStatus.FAILING -> string.tv_status_failing
        LiveStatus.NO_LINK -> string.tv_status_no_link
        LiveStatus.EXTERNAL -> string.tv_status_external
    }
)

@Composable
fun MatchTier.label(): String = stringResource(
    when (this) {
        MatchTier.PINNED -> string.tv_tier_pinned
        MatchTier.EXACT_ID -> string.tv_tier_exact
        MatchTier.ALIAS -> string.tv_tier_alias
        MatchTier.FUZZY -> string.tv_tier_fuzzy
    }
)

/**
 * "CANLI" with a pulse dot at the live edge; the distance to live once rewound or paused.
 */
@Composable
fun LiveBadge(behindLiveMillis: Long, modifier: Modifier = Modifier) {
    val atLive = behindLiveMillis < LIVE_EDGE_TOLERANCE_MILLIS
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvSpacing.Xs + TvSpacing.Xxs),
        modifier = modifier
            .clip(TvShapes.Pill)
            .background(if (atLive) TvColors.Live else TvColors.SurfaceRaised)
            .padding(horizontal = TvSpacing.Md - TvSpacing.Xxs, vertical = TvSpacing.Xs),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (atLive) TvColors.OnLive else TvColors.Live)
        )
        Text(
            text = if (atLive) stringResource(string.tv_live_badge) else "−${formatDuration(behindLiveMillis)}",
            style = TvType.Caption,
            color = TvColors.OnLive,
            maxLines = 1,
        )
    }
}

/**
 * Logo on a soft tile; logos are usually transparent PNGs drawn for light backgrounds, so the tile
 * keeps them readable on ink. Falls back to a TV glyph.
 */
@Composable
fun ChannelLogo(url: String?, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(TvShapes.Md)
            .background(Color.White.copy(alpha = 0.92f))
            .padding(size / 8),
    ) {
        if (url.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Rounded.Tv,
                contentDescription = null,
                tint = TvColors.OnFocus.copy(alpha = 0.5f),
                modifier = Modifier.size(size / 2),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * One row of the ranked lineup: number, logo, name, group and link health.
 */
@Composable
fun TvChannelRow(
    item: LiveChannel,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    onFocused: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    compact: Boolean = false,
) {
    // a held OK fires onLongClick once; the release that follows must not also play the channel
    var longPressed by remember { mutableStateOf(false) }
    FocusFrame(
        onClick = {
            if (longPressed) longPressed = false else onClick()
        },
        onFocus = onFocused,
        focusRequester = focusRequester,
        shape = TvShapes.Md,
        focusedScale = TvMotion.RowFocusScale,
        focusedBorderWidth = 0.dp,
        semanticRole = Role.Button,
        semanticsLabel = "${item.number}. ${item.channel.title}",
        onKey = { event ->
            event.isLongPressConfirm().also { held ->
                if (held) {
                    longPressed = true
                    onLongClick()
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 52.dp else 64.dp),
    ) { focused ->
        val primary = if (focused) TvColors.OnFocus else TvColors.TextPrimary
        val secondary = if (focused) TvColors.OnFocus.copy(alpha = 0.66f) else TvColors.TextSecondary
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Md),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = TvSpacing.Md, vertical = TvSpacing.Sm),
        ) {
            ChannelNumberBadge(
                number = item.number,
                emphasized = playing || focused,
            )
            if (!compact) ChannelLogo(url = item.channel.cover, size = 44.dp)
            Column(
                verticalArrangement = Arrangement.spacedBy(TvSpacing.Xxs),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = item.channel.title,
                    style = if (compact) TvType.Label else TvType.TitleSmall,
                    color = primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact) {
                    Text(
                        text = listOfNotNull(
                            item.channel.category.takeIf(String::isNotBlank),
                            if (playing) stringResource(string.tv_live_now_playing) else null,
                        ).joinToString(" · "),
                        style = TvType.Label,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (playing) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = if (focused) TvColors.Live else TvColors.Live.copy(alpha = 0.9f),
                    modifier = Modifier.size(20.dp),
                )
            }
            StatusDot(status = item.status)
        }
    }
}

/**
 * Transient notice for automatic actions ("Switching source…", update summary). Opaque so it reads
 * over video and over focused controls; never focusable.
 */
@Composable
fun TvToast(text: String, modifier: Modifier = Modifier, accent: Color = TvColors.Live) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvSpacing.Sm),
        modifier = modifier
            .widthIn(max = 480.dp)
            .shadow(16.dp, TvShapes.Lg, clip = false)
            .clip(TvShapes.Lg)
            .background(TvColors.SurfaceRaised)
            .border(1.dp, TvColors.Outline, TvShapes.Lg)
            .padding(horizontal = TvSpacing.Lg, vertical = TvSpacing.Sm + TvSpacing.Xxs),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Text(
            text = text,
            style = TvType.Label,
            color = TvColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Link update progress as a compact line under a screen header.
 */
@Composable
fun LinkUpdateProgressLine(progress: LinkUpdateProgress.Running, modifier: Modifier = Modifier) {
    val text = when (progress.phase) {
        LinkUpdateProgress.Phase.FETCHING -> stringResource(string.tv_link_progress_fetching, progress.done, progress.total)
        LinkUpdateProgress.Phase.MATCHING -> stringResource(string.tv_link_progress_matching)
        LinkUpdateProgress.Phase.VALIDATING -> stringResource(string.tv_link_progress_validating, progress.done, progress.total)
        LinkUpdateProgress.Phase.SAVING -> stringResource(string.tv_link_progress_saving)
    }
    val fraction by animateFloatAsState(
        targetValue = progress.fraction(),
        label = "link-update-progress",
    )
    Column(verticalArrangement = Arrangement.spacedBy(TvSpacing.Xs), modifier = modifier) {
        Text(text = text, style = TvType.Label, color = TvColors.TextSecondary, maxLines = 1)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(TvShapes.Pill)
                .background(TvColors.SurfaceRaised)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(maxWidth * fraction)
                    .clip(TvShapes.Pill)
                    .background(TvColors.Live)
            )
        }
    }
}

/**
 * Seekable range of a live stream with the playhead and the live edge.
 */
@Composable
fun TimeshiftBar(
    windowMillis: Long,
    behindLiveMillis: Long,
    modifier: Modifier = Modifier,
) {
    val position = if (windowMillis <= 0L) 1f else (1f - behindLiveMillis.toFloat() / windowMillis).coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(TvShapes.Pill)
                .background(Color.White.copy(alpha = 0.24f))
        )
        Box(
            Modifier
                .width(maxWidth * position)
                .height(4.dp)
                .clip(TvShapes.Pill)
                .background(TvColors.Live)
        )
        Box(
            Modifier
                .offset(x = (maxWidth - 16.dp) * position)
                .size(16.dp)
                .clip(CircleShape)
                .background(TvColors.Focus)
        )
    }
}

fun LinkUpdateProgress.Running.fraction(): Float {
    val base = when (phase) {
        LinkUpdateProgress.Phase.FETCHING -> 0f
        LinkUpdateProgress.Phase.MATCHING -> 0.2f
        LinkUpdateProgress.Phase.VALIDATING -> 0.25f
        LinkUpdateProgress.Phase.SAVING -> 0.95f
    }
    val span = when (phase) {
        LinkUpdateProgress.Phase.FETCHING -> 0.2f
        LinkUpdateProgress.Phase.VALIDATING -> 0.7f
        else -> 0.05f
    }
    val step = if (total > 0) done.toFloat() / total else 0f
    return (base + span * step).coerceIn(0f, 1f)
}

fun formatDuration(millis: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis.coerceAtLeast(0L))
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

fun relativeTime(time: Long, now: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()

/**
 * A held confirm key: Android repeats KeyDown while it is held.
 */
fun KeyEvent.isLongPressConfirm(): Boolean =
    type == KeyEventType.KeyDown &&
        (key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter) &&
        nativeKeyEvent.repeatCount == LONG_PRESS_REPEAT_COUNT

const val LIVE_EDGE_TOLERANCE_MILLIS = 15_000L
private const val LONG_PRESS_REPEAT_COUNT = 1
