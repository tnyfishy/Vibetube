package vibe.tube.update

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vibe.tube.R
import vibe.tube.BuildConfig

@Composable
fun UpdatePanel(updater: AppUpdater = hiltViewModel(), autoCheck: Boolean = false) {
    val state by updater.state.collectAsStateWithLifecycle()
    LaunchedEffect(autoCheck) { if (autoCheck && state.manifest == null && !state.ready) updater.check() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
        Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
        state.manifest?.let { m ->
            Text("${m.versionName} · ${"%.1f".format(m.size / 1_000_000f)} MB")
            if (m.notes.isNotBlank()) Text(m.notes, style = MaterialTheme.typography.bodySmall)
        }
        state.message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
        if (state.busy) {
            state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = updater::cancel) { Text(stringResource(R.string.action_cancel)) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = updater::check) { Text(stringResource(R.string.update_check)) }
            if (state.ready) Button(onClick = updater::install) { Text(stringResource(R.string.update_install)) }
            else if (state.manifest != null) Button(onClick = updater::download) { Text(stringResource(R.string.update_download)) }
        }
    }
}

/** Opened from the "new version" notification or snackbar; checks straight away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        UpdatePanel(autoCheck = true)
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}
