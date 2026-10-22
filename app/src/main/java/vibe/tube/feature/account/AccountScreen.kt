package vibe.tube.feature.account

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vibe.tube.R
import vibe.tube.account.AccountSession
import vibe.tube.account.AuthState
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.Avatar
import vibe.tube.ui.components.Banner
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.theme.Sizing
import vibe.tube.ui.theme.Spacing

private sealed interface Confirm {
    data class Logout(val id: String) : Confirm
    data object LogoutAll : Confirm
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onLogin: () -> Unit,
    onSettings: () -> Unit,
    contentPadding: PaddingValues,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val context by viewModel.activeId.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val active = context?.let { sessions[it.accountId] }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_account)) }) },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = inner.calculateTopPadding() + Spacing.sm,
                bottom = contentPadding.calculateBottomPadding() + Spacing.lg,
                start = Spacing.gutter,
                end = Spacing.gutter,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                val saved: @Composable () -> Unit = {
                    SavedAccounts(
                        sessions = sessions.values.toList(),
                        activeId = context?.accountId,
                        onSwitch = viewModel::switchAccount,
                        onSignOut = { confirm = Confirm.Logout(it) },
                        onAdd = onLogin,
                    )
                }
                if (active == null) GuestCard(onLogin, saved.takeIf { sessions.isNotEmpty() })
                else ActiveCard(active, auth, onLogin, saved)
            }
            if (active != null && active.identities.size > 1) {
                item {
                    Text(stringResource(R.string.account_channels), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.account_channels_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(active.identities, key = { "id:${it.key}" }) { identity ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(active.activeIdentityKey == identity.key, role = Role.RadioButton) {
                                viewModel.switchIdentity(identity.key)
                            }
                            .padding(vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        RadioButton(selected = active.activeIdentityKey == identity.key, onClick = null)
                        Avatar(identity.avatarUrl, Sizing.avatarMedium)
                        Column(Modifier.weight(1f)) {
                            Text(identity.name, style = MaterialTheme.typography.bodyLarge)
                            identity.handle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            if (active != null) {
                item {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.sm)) {
                        OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = { confirm = Confirm.Logout(active.id) }, modifier = Modifier.fillMaxWidth()) {
                            VIconImage(VIcon.Logout, null)
                            Text(stringResource(R.string.action_sign_out), Modifier.padding(start = Spacing.sm))
                        }
                        if (sessions.size > 1) {
                            TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirm = Confirm.LogoutAll }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.account_sign_out_all))
                            }
                        }
                    }
                }
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button, onClick = onSettings),
                    leadingContent = { VIconImage(VIcon.Settings, null) },
                    headlineContent = { Text(stringResource(R.string.settings_title)) },
                    trailingContent = { VIconImage(VIcon.ArrowForward, null) },
                )
            }
        }
    }

    confirm?.let { c ->
        val (title, body, action) = when (c) {
            is Confirm.Logout -> Triple(R.string.dialog_sign_out_title, R.string.dialog_sign_out_body, { viewModel.logout(c.id) })
            Confirm.LogoutAll -> Triple(R.string.dialog_sign_out_all_title, R.string.dialog_sign_out_all_body, { viewModel.logoutAll() })
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(body)) },
            confirmButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirm = null; action() }) { Text(stringResource(R.string.action_confirm)) } },
            dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun GuestCard(onLogin: () -> Unit, saved: (@Composable () -> Unit)?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.account_guest_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.account_guest_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(shapes = ButtonDefaults.shapes(), onClick = onLogin) { Text(stringResource(R.string.action_sign_in)) }
            saved?.let {
                HorizontalDivider()
                it()
            }
        }
    }
}

@Composable
private fun ActiveCard(account: AccountSession, auth: AuthState, onLogin: () -> Unit, saved: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Avatar(account.activeIdentity?.avatarUrl ?: account.avatarUrl, Sizing.avatarHero / 1.5f)
                Column(Modifier.weight(1f)) {
                    Text(account.activeIdentity?.name ?: account.name, style = MaterialTheme.typography.titleLarge)
                    (account.activeIdentity?.handle ?: account.handle)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val (label, icon) = when (auth) {
                is AuthState.Verified -> R.string.account_status_verified to VIcon.VerifiedFilled
                is AuthState.Verifying -> R.string.account_status_verifying to VIcon.Cached
                is AuthState.Expired -> R.string.account_status_expired to VIcon.Lock
                is AuthState.Unverified -> R.string.account_status_unverified to VIcon.CloudOff
                AuthState.Guest -> R.string.account_status_guest to VIcon.Person
            }
            AssistChip(onClick = {}, label = { Text(stringResource(label)) }, leadingIcon = { VIconImage(icon, null) })
            if (auth is AuthState.Expired) {
                Banner(
                    stringResource(R.string.feed_session_expired_body),
                    VIcon.Lock,
                    actionLabel = stringResource(R.string.action_sign_in_again),
                    onAction = onLogin,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            HorizontalDivider()
            saved()
        }
    }
}

/** "Accounts on this device": a collapsed row inside the profile card that expands on tap. */
@Composable
private fun SavedAccounts(
    sessions: List<AccountSession>,
    activeId: String?,
    onSwitch: (String) -> Unit,
    onSignOut: (String) -> Unit,
    onAdd: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, androidx.compose.animation.core.tween(240, easing = vibe.tube.ui.components.EmphasizedDecelerate), label = "chevron")
    Column(Modifier.animateContentSize(androidx.compose.animation.core.tween(240, easing = vibe.tube.ui.components.EmphasizedDecelerate))) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.account_saved_accounts), style = MaterialTheme.typography.titleMedium)
                Text(
                    pluralStringResource(R.plurals.account_count, sessions.size, sessions.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VIconImage(VIcon.ExpandMore, null, Modifier.rotate(rotation))
        }
        if (expanded) {
            sessions.forEach { account ->
                val isActive = account.id == activeId
                ListItem(
                    modifier = Modifier.clickable(enabled = !isActive, role = Role.Button) { onSwitch(account.id) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Avatar(account.avatarUrl, Sizing.avatarLarge) },
                    headlineContent = { Text(account.name) },
                    supportingContent = { Text(account.handle ?: stringResource(R.string.account_no_handle)) },
                    trailingContent = {
                        if (isActive) {
                            AssistChip(onClick = {}, label = { Text(stringResource(R.string.account_active)) })
                        } else {
                            TextButton(shapes = ButtonDefaults.shapes(), onClick = { onSignOut(account.id) }) { Text(stringResource(R.string.action_sign_out)) }
                        }
                    },
                )
            }
            FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = onAdd, modifier = Modifier.padding(top = Spacing.xs)) {
                VIconImage(VIcon.PersonAdd, null)
                Text(stringResource(R.string.account_add), Modifier.padding(start = Spacing.sm))
            }
        }
    }
}
