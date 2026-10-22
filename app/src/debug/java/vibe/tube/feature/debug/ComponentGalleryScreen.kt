package vibe.tube.feature.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.theme.Spacing

private const val VIETNAMESE = "Tiếng Việt có dấu: Nguyễn Đặng Ưu Ơ ă â ê ô ơ ư đ — ạ ả ấ ầ ẩ ẫ ậ ắ ằ ẳ ẵ ặ ẹ ẻ ẽ ế ề ể ễ ệ ỉ ị ọ ỏ ố ồ ổ ỗ ộ ớ ờ ở ỡ ợ ụ ủ ứ ừ ử ữ ự ỳ ỵ ỷ ỹ"

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ComponentGalleryScreen(onBack: () -> Unit, onDiagnostics: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var slider by remember { mutableFloatStateOf(0.4f) }
    var text by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(true) }
    var radio by remember { mutableIntStateOf(0) }
    var segmented by remember { mutableIntStateOf(0) }
    var chipSelected by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Component gallery") },
                navigationIcon = { TooltipIconButton(VIcon.ArrowBack, "Back", onBack) },
                actions = { TooltipIconButton(VIcon.Tune, "Diagnostics", onDiagnostics) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            item { Section("Typography (Google Sans Flex, ROND=100)") { TypographySamples() } }
            item { Section("Color roles") { ColorRoles(MaterialTheme.colorScheme) } }
            item {
                Section("Shapes") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        listOf(
                            "xs" to MaterialTheme.shapes.extraSmall, "s" to MaterialTheme.shapes.small, "m" to MaterialTheme.shapes.medium,
                            "l" to MaterialTheme.shapes.large, "xl" to MaterialTheme.shapes.extraLarge,
                        ).forEach { (name, shape) ->
                            Box(
                                Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, shape),
                                contentAlignment = Alignment.Center,
                            ) { Text(name, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                        }
                        Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape))
                    }
                }
            }
            item {
                Section("Buttons") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Button(onClick = {}) { Text("Filled") }
                        FilledTonalButton(onClick = {}) { Text("Tonal") }
                        ElevatedButton(onClick = {}) { Text("Elevated") }
                        OutlinedButton(onClick = {}) { Text("Outlined") }
                        TextButton(onClick = {}) { Text("Text") }
                        Button(onClick = {}, enabled = false) { Text("Disabled") }
                        FilledIconButton(onClick = {}) { VIconImage(VIcon.PlayArrowFilled, "Play") }
                        FilledTonalIconButton(onClick = {}) { VIconImage(VIcon.ThumbUp, "Like") }
                        TooltipIconButton(VIcon.OpenInNew, "Share", {})
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("Day", "Night", "AMOLED").forEachIndexed { index, label ->
                            SegmentedButton(
                                selected = segmented == index,
                                onClick = { segmented = index },
                                shape = SegmentedButtonDefaults.itemShape(index, 3),
                            ) { Text(label) }
                        }
                    }
                }
            }
            item {
                Section("FAB") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        FloatingActionButton(onClick = {}) { VIconImage(VIcon.Add, "Add") }
                        ExtendedFloatingActionButton(onClick = {}, icon = { VIconImage(VIcon.PlaylistAdd, null) }, text = { Text("Create") })
                    }
                }
            }
            item {
                Section("Chips & badges") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        AssistChip(onClick = {}, label = { Text("Assist") }, leadingIcon = { VIconImage(VIcon.Schedule, null) })
                        FilterChip(selected = chipSelected, onClick = { chipSelected = !chipSelected }, label = { Text("Filter") })
                        InputChip(selected = false, onClick = {}, label = { Text("Input") }, trailingIcon = { VIconImage(VIcon.Close, null) })
                        SuggestionChip(onClick = {}, label = { Text("Suggestion") })
                        BadgedBox(badge = { Badge { Text("3") } }) { VIconImage(VIcon.Subscriptions, "Subscriptions") }
                    }
                }
            }
            item {
                Section("Cards & lists") {
                    Card(Modifier.fillMaxWidth()) { Text("Filled card", Modifier.padding(Spacing.lg)) }
                    ElevatedCard(Modifier.fillMaxWidth()) { Text("Elevated card", Modifier.padding(Spacing.lg)) }
                    OutlinedCard(Modifier.fillMaxWidth()) { Text("Outlined card", Modifier.padding(Spacing.lg)) }
                    ListItem(
                        leadingContent = { VIconImage(VIcon.History, null) },
                        headlineContent = { Text("List item") },
                        supportingContent = { Text("Supporting text với tiếng Việt") },
                        trailingContent = { Text("12:34") },
                    )
                }
            }
            item {
                Section("Selection controls & slider") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Switch(checked = checked, onCheckedChange = { checked = it })
                        Checkbox(checked = checked, onCheckedChange = { checked = it })
                        RadioButton(selected = radio == 0, onClick = { radio = 0 })
                        RadioButton(selected = radio == 1, onClick = { radio = 1 })
                    }
                    Slider(value = slider, onValueChange = { slider = it })
                }
            }
            item {
                Section("Progress") {
                    LinearProgressIndicator(progress = { slider }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        LoadingIndicator()
                    }
                }
            }
            item {
                Section("Text field") {
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Label") }, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                Section("Tabs & navigation") {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        listOf("Mới nhất", "Kênh", "Danh sách").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                    }
                    NavigationBar {
                        listOf(VIcon.Home to "Home", VIcon.Subscriptions to "Subs", VIcon.VideoLibrary to "Library").forEachIndexed { i, (icon, label) ->
                            NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { VIconImage(icon, null) }, label = { Text(label) })
                        }
                    }
                    Row {
                        NavigationRail {
                            listOf(VIcon.Home to "Home", VIcon.Subscriptions to "Subs").forEachIndexed { i, (icon, label) ->
                                NavigationRailItem(selected = tab == i, onClick = { tab = i }, icon = { VIconImage(icon, null) }, label = { Text(label) })
                            }
                        }
                    }
                }
            }
            item {
                Section("Menus, dialogs, sheets, snackbars") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Box {
                            OutlinedButton(onClick = { menu = true }) { Text("Menu") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Item one") }, onClick = { menu = false })
                                DropdownMenuItem(text = { Text("Item two") }, onClick = { menu = false })
                            }
                        }
                        OutlinedButton(onClick = { dialog = true }) { Text("Dialog") }
                        OutlinedButton(onClick = { sheet = true }) { Text("Bottom sheet") }
                        OutlinedButton(onClick = { scope.launch { snackbar.showSnackbar("Snackbar message") } }) { Text("Snackbar") }
                    }
                }
            }
            item { HorizontalDivider() }
        }
    }
    if (dialog) {
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("Dialog") },
            text = { Text("Nội dung hộp thoại") },
            confirmButton = { TextButton(onClick = { dialog = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("Cancel") } },
        )
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(Modifier.padding(Spacing.lg)) { Text("Bottom sheet", style = MaterialTheme.typography.titleLarge) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun TypographySamples() {
    val t = MaterialTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        listOf(
            "displaySmall" to t.displaySmall, "headlineMedium" to t.headlineMedium, "titleLarge" to t.titleLarge,
            "titleMedium" to t.titleMedium, "bodyLarge" to t.bodyLarge, "bodyMedium" to t.bodyMedium,
            "labelLarge" to t.labelLarge, "bodySmall" to t.bodySmall,
        ).forEach { (name, style) -> Text("$name — Việt Nam Hà Nội Sài Gòn", style = style) }
        Text(VIETNAMESE, style = t.bodyMedium)
        Text("Fallback glyphs: 日本語 한국어 العربية ไทย 😀", style = t.bodyMedium)
    }
}

