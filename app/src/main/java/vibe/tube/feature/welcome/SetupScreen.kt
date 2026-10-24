package vibe.tube.feature.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vibe.tube.R
import vibe.tube.data.settings.AppSettings
import vibe.tube.data.settings.ThemeMode
import vibe.tube.feature.settings.PalettePicker
import vibe.tube.feature.settings.SettingsViewModel
import vibe.tube.ui.theme.Spacing
import java.util.Locale

/**
 * One page for the choices people usually want to make first: theme, colour, language and whether Shorts shows. Every
 * choice is applied the moment it is made (this page re-colours itself), and the same page is reachable from Settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(onDone: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = Spacing.lg),
        ) {
            Text(
                stringResource(R.string.setup_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
            Text(
                stringResource(R.string.setup_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.lg),
            )

            SetupHeading(R.string.settings_theme)
            FlowRow(
                Modifier.padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.update { it.copy(themeMode = mode) } },
                        label = {
                            Text(
                                stringResource(
                                    when (mode) {
                                        ThemeMode.System -> R.string.theme_system
                                        ThemeMode.Light -> R.string.theme_light
                                        ThemeMode.Dark -> R.string.theme_dark
                                        ThemeMode.Amoled -> R.string.theme_amoled
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            SetupHeading(R.string.setup_color)
            PalettePicker(settings, onPick = { dyn, pal -> viewModel.update { it.copy(dynamicColor = dyn, palette = pal) } })

            Spacer(Modifier.height(Spacing.sm))
            SetupHeading(R.string.settings_ui_language)
            FlowRow(
                Modifier.padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                listOf(AppSettings.SYSTEM, "en", "vi").forEach { code ->
                    FilterChip(
                        selected = settings.uiLanguage == code,
                        onClick = { viewModel.update { it.copy(uiLanguage = code) } },
                        label = {
                            Text(
                                if (code == AppSettings.SYSTEM) stringResource(R.string.settings_system_default)
                                else Locale.forLanguageTag(code).let { l -> l.getDisplayLanguage(l).replaceFirstChar { c -> c.titlecase(l) } },
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(!settings.hideShorts, role = Role.Switch) { on -> viewModel.update { it.copy(hideShorts = !on) } }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.setup_shorts), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.setup_shorts_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = !settings.hideShorts, onCheckedChange = null)
            }
        }
        Button(
            shapes = ButtonDefaults.shapes(),
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) { Text(stringResource(R.string.setup_done)) }
    }
}

@Composable
private fun SetupHeading(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    )
}
