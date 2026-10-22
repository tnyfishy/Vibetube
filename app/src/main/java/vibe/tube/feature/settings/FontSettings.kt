package vibe.tube.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import vibe.tube.R
import vibe.tube.data.settings.AppFont
import vibe.tube.data.settings.AppSettings
import vibe.tube.ui.theme.FontStyleSettings
import vibe.tube.ui.theme.GoogleSansFlex
import vibe.tube.ui.theme.Spacing
import vibe.tube.ui.theme.buildTypography
import kotlin.math.roundToInt

private const val WEIGHT_MIN = -150f
private const val WEIGHT_MAX = 300f

@Composable
fun fontLabel(font: AppFont): String = font.label ?: stringResource(R.string.settings_font_system)

/** Font picker with a live preview and weight (all fonts) plus width / roundness (Google Sans Flex). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontSheet(settings: AppSettings, onChange: (AppSettings.() -> AppSettings) -> Unit, onDismiss: () -> Unit) {
    var font by remember { mutableStateOf(settings.font) }
    var weight by remember { mutableFloatStateOf(settings.fontWeightOffset.toFloat()) }
    var width by remember { mutableFloatStateOf(settings.fontWidth.toFloat()) }
    var roundness by remember { mutableFloatStateOf(settings.fontRoundness.toFloat()) }
    // The preview follows committed values only (on release), so dragging a slider never rebuilds typography per frame.
    var applied by remember { mutableStateOf(FontStyleSettings(settings.font, settings.fontWeightOffset, settings.fontWidth, settings.fontRoundness)) }
    val preview = remember(applied) { buildTypography(applied) }

    fun commitSettings() = onChange {
        copy(font = font, fontWeightOffset = weight.roundToInt(), fontWidth = width.roundToInt(), fontRoundness = roundness.roundToInt())
    }
    // Only the sheet's own preview changes while it is open; the app-wide theme (which recomposes every screen) is
    // updated once, when the sheet is closed.
    fun commit() {
        applied = FontStyleSettings(font, weight.roundToInt(), width.roundToInt(), roundness.roundToInt())
    }
    fun finish() {
        commitSettings()
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = ::finish, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.settings_font), style = MaterialTheme.typography.titleLarge)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                // Cross-fade between previews so a change glides instead of snapping.
                AnimatedContent(
                    targetState = preview,
                    transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) using SizeTransform(clip = false) { _, _ -> tween(240) } },
                    label = "fontPreview",
                ) { typography ->
                    Column(Modifier.padding(Spacing.lg).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(R.string.settings_font_preview_title), style = typography.headlineSmall)
                        Text(stringResource(R.string.settings_font_preview_label), style = typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Column(Modifier.selectableGroup()) {
                AppFont.entries.forEach { option ->
                    val style = remember(option, applied.weightOffset) { buildTypography(FontStyleSettings(option, applied.weightOffset, applied.width, applied.roundness)).titleMedium }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(option == font, role = Role.RadioButton) { font = option; commit() }
                            .padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == font, onClick = null)
                        Text(fontLabel(option), style = style, modifier = Modifier.padding(start = Spacing.md))
                    }
                }
            }

            SliderRow(
                label = stringResource(R.string.settings_font_weight),
                valueText = weightText(weight.roundToInt()),
                value = weight,
                range = WEIGHT_MIN..WEIGHT_MAX,
                onChange = { weight = (it / 10f).roundToInt() * 10f },
                onFinished = ::commit,
            )
            if (font == AppFont.GoogleSansFlex) {
                SliderRow(
                    label = stringResource(R.string.settings_font_width),
                    valueText = width.roundToInt().toString(),
                    value = width,
                    range = GoogleSansFlex.WIDTH_MIN..GoogleSansFlex.WIDTH_MAX,
                    onChange = { width = it.roundToInt().toFloat() },
                    onFinished = ::commit,
                )
                SliderRow(
                    label = stringResource(R.string.settings_font_roundness),
                    valueText = roundness.roundToInt().toString(),
                    value = roundness,
                    range = 0f..100f,
                    onChange = { roundness = it.roundToInt().toFloat() },
                    onFinished = ::commit,
                )
            }
            OutlinedButton(
                shapes = ButtonDefaults.shapes(),
                onClick = {
                    font = AppFont.System; weight = 0f; width = GoogleSansFlex.WIDTH_DEFAULT; roundness = GoogleSansFlex.ROUNDNESS
                    commit()
                },
                modifier = Modifier.padding(bottom = Spacing.lg),
            ) { Text(stringResource(R.string.settings_font_reset)) }
        }
    }
}

@Composable
private fun weightText(offset: Int): String = when {
    offset == 0 -> stringResource(R.string.settings_font_weight_normal)
    offset > 0 -> "+$offset"
    else -> offset.toString()
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, onValueChangeFinished = onFinished)
    }
}