@Composable
private fun ColorRoles(scheme: ColorScheme) {
    val roles = listOf(
        "primary" to scheme.primary, "onPrimary" to scheme.onPrimary, "primaryContainer" to scheme.primaryContainer,
        "onPrimaryContainer" to scheme.onPrimaryContainer, "secondary" to scheme.secondary, "onSecondary" to scheme.onSecondary,
        "secondaryContainer" to scheme.secondaryContainer, "onSecondaryContainer" to scheme.onSecondaryContainer,
        "tertiary" to scheme.tertiary, "onTertiary" to scheme.onTertiary, "tertiaryContainer" to scheme.tertiaryContainer,
        "onTertiaryContainer" to scheme.onTertiaryContainer, "error" to scheme.error, "onError" to scheme.onError,
        "errorContainer" to scheme.errorContainer, "onErrorContainer" to scheme.onErrorContainer,
        "background" to scheme.background, "onBackground" to scheme.onBackground, "surface" to scheme.surface,
        "onSurface" to scheme.onSurface, "surfaceVariant" to scheme.surfaceVariant, "onSurfaceVariant" to scheme.onSurfaceVariant,
        "surfaceDim" to scheme.surfaceDim, "surfaceBright" to scheme.surfaceBright,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest, "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer, "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest, "outline" to scheme.outline,
        "outlineVariant" to scheme.outlineVariant, "inverseSurface" to scheme.inverseSurface,
        "inverseOnSurface" to scheme.inverseOnSurface, "inversePrimary" to scheme.inversePrimary, "scrim" to scheme.scrim,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        roles.forEach { (name, color) ->
            Surface(color = color, shape = MaterialTheme.shapes.small, border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant)) {
                val onColor = if (color.luminance() > 0.5f) Color.Black else Color.White
                Text(name, color = onColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(Spacing.sm))
            }
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
