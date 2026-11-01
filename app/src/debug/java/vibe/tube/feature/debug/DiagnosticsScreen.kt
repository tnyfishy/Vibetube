package vibe.tube.feature.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import vibe.tube.account.AuthState
import vibe.tube.account.SessionManager
import vibe.tube.core.Diagnostics
import vibe.tube.core.Redact
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.StateMessage
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(val diagnostics: Diagnostics, val session: SessionManager) : ViewModel() {
    fun sessionSummary(): String {
        val state = session.authState.value
        val ctx = session.currentContext()
        val verified = state is AuthState.Verified
        return "auth=${state::class.simpleName} verified=$verified account=${Redact.anonymize(ctx?.scopeKey)}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val events by viewModel.diagnostics.events.collectAsStateWithLifecycle()
    val auth by viewModel.session.authState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics (debug)") },
                navigationIcon = { TooltipIconButton(VIcon.ArrowBack, "Back", onBack) },
                actions = { TooltipIconButton(VIcon.DeleteSweep, "Clear", viewModel.diagnostics::clear) },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Text(
                viewModel.sessionSummary() + " state=" + auth,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(Spacing.lg),
            )
            if (events.isEmpty()) {
                StateMessage(VIcon.Info, "No events yet", "Load a feed to record provider diagnostics.")
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(events) { e ->
                        Card {
                            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                                Text(
                                    "${e.feedType} · ${e.source} · ${e.durationMs} ms",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "provider=${e.provider}\nsessionVerified=${e.sessionVerified} account=${e.accountRef}\n" +
                                        "items=${e.itemCount} continuation=${e.hasContinuation} adsSkipped=${e.adsSkipped}\n" +
                                        "http=${e.httpCode ?: "-"} parserError=${e.parserError ?: "-"} sessionExpired=${e.sessionExpired}\n" +
                                        DateFormat.getTimeInstance().format(Date(e.timestamp)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
