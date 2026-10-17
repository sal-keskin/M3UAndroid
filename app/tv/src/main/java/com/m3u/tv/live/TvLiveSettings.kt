package com.m3u.tv.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.m3u.data.repository.linkupdate.LinkUpdateProgress
import com.m3u.i18n.R.string
import com.m3u.tv.TvActionButton
import com.m3u.tv.TvColors
import com.m3u.tv.TvShapes
import com.m3u.tv.TvSpacing
import com.m3u.tv.TvType
import kotlinx.coroutines.yield

/**
 * Live TV block at the top of Settings: update links, diagnostics, start-on-last-channel,
 * automatic updates and the registry url. One horizontal row so ▼ reaches the rest of Settings.
 */
@Composable
fun TvLiveSettingsSection(
    state: TvLiveUiState,
    onUpdateLinks: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onStartOnLastChannel: (Boolean) -> Unit,
    onAutoUpdate: (Boolean) -> Unit,
    onEditRegistryUrl: () -> Unit,
    onOfficialLinks: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.Sm),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = TvSpacing.SafeVertical),
    ) {
        Text(
            text = stringResource(string.tv_live_title),
            style = TvType.Title,
            color = TvColors.TextPrimary,
            modifier = Modifier.padding(horizontal = TvSpacing.SafeHorizontal),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Md),
            contentPadding = PaddingValues(horizontal = TvSpacing.SafeHorizontal, vertical = TvSpacing.Sm),
        ) {
            item {
                TvActionButton(
                    text = stringResource(string.tv_action_update_links),
                    icon = Icons.Rounded.Sync,
                    onClick = onUpdateLinks,
                    supportingText = liveSubtitle(state),
                )
            }
            item {
                TvActionButton(
                    text = stringResource(string.tv_action_open_diagnostics),
                    icon = Icons.Rounded.MonitorHeart,
                    onClick = onOpenDiagnostics,
                )
            }
            item {
                TvActionButton(
                    text = stringResource(string.tv_settings_start_last),
                    icon = Icons.Rounded.History,
                    onClick = { onStartOnLastChannel(!state.startOnLastChannel) },
                    checked = state.startOnLastChannel,
                    supportingText = onOff(state.startOnLastChannel),
                )
            }
            item {
                TvActionButton(
                    text = stringResource(string.tv_settings_auto_update),
                    icon = Icons.Rounded.Autorenew,
                    onClick = { onAutoUpdate(!state.autoUpdate) },
                    checked = state.autoUpdate,
                    supportingText = onOff(state.autoUpdate),
                )
            }
            item {
                TvActionButton(
                    text = stringResource(string.tv_settings_official_links),
                    icon = Icons.Rounded.Public,
                    onClick = { onOfficialLinks(!state.officialLinks) },
                    checked = state.officialLinks,
                    supportingText = stringResource(
                        string.tv_settings_official_links_state,
                        onOff(state.officialLinks),
                    ),
                )
            }
            item {
                TvActionButton(
                    text = stringResource(string.tv_settings_registry_url),
                    icon = Icons.Rounded.Link,
                    onClick = onEditRegistryUrl,
                    supportingText = state.baseUrl,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
            }
        }
        (state.progress as? LinkUpdateProgress.Running)?.let { running ->
            LinkUpdateProgressLine(
                running,
                modifier = Modifier
                    .padding(horizontal = TvSpacing.SafeHorizontal)
                    .fillMaxWidth(0.5f),
            )
        }
    }
}

@Composable
private fun onOff(value: Boolean): String =
    stringResource(if (value) string.tv_settings_on else string.tv_settings_off)

/**
 * Full-screen panel to edit the registry url with the TV keyboard. ▼ leaves the field for
 * Save / Reset; Back closes without saving.
 */
@Composable
fun RegistryUrlPanel(
    currentUrl: String,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(currentUrl) { mutableStateOf(currentUrl) }
    var focused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val label = stringResource(string.tv_settings_registry_url)

    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) {
        yield()
        runCatching { fieldFocus.requestFocus() }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background.copy(alpha = 0.94f)),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(TvSpacing.Lg),
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .background(TvColors.Surface, TvShapes.Lg)
                .padding(TvSpacing.Xl),
        ) {
            Text(text = label, style = TvType.Title, color = TvColors.TextPrimary)
            Text(
                text = stringResource(string.tv_settings_registry_url_hint),
                style = TvType.Body,
                color = TvColors.TextSecondary,
            )
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = TvType.Body.copy(color = TvColors.TextPrimary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { saveFocus.requestFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .focusRequester(fieldFocus)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            focusManager.moveFocus(FocusDirection.Down)
                        } else {
                            false
                        }
                    }
                    .semantics { contentDescription = label }
                    .border(
                        width = if (focused) 3.dp else 1.dp,
                        color = if (focused) TvColors.Focus else TvColors.TextMuted,
                        shape = TvShapes.Md,
                    )
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.Md)) {
                TvActionButton(
                    text = stringResource(string.tv_settings_save),
                    icon = Icons.Rounded.Check,
                    onClick = { onSave(draft) },
                    focusRequester = saveFocus,
                )
                TvActionButton(
                    text = stringResource(string.tv_settings_reset),
                    icon = Icons.Rounded.Restore,
                    onClick = onReset,
                )
            }
        }
    }
}
