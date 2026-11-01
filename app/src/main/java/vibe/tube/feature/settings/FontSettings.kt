package vibe.tube.feature.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
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
import vibe.tube.ui.theme.previewStyle
import vibe.tube.ui.components.ExpandingSection
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private const val OFFSET_FLOOR = -150
private const val OFFSET_CEIL = 300
                                                                                                                  
private const val LIGHTEST_ROLE = 400

internal fun weightOffsetRange(font: AppFont): ClosedFloatingPointRange<Float> {
    if (font.isSystem) return OFFSET_FLOOR.toFloat()..OFFSET_CEIL.toFloat()
    val low = (font.minWeight - LIGHTEST_ROLE).coerceIn(OFFSET_FLOOR, 0)
    val high = (font.maxWeight - LIGHTEST_ROLE).coerceIn(0, OFFSET_CEIL)
    return low.toFloat()..high.toFloat()
}

@Composable
fun fontLabel(font: AppFont): String = font.label ?: stringResource(R.string.settings_font_system)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontSheet(settings: AppSettings, onChange: (AppSettings.() -> AppSettings) -> Unit, onDismiss: () -> Unit) {
    var font by remember { mutableStateOf(settings.font) }
    var weight by remember { mutableFloatStateOf(settings.fontWeightOffset.toFloat()) }
    var width by remember { mutableFloatStateOf(settings.fontWidth.toFloat()) }
    var roundness by remember { mutableFloatStateOf(settings.fontRoundness.toFloat()) }
    var slant by remember { mutableFloatStateOf(settings.fontSlant.toFloat()) }
    
    val live = FontStyleSettings(font, weight.roundToInt(), width.roundToInt(), roundness.roundToInt(), slant.roundToInt())

    val scope = rememberCoroutineScope()
    val resolver = LocalFontFamilyResolver.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scroll = rememberScrollState()
    val controls = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    var fontChanged by remember { mutableStateOf(false) }

    fun finish() {
        scope.launch {
            
            val t = buildTypography(live)
            listOf(t.displayLarge, t.headlineSmall, t.titleMedium, t.bodyLarge, t.bodySmall, t.labelLarge, t.labelSmall)
                .mapNotNull { it.fontFamily }
                .distinct()
                .forEach { runCatching { resolver.preload(it) } }
            onChange {
                copy(font = font, fontWeightOffset = weight.roundToInt(), fontWidth = width.roundToInt(), fontRoundness = roundness.roundToInt(), fontSlant = slant.roundToInt())
            }
            onDismiss()
        }
    }

    LaunchedEffect(sheetState) {
        androidx.compose.runtime.snapshotFlow { sheetState.currentValue == androidx.compose.material3.SheetValue.Expanded }.first { it }
        controls.bringIntoView()
    }

    ModalBottomSheet(onDismissRequest = ::finish, sheetState = sheetState) {
        Column(
            Modifier.verticalScroll(scroll).navigationBarsPadding().padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.settings_font), style = MaterialTheme.typography.titleLarge)
            FontPreview(live)
            androidx.compose.foundation.layout.FlowRow(
                Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                AppFont.entries.forEach { option ->
                    androidx.compose.material3.FilterChip(
                        selected = option == font,
                        onClick = {
                            if (option != font) {
                                
                                fontChanged = option.axes.any { font.axis(it.tag) == null }
                                font = option
                            }
                        },
                        label = { Text(fontLabel(option)) },
                        modifier = Modifier.semantics { role = Role.RadioButton },
                    )
                }
            }
            
            Column(Modifier.bringIntoViewRequester(controls)) {
                val bringIn: () -> Unit = {
                    if (fontChanged) {
                        fontChanged = false
                        scope.launch { controls.bringIntoView() }
                    }
                }
                val weightRange = weightOffsetRange(font)
                SliderRow(
                    label = stringResource(R.string.settings_font_weight),
                    valueText = weightText(weight.coerceIn(weightRange).roundToInt()),
                    value = weight.coerceIn(weightRange),
                    range = weightRange,
                    onChange = { weight = (it / 10f).roundToInt() * 10f },
                )
                val wdth = font.axis(AppFont.WDTH).orLast()
                ExpandingSection(visible = font.axis(AppFont.WDTH) != null, onShown = bringIn) {
                    val axis = wdth ?: return@ExpandingSection
                    SliderRow(
                        label = stringResource(R.string.settings_font_width),
                        valueText = width.coerceIn(axis.range).roundToInt().toString(),
                        value = width.coerceIn(axis.range),
                        range = axis.range,
                        onChange = { width = it.roundToInt().toFloat() },
                    )
                }
                val rond = font.axis(AppFont.ROND).orLast()
                ExpandingSection(visible = font.axis(AppFont.ROND) != null, onShown = bringIn) {
                    val axis = rond ?: return@ExpandingSection
                    SliderRow(
                        label = stringResource(R.string.settings_font_roundness),
                        valueText = roundness.coerceIn(axis.range).roundToInt().toString(),
                        value = roundness.coerceIn(axis.range),
                        range = axis.range,
                        onChange = { roundness = it.roundToInt().toFloat() },
                    )
                }
                val slnt = font.axis(AppFont.SLNT).orLast()
                ExpandingSection(visible = font.axis(AppFont.SLNT) != null, onShown = bringIn) {
                    val axis = slnt ?: return@ExpandingSection
                    val shown = slant.coerceIn(axis.range).roundToInt()
                    SliderRow(
                        label = stringResource(R.string.settings_font_slant),
                        valueText = if (shown == 0) stringResource(R.string.settings_font_slant_upright) else "$shown°",
                        value = slant.coerceIn(axis.range),
                        range = axis.range,
                        onChange = { slant = it.roundToInt().toFloat() },
                    )
                }
                ExpandingSection(visible = font.axis(AppFont.OPSZ) != null) {
                    Text(
                        stringResource(R.string.settings_font_opsz_auto),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
            OutlinedButton(
                shapes = ButtonDefaults.shapes(),
                onClick = {
                    fontChanged = AppFont.GoogleSansFlex.axes.any { font.axis(it.tag) == null }
                    font = AppFont.GoogleSansFlex; weight = 0f; width = GoogleSansFlex.WIDTH_DEFAULT; roundness = GoogleSansFlex.ROUNDNESS; slant = 0f
                },
                modifier = Modifier.padding(bottom = Spacing.lg),
            ) { Text(stringResource(R.string.settings_font_reset)) }
        }
    }
}

@Composable
private fun FontPreview(live: FontStyleSettings) {
    val reduced = vibe.tube.ui.theme.LocalReducedMotion.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(
            Modifier
                .then(if (reduced) Modifier else Modifier.animateContentSize(vibe.tube.ui.components.SectionMotion.size))
                .padding(Spacing.lg)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(stringResource(R.string.settings_font_preview_title), style = previewStyle(live, 24f, 32f, 400))
            Text(
                stringResource(R.string.settings_font_preview_body),
                style = previewStyle(live, 14f, 20f, 400),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.settings_font_preview_label), style = previewStyle(live, 14f, 20f, 500), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun <T : Any> T?.orLast(): T? {
    val last = remember { arrayOfNulls<Any>(1) }
    if (this != null) last[0] = this
    @Suppress("UNCHECKED_CAST")
    return this ?: last[0] as T?
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
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
