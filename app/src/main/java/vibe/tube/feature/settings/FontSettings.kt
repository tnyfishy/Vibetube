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
import vibe.tube.ui.theme.fontFamilyFor
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalFontFamilyResolver
import kotlinx.coroutines.launch
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

    val scope = rememberCoroutineScope()
    val resolver = LocalFontFamilyResolver.current
    // Loading a typeface is blocking work; do it on the resolver's background loader first so the UI thread only ever
    // swaps in fonts that are already in memory.
    suspend fun warm(style: FontStyleSettings) {
        val t = buildTypography(style)
        listOf(t.displayLarge, t.headlineSmall, t.titleMedium, t.bodyLarge, t.bodySmall, t.labelLarge, t.labelSmall)
            .mapNotNull { it.fontFamily }
            .distinct()
            .forEach { runCatching { resolver.preload(it) } }
    }

    fun commitSettings() = onChange {
        copy(font = font, fontWeightOffset = weight.roundToInt(), fontWidth = width.roundToInt(), fontRoundness = roundness.roundToInt())
    }
    // Only the sheet's own preview changes while it is open; the app-wide theme (which recomposes every screen) is
    // updated once, when the sheet is closed.
    var pending by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun commit() {
        val next = FontStyleSettings(font, weight.roundToInt(), width.roundToInt(), roundness.roundToInt())
        pending?.cancel()
        pending = scope.launch {
            warm(next)
            applied = next
        }
    }
    fun finish() {
        scope.launch {
            // The app-wide typography is warmed before it is applied, so closing the sheet never stalls a frame.
            warm(FontStyleSettings(font, weight.roundToInt(), width.roundToInt(), roundness.roundToInt()))
            commitSettings()
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = ::finish) {
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

            // Rows switch to their own typeface one after another once the sheet has settled: loading eleven font files
            // while the sheet is still sliding in is what made opening it stutter.
            var ready by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                delay(380)
                for (i in 1..AppFont.entries.size) {
                    ready = i
                    delay(32)
                }
            }
            Column(Modifier.selectableGroup()) {
                AppFont.entries.forEachIndexed { index, option ->
                    val style = if (index < ready) {
                        remember(option, applied.weightOffset, applied.width) {
                            TextStyle(
                                fontFamily = fontFamilyFor(FontStyleSettings(option, applied.weightOffset, applied.width, applied.roundness), 16f),
                                fontWeight = FontWeight(if (option.isSystem) 400 + applied.weightOffset else 400),
                                fontSize = 16.sp,
                            )
                        }
                    } else {
                        MaterialTheme.typography.titleMedium
                    }
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
            val showWidth = font.isBundled && font.maxWidth > font.minWidth
            val grow = androidx.compose.animation.fadeIn(tween(220)) + androidx.compose.animation.expandVertically(tween(280))
            val shrink = androidx.compose.animation.fadeOut(tween(120)) + androidx.compose.animation.shrinkVertically(tween(220))
            androidx.compose.animation.AnimatedVisibility(visible = showWidth, enter = grow, exit = shrink) {
                val minW = font.minWidth
                val maxW = font.maxWidth.coerceAtLeast(minW + 1f)
                SliderRow(
                    label = stringResource(R.string.settings_font_width),
                    valueText = width.coerceIn(minW, maxW).roundToInt().toString(),
                    value = width.coerceIn(minW, maxW),
                    range = minW..maxW,
                    onChange = { width = it.roundToInt().toFloat() },
                    onFinished = ::commit,
                )
            }
            androidx.compose.animation.AnimatedVisibility(visible = font.hasRoundness, enter = grow, exit = shrink) {
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
