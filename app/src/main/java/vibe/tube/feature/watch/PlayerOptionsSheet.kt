package vibe.tube.feature.watch

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vibe.tube.R
import vibe.tube.player.PlayerController
import vibe.tube.player.PlayerUiState
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.theme.Spacing
import kotlin.math.roundToInt

private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val sleepMinutes = listOf(5, 10, 15, 30, 45, 60)

private enum class OptionsPage { Main, Speed, Quality, Captions, Audio, Sleep }

/** YouTube-style settings sheet: one row per setting on the first page, each opening its own page. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlayerOptionsSheet(state: PlayerUiState, controller: PlayerController, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(OptionsPage.Main) }
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    // Landscape screens are short: the sheet opens straight at its content height (no half-open stop), is narrower and
    // never grows past ~65% of the height, so it no longer shoots up the screen.
    val maxContentHeight = (config.screenHeightDp * 0.65f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = if (landscape) 560.dp else 640.dp,
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState != OptionsPage.Main
                (slideInHorizontally(tween(170)) { if (forward) it / 8 else -it / 8 } + fadeIn(tween(150)))
                    .togetherWith(slideOutHorizontally(tween(130)) { if (forward) -it / 8 else it / 8 } + fadeOut(tween(90)))
                    .using(SizeTransform(clip = false) { _, _ -> tween(170) })
            },
            label = "optionsPage",
        ) { current ->
            Column(
                Modifier
                    .navigationBarsPadding()
                    .then(if (landscape) Modifier.heightIn(max = maxContentHeight) else Modifier)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = Spacing.md),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    if (current != OptionsPage.Main) {
                        TooltipIconButton(VIcon.ArrowBack, stringResource(R.string.action_back), { page = OptionsPage.Main })
                    } else {
                        Spacer(Modifier.size(Spacing.md))
                    }
                    Text(
                        when (current) {
                            OptionsPage.Main -> stringResource(R.string.player_options)
                            OptionsPage.Speed -> stringResource(R.string.player_speed)
                            OptionsPage.Quality -> stringResource(R.string.player_quality)
                            OptionsPage.Captions -> stringResource(R.string.player_captions)
                            OptionsPage.Audio -> stringResource(R.string.player_audio_tracks)
                            OptionsPage.Sleep -> stringResource(R.string.player_sleep_timer)
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                when (current) {
                    OptionsPage.Main -> MainPage(state, onOpen = { page = it })
                    OptionsPage.Speed -> SpeedPage(state, controller)
                    OptionsPage.Quality -> QualityPage(state, controller, onDone = { page = OptionsPage.Main })
                    OptionsPage.Captions -> CaptionsPage(state, controller, onDone = { page = OptionsPage.Main })
                    OptionsPage.Audio -> AudioPage(state, controller, onDone = { page = OptionsPage.Main })
                    OptionsPage.Sleep -> SleepPage(state, controller, onDone = { page = OptionsPage.Main })
                }
            }
        }
    }
}

@Composable
private fun MainPage(state: PlayerUiState, onOpen: (OptionsPage) -> Unit) {
    val speedText = if (state.speed == 1f && state.pitch == 1f) stringResource(R.string.player_speed_normal) else "${fmt(state.speed)}x"
    OptionRow(VIcon.Speed, stringResource(R.string.player_speed), speedText) { onOpen(OptionsPage.Speed) }
    if (state.qualities.isNotEmpty()) {
        val q = state.selectedHeight?.let { "${it}p" } ?: if (state.activeHeight != null) stringResource(R.string.quality_auto_active, state.activeHeight) else stringResource(R.string.quality_auto)
        OptionRow(VIcon.HighQuality, stringResource(R.string.player_quality), q) { onOpen(OptionsPage.Quality) }
    }
    if (state.captions.isNotEmpty()) {
        val c = state.captions.firstOrNull { it.languageCode == state.selectedCaption }?.name ?: stringResource(R.string.player_captions_off)
        OptionRow(VIcon.ClosedCaption, stringResource(R.string.player_captions), c) { onOpen(OptionsPage.Captions) }
    }
    if (state.audioTracks.size > 1) {
        val a = state.audioTracks.firstOrNull { it.id == state.selectedAudioTrack || (state.selectedAudioTrack == null && it.isDefault) }?.name.orEmpty()
        OptionRow(VIcon.GraphicEq, stringResource(R.string.player_audio_tracks), a) { onOpen(OptionsPage.Audio) }
    }
    val sleep = when {
        state.sleepAtEnd -> stringResource(R.string.player_sleep_end)
        state.sleepUntilMs != null -> stringResource(R.string.player_sleep_minutes, (((state.sleepUntilMs - System.currentTimeMillis()) / 60_000f).coerceAtLeast(0f)).roundToInt().coerceAtLeast(1))
        else -> stringResource(R.string.player_sleep_off)
    }
    OptionRow(VIcon.Bedtime, stringResource(R.string.player_sleep_timer), sleep) { onOpen(OptionsPage.Sleep) }
    state.clientName?.let {
        Text(
            stringResource(R.string.player_source_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SpeedPage(state: PlayerUiState, controller: PlayerController) {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    if (config.screenWidthDp > config.screenHeightDp) {
        // Landscape: presets on the left, sliders on the right, so the page is half as tall.
        Row(Modifier.padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SpeedChips(state, controller)
                SpeedExtras(state, controller)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SpeedSliders(state, controller)
            }
        }
        return
    }
    Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SpeedChips(state, controller)
        SpeedSliders(state, controller)
        SpeedExtras(state, controller)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SpeedChips(state: PlayerUiState, controller: PlayerController) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        speeds.forEach { value ->
            FilterChip(
                selected = state.speed == value,
                onClick = { controller.setSpeed(value) },
                label = { Text(if (value == 1f) stringResource(R.string.player_speed_normal) else "${fmt(value)}x") },
            )
        }
    }
}

@Composable
private fun SpeedSliders(state: PlayerUiState, controller: PlayerController) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        TuningSlider(stringResource(R.string.player_speed), state.speed, 0.25f..3f, controller::setSpeed)
        Row(
            Modifier.fillMaxWidth().clickable { controller.setLinkPitch(!state.linkPitch) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.player_link_pitch), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = state.linkPitch, onCheckedChange = { controller.setLinkPitch(it) })
        }
        if (!state.linkPitch) {
            TuningSlider(stringResource(R.string.player_pitch), state.pitch, 0.5f..2f, controller::setPitch)
        }
    }
}

@Composable
private fun SpeedExtras(state: PlayerUiState, controller: PlayerController) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FilterChip(
            selected = state.tuningRemembered,
            onClick = { controller.setRememberTuning(!state.tuningRemembered) },
            label = { Text(stringResource(R.string.player_tuning_remember)) },
        )
        OutlinedButton(onClick = { controller.setLinkPitch(false); controller.setSpeed(1f); controller.setPitch(1f) }) {
            Text(stringResource(R.string.player_tuning_reset))
        }
    }
}

@Composable
private fun QualityPage(state: PlayerUiState, controller: PlayerController, onDone: () -> Unit) {
    ChoiceRow(
        if (state.selectedHeight == null && state.activeHeight != null) stringResource(R.string.quality_auto_active, state.activeHeight) else stringResource(R.string.quality_auto),
        state.selectedHeight == null,
    ) { controller.selectQuality(null); onDone() }
    state.qualities.forEach { h -> ChoiceRow("${h}p", state.selectedHeight == h) { controller.selectQuality(h); onDone() } }
}

@Composable
private fun CaptionsPage(state: PlayerUiState, controller: PlayerController, onDone: () -> Unit) {
    ChoiceRow(stringResource(R.string.player_captions_off), state.selectedCaption == null) { controller.selectCaption(null); onDone() }
    state.captions.forEach { track ->
        ChoiceRow(
            if (track.isAutoGenerated) stringResource(R.string.player_caption_auto, track.name) else track.name,
            state.selectedCaption == track.languageCode,
        ) { controller.selectCaption(track.languageCode); onDone() }
    }
}

@Composable
private fun AudioPage(state: PlayerUiState, controller: PlayerController, onDone: () -> Unit) {
    state.audioTracks.forEach { track ->
        ChoiceRow(track.name, state.selectedAudioTrack == track.id || (state.selectedAudioTrack == null && track.isDefault)) {
            controller.selectAudioTrack(track.id); onDone()
        }
    }
}

@Composable
private fun SleepPage(state: PlayerUiState, controller: PlayerController, onDone: () -> Unit) {
    ChoiceRow(stringResource(R.string.player_sleep_off), state.sleepUntilMs == null && !state.sleepAtEnd) { controller.setSleepTimer(null); onDone() }
    sleepMinutes.forEach { m ->
        ChoiceRow(stringResource(R.string.player_sleep_minutes, m), false) { controller.setSleepTimer(m); onDone() }
    }
    ChoiceRow(stringResource(R.string.player_sleep_end), state.sleepAtEnd) { controller.setSleepAtEnd(true); onDone() }
}

@Composable
private fun OptionRow(icon: Int, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        VIconImage(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        VIconImage(VIcon.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

@Composable
private fun TuningSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(String.format(java.util.Locale.US, "%.2fx", value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = { onChange((it * 100f).roundToInt() / 100f) },
            valueRange = range,
        )
    }
}
