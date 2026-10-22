package vibe.tube.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Row
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vibe.tube.BuildConfig
import vibe.tube.R
import vibe.tube.data.settings.AppSettings
import vibe.tube.data.settings.QualityCap
import vibe.tube.data.settings.ThemeMode
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.theme.Spacing
import java.util.Locale

data class Choice<T>(val value: T, val label: String)

@Composable
fun <T> ChoiceDialog(
    title: String,
    choices: List<Choice<T>>,
    selected: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.selectableGroup()) {
                items(choices.size) { index ->
                    val choice = choices[index]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(choice.value == selected, role = Role.RadioButton) { onSelect(choice.value) }
                            .padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice.value == selected, onClick = null)
                        Text(choice.label, Modifier.padding(start = Spacing.md))
                    }
                }
            }
        },
        confirmButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

private val contentLanguages = listOf("en", "vi", "ja", "ko", "zh", "es", "fr", "de", "pt", "ru", "id", "th", "hi", "ar", "it", "tr")
private val contentRegions = listOf("US", "VN", "GB", "JP", "KR", "DE", "FR", "ES", "BR", "IN", "ID", "TH", "RU", "AU", "CA", "SG", "TW", "PH", "MY")

private enum class Dialog { Theme, Font, RefreshRate, HiddenChannels, DownloadDir, UiLanguage, ContentLanguage, Region, WifiQuality, MobileQuality, ClearCache, ClearHistory, ClearSearch }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    onDebug: (() -> Unit)?,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val systemLabel = stringResource(R.string.settings_system_default)
    val displayLocale = Locale.getDefault()
    val context = LocalContext.current
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.update { it.copy(downloadDir = uri.toString()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { TooltipIconButton(VIcon.ArrowBack, stringResource(R.string.action_back), onBack) },
            )
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner)) {
            section(R.string.settings_section_appearance)
            item {
                SettingRow(
                    VIcon.Palette, R.string.settings_theme,
                    themeLabel(settings.themeMode),
                ) { dialog = Dialog.Theme }
            }
            item {
                SwitchRow(
                    VIcon.LightMode, R.string.settings_dynamic_color, R.string.settings_dynamic_color_desc,
                    settings.dynamicColor,
                ) { v -> viewModel.update { it.copy(dynamicColor = v) } }
            }
            item {
                SettingRow(
                    VIcon.Edit, R.string.settings_font,
                    fontLabel(settings.font),
                ) { dialog = Dialog.Font }
            }
            item {
                SettingRow(
                    VIcon.Tune, R.string.settings_refresh_rate,
                    refreshLabel(settings.refreshRate),
                ) { dialog = Dialog.RefreshRate }
            }
            section(R.string.settings_section_language)
            item {
                SettingRow(
                    VIcon.Language, R.string.settings_ui_language,
                    languageLabel(settings.uiLanguage, systemLabel, displayLocale),
                ) { dialog = Dialog.UiLanguage }
            }
            item {
                SettingRow(
                    VIcon.Lyrics, R.string.settings_content_language,
                    languageLabel(settings.contentLanguage, systemLabel, displayLocale),
                ) { dialog = Dialog.ContentLanguage }
            }
            item {
                SettingRow(
                    VIcon.Public, R.string.settings_content_region,
                    regionLabel(settings.contentRegion, systemLabel, displayLocale),
                ) { dialog = Dialog.Region }
            }
            item {
                Text(
                    stringResource(R.string.settings_region_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }
            section(R.string.settings_section_playback)
            item { SettingRow(VIcon.HighQuality, R.string.settings_quality_wifi, qualityLabel(settings.wifiQuality)) { dialog = Dialog.WifiQuality } }
            item { SettingRow(VIcon.HighQuality, R.string.settings_quality_mobile, qualityLabel(settings.mobileQuality)) { dialog = Dialog.MobileQuality } }
            item {
                SwitchRow(VIcon.SkipNext, R.string.settings_autoplay, R.string.settings_autoplay_desc, settings.autoplay) { v -> viewModel.update { it.copy(autoplay = v) } }
            }
            item {
                SwitchRow(VIcon.MusicNote, R.string.settings_background, R.string.settings_background_desc, settings.backgroundPlayback) { v -> viewModel.update { it.copy(backgroundPlayback = v) } }
            }
            item {
                SwitchRow(VIcon.PictureInPicture, R.string.settings_pip, R.string.settings_pip_desc, settings.pictureInPicture) { v -> viewModel.update { it.copy(pictureInPicture = v) } }
            }
            item {
                SwitchRow(VIcon.ClosedCaption, R.string.settings_subtitles, R.string.settings_subtitles_desc, settings.subtitlesEnabled) { v -> viewModel.update { it.copy(subtitlesEnabled = v) } }
            }
            item {
                SettingRow(
                    VIcon.VisibilityOff, R.string.settings_hidden_channels,
                    if (settings.hiddenChannels.isEmpty()) stringResource(R.string.settings_hidden_channels_none)
                    else stringResource(R.string.settings_hidden_channels_count, settings.hiddenChannels.size),
                ) { dialog = Dialog.HiddenChannels }
            }
            item {
                SwitchRow(VIcon.VisibilityOff, R.string.settings_hide_shorts, R.string.settings_hide_shorts_desc, settings.hideShorts) { v -> viewModel.update { it.copy(hideShorts = v) } }
            }
            section(R.string.settings_section_privacy)
            item {
                SwitchRow(VIcon.History, R.string.settings_local_history, R.string.settings_local_history_desc, settings.saveLocalHistory) { v -> viewModel.update { it.copy(saveLocalHistory = v) } }
            }
            item {
                SwitchRow(VIcon.PrivacyTip, R.string.settings_private_mode, R.string.settings_private_mode_desc, settings.privateMode) { v -> viewModel.update { it.copy(privateMode = v) } }
            }
            item { SettingRow(VIcon.DeleteSweep, R.string.settings_clear_device_history, null) { dialog = Dialog.ClearHistory } }
            item { SettingRow(VIcon.DeleteSweep, R.string.settings_clear_search_history, null) { dialog = Dialog.ClearSearch } }
            item {
                Text(
                    stringResource(R.string.settings_history_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }
            section(R.string.settings_section_storage)
            item {
                SettingRow(
                    VIcon.Download, R.string.settings_download_dir,
                    downloadDirLabel(settings.downloadDir),
                ) { dialog = Dialog.DownloadDir }
            }
            item { SettingRow(VIcon.Storage, R.string.settings_clear_cache, R.string.settings_clear_cache_desc) { dialog = Dialog.ClearCache } }
            section(R.string.settings_section_about)
            item { SettingRow(VIcon.Info, R.string.settings_version, BuildConfig.VERSION_NAME, onClick = null) }
            item { SettingRow(VIcon.Lock, R.string.settings_licenses, null) { onLicenses() } }
            if (onDebug != null) {
                item { SettingRow(VIcon.Tune, R.string.settings_debug_tools, null) { onDebug() } }
            }
        }
    }

    when (dialog) {
        Dialog.Theme -> ChoiceDialog(
            stringResource(R.string.settings_theme),
            ThemeMode.entries.map { Choice(it, themeLabel(it)) },
            settings.themeMode,
            { dialog = null },
        ) { v -> viewModel.update { it.copy(themeMode = v) }; dialog = null }
        Dialog.DownloadDir -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.settings_download_dir)) },
            text = {
                Column {
                    Text(downloadDirLabel(settings.downloadDir), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_download_dir_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            },
            confirmButton = {
                TextButton(shapes = ButtonDefaults.shapes(), onClick = { dialog = null; pickFolder.launch(null) }) {
                    Text(stringResource(R.string.settings_download_dir_choose))
                }
            },
            dismissButton = {
                Row {
                    if (settings.downloadDir.isNotBlank()) {
                        TextButton(shapes = ButtonDefaults.shapes(), onClick = { viewModel.update { it.copy(downloadDir = "") }; dialog = null }) {
                            Text(stringResource(R.string.settings_download_dir_default))
                        }
                    }
                    TextButton(shapes = ButtonDefaults.shapes(), onClick = { dialog = null }) { Text(stringResource(R.string.action_close)) }
                }
            },
        )
        Dialog.HiddenChannels -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.settings_hidden_channels)) },
            text = {
                if (settings.hiddenChannels.isEmpty()) {
                    Text(stringResource(R.string.settings_hidden_channels_none))
                } else {
                    androidx.compose.foundation.lazy.LazyColumn {
                        val entries = settings.hiddenChannels.toList().sortedBy { it.substringAfter('|').lowercase() }
                        items(entries.size) { index ->
                            val entry = entries[index]
                            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.substringAfter('|'), Modifier.weight(1f), maxLines = 2)
                                TextButton(
                                    shapes = ButtonDefaults.shapes(),
                                    onClick = { viewModel.update { it.copy(hiddenChannels = it.hiddenChannels - entry) } },
                                ) { Text(stringResource(R.string.action_show_again)) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { dialog = null }) { Text(stringResource(R.string.action_close)) } },
        )
        Dialog.RefreshRate -> {
            val rates = remember { vibe.tube.ui.RefreshRate.supportedRates(context) }
            ChoiceDialog(
                stringResource(R.string.settings_refresh_rate),
                (listOf(vibe.tube.ui.RefreshRate.MAX, vibe.tube.ui.RefreshRate.SYSTEM) + rates.reversed()).map { Choice(it, refreshLabel(it)) },
                settings.refreshRate,
                { dialog = null },
            ) { v -> viewModel.update { it.copy(refreshRate = v) }; dialog = null }
        }
        Dialog.Font -> FontSheet(settings, { change -> viewModel.update { it.change() } }) { dialog = null }
        Dialog.UiLanguage -> ChoiceDialog(
            stringResource(R.string.settings_ui_language),
            listOf(AppSettings.SYSTEM, "en", "vi").map { Choice(it, languageLabel(it, systemLabel, displayLocale)) },
            settings.uiLanguage,
            { dialog = null },
        ) { v -> viewModel.update { it.copy(uiLanguage = v) }; dialog = null }
        Dialog.ContentLanguage -> ChoiceDialog(
            stringResource(R.string.settings_content_language),
            (listOf(AppSettings.SYSTEM) + contentLanguages).map { Choice(it, languageLabel(it, systemLabel, displayLocale)) },
            settings.contentLanguage,
            { dialog = null },
        ) { v -> viewModel.update { it.copy(contentLanguage = v) }; dialog = null }
        Dialog.Region -> ChoiceDialog(
            stringResource(R.string.settings_content_region),
            (listOf(AppSettings.SYSTEM) + contentRegions).map { Choice(it, regionLabel(it, systemLabel, displayLocale)) },
            settings.contentRegion,
            { dialog = null },
        ) { v -> viewModel.update { it.copy(contentRegion = v) }; dialog = null }
        Dialog.WifiQuality -> ChoiceDialog(
            stringResource(R.string.settings_quality_wifi),
            QualityCap.entries.map { Choice(it, qualityLabel(it)) },
            settings.wifiQuality,
            { dialog = null },
        ) { v -> viewModel.update { it.copy(wifiQuality = v) }; dialog = null }
        Dialog.MobileQuality -> ChoiceDialog(
            stringResource(R.string.settings_quality_mobile),
            QualityCap.entries.map { Choice(it, qualityLabel(it)) },
            settings.mobileQuality,
            { dialog = null },
        ) { v -> viewModel.update { it.copy(mobileQuality = v) }; dialog = null }
        Dialog.ClearCache -> ConfirmDialog(R.string.settings_clear_cache, R.string.dialog_clear_cache_body, { dialog = null }) { viewModel.clearCache() }
        Dialog.ClearHistory -> ConfirmDialog(R.string.dialog_clear_device_history_title, R.string.dialog_clear_device_history_body, { dialog = null }) { viewModel.clearPlaybackHistory() }
        Dialog.ClearSearch -> ConfirmDialog(R.string.dialog_clear_search_title, R.string.dialog_clear_search_body, { dialog = null }) { viewModel.clearSearchHistory() }
        null -> Unit
    }
}

