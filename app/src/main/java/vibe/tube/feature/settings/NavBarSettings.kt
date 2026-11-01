package vibe.tube.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import vibe.tube.R
import vibe.tube.data.settings.AppSettings
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.NavBar
import vibe.tube.ui.components.NavBarAppearance
import vibe.tube.ui.components.NavBarEntry
import vibe.tube.ui.components.glassSource
import vibe.tube.ui.components.glassSupported
import vibe.tube.ui.components.rememberGlassBackdrop
import vibe.tube.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavBarSheet(settings: AppSettings, onChange: (AppSettings.() -> AppSettings) -> Unit, onDismiss: () -> Unit) {
    var hideLabels by remember { mutableStateOf(settings.navHideLabels) }
    var height by remember { mutableIntStateOf(settings.navBarHeight) }
    var icon by remember { mutableIntStateOf(settings.navIconSize) }
    var floating by remember { mutableStateOf(settings.navFloating) }
    var blur by remember { mutableStateOf(settings.navBlur) }
    var selected by remember { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun finish() {
        onChange { copy(navHideLabels = hideLabels, navBarHeight = height, navIconSize = icon, navFloating = floating, navBlur = blur) }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = ::finish, sheetState = sheetState) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.settings_navbar), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.lg))

            NavBarPreview(
                NavBarAppearance(showLabels = !hideLabels, height = height.dp, iconSize = icon.dp, floating = floating, blur = blur),
                selected,
                { selected = it },
                Modifier.padding(horizontal = Spacing.lg),
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
                SegmentedButton(selected = !floating, onClick = { floating = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(R.string.settings_navbar_docked))
                }
                SegmentedButton(selected = floating, onClick = { floating = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text(stringResource(R.string.settings_navbar_floating))
                }
            }
            ToggleLine(stringResource(R.string.settings_navbar_hide_labels), null, hideLabels) { hideLabels = it }
            ToggleLine(
                stringResource(R.string.settings_navbar_blur),
                stringResource(if (glassSupported) R.string.settings_navbar_blur_desc else R.string.settings_navbar_blur_unsupported),
                blur,
            ) { blur = it }
            ValueSlider(stringResource(R.string.settings_navbar_height), height, AppSettings.NAV_HEIGHT_RANGE) { height = it }
            ValueSlider(stringResource(R.string.settings_navbar_icon), icon, AppSettings.NAV_ICON_RANGE) { icon = it }
            OutlinedButton(
                shapes = ButtonDefaults.shapes(),
                enabled = !hideLabels || height != AppSettings.NAV_HEIGHT_DEFAULT || icon != AppSettings.NAV_ICON_DEFAULT || floating || blur,
                onClick = {
                    hideLabels = true
                    height = AppSettings.NAV_HEIGHT_DEFAULT
                    icon = AppSettings.NAV_ICON_DEFAULT
                    floating = false
                    blur = false
                },
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
            ) { Text(stringResource(R.string.settings_display_reset)) }
        }
    }
}

/** Live bar over a busy fake feed, so blur and the floating capsule read the same as in the app. */
@Composable
private fun NavBarPreview(appearance: NavBarAppearance, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val entries = listOf(
        NavBarEntry(stringResource(R.string.nav_home), VIcon.Home, VIcon.HomeFilled),
        NavBarEntry(stringResource(R.string.nav_shorts), VIcon.Shorts, VIcon.ShortsFilled, vibe.tube.ui.components.SHORTS_ICON_SCALE),
        NavBarEntry(stringResource(R.string.nav_subscriptions), VIcon.Subscriptions, VIcon.SubscriptionsFilled),
        NavBarEntry(stringResource(R.string.nav_library), VIcon.VideoLibrary, VIcon.VideoLibraryFilled),
        NavBarEntry(stringResource(R.string.nav_account), VIcon.AccountCircle, VIcon.AccountCircleFilled),
    )
    val backdrop = if (appearance.needsBackdrop) rememberGlassBackdrop() else null
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface),
    ) {
        Column(
            Modifier.fillMaxSize().glassSource(backdrop).background(colors.surface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(3) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(10.dp))
                            .background(listOf(colors.primary, colors.tertiary, colors.secondary)[row]),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth(0.8f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(colors.onSurface.copy(alpha = 0.5f)))
                        Box(Modifier.fillMaxWidth(0.5f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.onSurfaceVariant.copy(alpha = 0.4f)))
                    }
                }
            }
        }
        NavBar(entries, selected, onSelect, appearance, bottomInset = 0.dp, modifier = Modifier.align(Alignment.BottomCenter), backdrop = backdrop)
    }
}

@Composable
private fun ToggleLine(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(checked, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ValueSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = Spacing.lg)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text("$value dp", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(kotlin.math.round(it).toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / 2 - 1,
        )
    }
}
