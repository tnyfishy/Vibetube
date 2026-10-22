package vibe.tube.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import vibe.tube.R
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.theme.Spacing

private data class Entry(val name: String, val license: String)

private val entries = listOf(
    Entry("Google Sans Flex (font)", "SIL Open Font License 1.1"),
    Entry("Inter, Nunito, Lexend, Noto Sans, Open Sans (fonts)", "SIL Open Font License 1.1"),
    Entry("Material Symbols Rounded (icons)", "Apache License 2.0"),
    Entry("AndroidX: Core, AppCompat, Activity, Lifecycle, Navigation, Room, DataStore, Media3", "Apache License 2.0"),
    Entry("Jetpack Compose and Material 3", "Apache License 2.0"),
    Entry("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache License 2.0"),
    Entry("Dagger / Hilt", "Apache License 2.0"),
    Entry("OkHttp, Okio", "Apache License 2.0"),
    Entry("Coil", "Apache License 2.0"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val ofl = remember { context.resources.openRawResource(R.raw.license_ofl_google_sans_flex).bufferedReader().use { it.readText() } }
    val apache = remember { context.resources.openRawResource(R.raw.license_apache_2_0).bufferedReader().use { it.readText() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_licenses)) },
                navigationIcon = { TooltipIconButton(VIcon.ArrowBack, stringResource(R.string.action_back), onBack) },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState())) {
            entries.forEach { ListItem(headlineContent = { Text(it.name) }, supportingContent = { Text(it.license) }) }
            Text(
                "SIL Open Font License 1.1 — Google Sans Flex",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(Spacing.lg),
            )
            Text(ofl, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Spacing.lg))
            Text(
                "Apache License 2.0",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(Spacing.lg),
            )
            Text(apache, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
        }
    }
}
