package vibe.tube.feature.settings

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
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
                val colors = MaterialTheme.colorScheme
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
    val border by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "tileBorder")
    Column(
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = container,
            border = BorderStroke(if (selected) 3.dp else 1.dp, border),
            modifier = Modifier.size(72.dp),
        ) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(primary))
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(18.dp).clip(CircleShape).background(secondary))
                Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(14.dp).clip(CircleShape).background(tertiary))
                if (selected) {
                    VIconImage(VIcon.Check, null, tint = contrastOn(primary), modifier = Modifier.size(22.dp))
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

private fun contrastOn(c: Color): Color = if (c.luminance() > 0.5f) Color.Black else Color.White

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