@Composable
private fun downloadDirLabel(dir: String): String {
    if (dir.isBlank()) return stringResource(R.string.settings_download_dir_default_label)
    val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(dir)) }.getOrNull()
    return id?.substringAfter(':')?.ifBlank { null } ?: id ?: dir
}

@Composable
private fun refreshLabel(rate: Int): String = when (rate) {
    vibe.tube.ui.RefreshRate.MAX -> stringResource(R.string.refresh_rate_max)
    vibe.tube.ui.RefreshRate.SYSTEM -> stringResource(R.string.refresh_rate_system)
    else -> stringResource(R.string.refresh_rate_hz, rate)
}

@Composable
private fun ConfirmDialog(title: Int, body: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { onDismiss(); onConfirm() }) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: Int) {
    item(key = "section-$title") {
        Column {
            HorizontalDivider(Modifier.padding(top = Spacing.sm))
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Spacing.lg, top = Spacing.lg, bottom = Spacing.xs),
            )
        }
    }
}

@Composable
private fun SettingRow(icon: Int, title: Int, summary: Any?, onClick: (() -> Unit)?) {
    ListItem(
        modifier = Modifier.then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        leadingContent = { VIconImage(icon, null) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = summary?.let { s -> { Text(if (s is Int) stringResource(s) else s.toString()) } },
    )
}

@Composable
private fun SwitchRow(icon: Int, title: Int, summary: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.selectable(checked, role = Role.Switch) { onChange(!checked) },
        leadingContent = { VIconImage(icon, null) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(summary)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.System -> R.string.theme_system
        ThemeMode.Light -> R.string.theme_light
        ThemeMode.Dark -> R.string.theme_dark
        ThemeMode.Amoled -> R.string.theme_amoled
    },
)

@Composable
private fun qualityLabel(cap: QualityCap): String = when (cap) {
    QualityCap.Auto -> stringResource(R.string.quality_auto)
    else -> cap.label.orEmpty()
}

private fun languageLabel(code: String, systemLabel: String, display: Locale): String =
    if (code == AppSettings.SYSTEM) systemLabel
    else Locale.forLanguageTag(code).getDisplayLanguage(display).replaceFirstChar { it.titlecase(display) }

private fun regionLabel(code: String, systemLabel: String, display: Locale): String =
    if (code == AppSettings.SYSTEM) systemLabel else Locale("", code).getDisplayCountry(display)
