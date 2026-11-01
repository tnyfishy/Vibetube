package vibe.tube.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import vibe.tube.R
import vibe.tube.data.settings.AppSettings
import androidx.compose.ui.zIndex
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.Avatar
import vibe.tube.ui.components.LocalVideoActions
import vibe.tube.ui.components.NavBarEntry
import vibe.tube.ui.components.NavBarRow
import vibe.tube.ui.components.NoOpVideoActions
import vibe.tube.ui.components.SearchHeaderRow
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.VideoCard
import vibe.tube.ui.theme.EffectiveScale
import vibe.tube.ui.theme.LocalDeviceDensity
import vibe.tube.ui.theme.ScaledDensity
import vibe.tube.ui.theme.Spacing
import vibe.tube.ui.theme.UiScaling
import vibe.tube.ui.theme.rememberEffectiveScale
import vibe.tube.youtube.model.VideoItem
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySizeSheet(settings: AppSettings, onChange: (AppSettings.() -> AppSettings) -> Unit, onDismiss: () -> Unit) {
    var display by remember { mutableIntStateOf(settings.displayScale) }
    var text by remember { mutableIntStateOf(settings.textScale) }
    val scale = rememberEffectiveScale(display, text)
    val systemFont = (LocalDeviceDensity.current ?: androidx.compose.ui.platform.LocalDensity.current).fontScale
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp

    fun finish() {
        onChange { copy(displayScale = display, textScale = text) }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = ::finish, sheetState = sheetState) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.settings_display_size), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.lg))

            FeedPreview(
                scale,
                settings,
                Modifier
                    .fillMaxWidth()
                    .height((screenHeight * 0.42f).coerceIn(280f, 460f).dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            ScaleSlider(
                modifier = Modifier.padding(horizontal = Spacing.lg),
                label = stringResource(R.string.settings_display_scale),
                value = display,
                range = UiScaling.DISPLAY_MIN..UiScaling.DISPLAY_MAX,
                note = if (scale.displayLimited) stringResource(R.string.settings_display_scale_limited, scale.displayPercent) else null,
                onChange = { display = it },
            )
            ScaleSlider(
                modifier = Modifier.padding(horizontal = Spacing.lg),
                label = stringResource(R.string.settings_text_scale),
                value = text,
                range = UiScaling.TEXT_MIN..UiScaling.TEXT_MAX,
                note = when {
                    scale.textLimited -> stringResource(R.string.settings_text_scale_limited, scale.textPercent)
                    systemFont != 1f -> stringResource(R.string.settings_text_scale_system, (systemFont * 100).roundToInt())
                    else -> null
                },
                onChange = { text = it },
            )
            OutlinedButton(
                shapes = ButtonDefaults.shapes(),
                enabled = display != 100 || text != 100,
                onClick = {
                    display = 100
                    text = 100
                },
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
            ) { Text(stringResource(R.string.settings_display_reset)) }
        }
    }
}

@Composable
private fun ScaleSlider(label: String, value: Int, range: IntRange, note: String?, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text("$value%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange((it / UiScaling.STEP).roundToInt() * UiScaling.STEP) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / UiScaling.STEP - 1,
            modifier = Modifier.semantics { contentDescription = label },
        )
        
        Text(
            note ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 1,
            maxLines = 2,
        )
    }
}

@Composable
internal fun FeedPreview(scale: EffectiveScale, settings: AppSettings, modifier: Modifier = Modifier) {
    val samples = previewVideos()
    val entries = listOf(
        NavBarEntry(stringResource(R.string.nav_home), VIcon.Home, VIcon.HomeFilled),
        NavBarEntry(stringResource(R.string.nav_shorts), VIcon.Shorts, VIcon.ShortsFilled, vibe.tube.ui.components.SHORTS_ICON_SCALE),
        NavBarEntry(stringResource(R.string.nav_subscriptions), VIcon.Subscriptions, VIcon.SubscriptionsFilled),
        NavBarEntry(stringResource(R.string.nav_library), VIcon.VideoLibrary, VIcon.VideoLibraryFilled),
        NavBarEntry(stringResource(R.string.nav_account), VIcon.AccountCircle, VIcon.AccountCircleFilled),
    )
    val previewLabel = stringResource(R.string.settings_display_preview)
    Box(modifier.semantics { contentDescription = previewLabel }) {
        ScaledDensity(scale) {
            CompositionLocalProvider(LocalVideoActions provides NoOpVideoActions) {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize().clearAndSetSemantics { }) {
                    Column(Modifier.fillMaxSize()) {
                        SearchHeaderRow(
                            title = stringResource(R.string.app_name),
                            onSearch = {},
                            modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.sm),
                            wordmark = true,
                            shown = { false },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TooltipIconButton(VIcon.Notifications, stringResource(R.string.notifications_title), {}, iconSize = vibe.tube.feature.home.HeaderIconSize)
                                Box(Modifier.padding(horizontal = 7.dp)) {
                                    Avatar(null, vibe.tube.feature.home.HeaderAvatarSize, shape = androidx.compose.material3.MaterialShapes.Cookie12Sided.toShape())
                                }
                            }
                        }
                        Column(
                            Modifier.weight(1f).fillMaxWidth().clipToBounds().padding(horizontal = Spacing.gutter),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            samples.forEach { VideoCard(it, emptyList()) }
                        }
                        // Mirrors the real bar's label and size settings; drawn above the feed so card overlays never cover it.
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.zIndex(1f)) {
                            NavBarRow(
                                entries, selectedIndex = 0, onSelect = {},
                                modifier = Modifier.fillMaxWidth().height(settings.navBarHeight.dp),
                                showLabels = !settings.navHideLabels, iconSize = settings.navIconSize.dp,
                            )
                        }
                    }
                }
            }
        }
        
        Box(
            Modifier.matchParentSize().pointerInput(Unit) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
        )
    }
}

@Composable
private fun previewVideos(): List<VideoItem> {
    val titles = listOf(stringResource(R.string.settings_display_sample_title_1), stringResource(R.string.settings_display_sample_title_2))
    val channels = listOf(stringResource(R.string.settings_display_sample_channel_1), stringResource(R.string.settings_display_sample_channel_2))
    val meta = stringResource(R.string.settings_display_sample_meta)
    return remember(titles, channels, meta) {
        titles.indices.map { i ->
            VideoItem(
                id = "preview$i",
                title = titles[i],
                channelName = channels[i],
                channelId = null,
                channelAvatar = null,
                thumbnails = emptyList(),
                durationText = if (i == 0) "12:34" else "4:05",
                durationSeconds = null,
                viewCountText = meta,
                publishedText = null,
            )
        }
    }
}
