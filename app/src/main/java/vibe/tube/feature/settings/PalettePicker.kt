package vibe.tube.feature.settings

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import vibe.tube.R
import vibe.tube.data.settings.AppSettings
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.theme.AppPalette
import vibe.tube.ui.theme.Spacing
import vibe.tube.ui.theme.paletteScheme

/** Horizontal row of colour-scheme tiles: wallpaper colours (Android 12+) followed by the built-in palettes. */
@Composable
fun PalettePicker(settings: AppSettings, onPick: (dynamic: Boolean, palette: AppPalette) -> Unit, modifier: Modifier = Modifier) {
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Column(modifier.padding(vertical = Spacing.sm)) {
        Text(
            stringResource(R.string.settings_palette),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
        Text(
            stringResource(R.string.settings_palette_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        )
        Row(
            Modifier.selectableGroup().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (dynamicAvailable) {
                // The wallpaper scheme itself, not the active theme: otherwise the tile would repaint in whichever palette is selected.
                val context = androidx.compose.ui.platform.LocalContext.current
                val isDarkNow = androidx.compose.foundation.isSystemInDarkTheme()
                val colors = remember(isDarkNow) {
                    if (isDarkNow) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)
                }
                PaletteTile(
                    label = stringResource(R.string.palette_dynamic),
                    selected = settings.dynamicColor,
                    container = colors.surfaceContainerHighest,
                    primary = colors.primary, secondary = colors.secondary, tertiary = colors.tertiary,
                    dynamicBadge = true,
                ) { onPick(true, settings.palette) }
            }
            AppPalette.entries.forEach { palette ->
                val scheme = remember(palette) { paletteScheme(palette, dark = false) }
                val dark = remember(palette) { paletteScheme(palette, dark = true) }
                val isDark = androidx.compose.foundation.isSystemInDarkTheme()
                val s = if (isDark) dark else scheme
                PaletteTile(
                    label = stringResource(paletteName(palette)),
                    selected = !(dynamicAvailable && settings.dynamicColor) && settings.palette == palette,
                    container = s.primaryContainer,
                    primary = s.primary, secondary = s.secondary, tertiary = s.tertiary,
                ) { onPick(false, palette) }
            }
        }
    }
}

private fun paletteName(p: AppPalette): Int = when (p) {
    AppPalette.Vibe -> R.string.palette_vibe
    AppPalette.Indigo -> R.string.palette_indigo
    AppPalette.Coral -> R.string.palette_coral
    AppPalette.Ocean -> R.string.palette_ocean
    AppPalette.Rose -> R.string.palette_rose
    AppPalette.Violet -> R.string.palette_violet
    AppPalette.Classic -> R.string.palette_classic
}

@Composable
private fun PaletteTile(
    label: String,
    selected: Boolean,
    container: Color,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    dynamicBadge: Boolean = false,
    onClick: () -> Unit,
) {
    // Circle at rest, squircle when chosen; the colour disc is split into three sectors (primary / secondary / tertiary).
    val corner by animateDpAsState(
        if (selected) 22.dp else 36.dp,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "tileCorner",
    )
    val ring by animateDpAsState(if (selected) 3.dp else 0.dp, spring(stiffness = Spring.StiffnessMedium), label = "tileRing")
    val badge by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "tileBadge")
    val ringColor = MaterialTheme.colorScheme.primary
    val gap = MaterialTheme.colorScheme.surface
    Column(
        Modifier.selectable(
            selected = selected,
            role = Role.RadioButton,
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(68.dp)
                    .graphicsLayer { scaleX = 1f - 0.04f * (1f - badge); scaleY = scaleX }
                    .drawBehind {
                        val r = androidx.compose.ui.geometry.CornerRadius(corner.toPx())
                        val outline = ring.toPx()
                        if (outline > 0.1f) {
                            drawRoundRect(ringColor, cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner.toPx() + 4.dp.toPx()), topLeft = Offset(-4.dp.toPx(), -4.dp.toPx()), size = Size(size.width + 8.dp.toPx(), size.height + 8.dp.toPx()), style = Stroke(outline))
                        }
                        drawRoundRect(container, cornerRadius = r)
                    }
                    .clip(RoundedCornerShape(corner)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val g = 2.dp.toPx()
                    drawRect(primary, topLeft = Offset.Zero, size = Size(w / 2f - g / 2f, h))
                    drawRect(secondary, topLeft = Offset(w / 2f + g / 2f, 0f), size = Size(w / 2f - g / 2f, h / 2f - g / 2f))
                    drawRect(tertiary, topLeft = Offset(w / 2f + g / 2f, h / 2f + g / 2f), size = Size(w / 2f - g / 2f, h / 2f - g / 2f))
                }
                if (dynamicBadge || selected) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .graphicsLayer { val k = if (dynamicBadge && !selected) 1f else badge; scaleX = k; scaleY = k; alpha = k }
                            .clip(CircleShape)
                            .background(gap),
                        contentAlignment = Alignment.Center,
                    ) {
                        VIconImage(
                            if (selected) VIcon.Check else VIcon.Palette,
                            null,
                            tint = if (selected) ringColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

