package vibe.tube.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vibe.tube.R
import vibe.tube.ui.VIcon
import vibe.tube.youtube.model.NotifyLevel

fun notifyIcon(level: NotifyLevel?): Int = when (level) {
    NotifyLevel.All -> VIcon.NotificationsActive
    NotifyLevel.None -> VIcon.NotificationsOff
    else -> VIcon.Notifications
}

private fun notifyLabel(level: NotifyLevel): Int = when (level) {
    NotifyLevel.All -> R.string.notify_all
    NotifyLevel.Personalized -> R.string.notify_personalized
    NotifyLevel.None -> R.string.notify_none
}

/**
 * What the subscribed button opens (YouTube's own layout): the three bell levels, then Unsubscribe. The bell levels only
 * show when the page handed over the requests for them ([available]).
 */
@Composable
fun SubscribeMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    available: Set<NotifyLevel>,
    level: NotifyLevel?,
    onLevel: (NotifyLevel) -> Unit,
    onUnsubscribe: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        NotifyLevel.entries.filter { it in available }.forEach { option ->
            DropdownMenuItem(
                text = { Text(stringResource(notifyLabel(option))) },
                leadingIcon = {
                    VIconImage(
                        notifyIcon(option),
                        null,
                        Modifier.size(20.dp),
                        tint = if (option == level) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = if (option == level) {
                    { VIconImage(VIcon.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
                } else null,
                onClick = {
                    onDismiss()
                    onLevel(option)
                },
            )
        }
        if (available.isNotEmpty()) HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_unsubscribe)) },
            leadingIcon = { VIconImage(VIcon.PersonAdd, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = {
                onDismiss()
                onUnsubscribe()
            },
        )
    }
}

/**
 * The subscribe button. Once subscribed it becomes the bell: the icon shows the current level, and tapping it opens the
 * levels and Unsubscribe, so the bell is part of the button instead of a second control beside it.
 */
@Composable
fun SubscribeButton(
    subscribed: Boolean,
    enabled: Boolean,
    notifyLevels: Set<NotifyLevel>,
    notifyLevel: NotifyLevel?,
    onToggle: () -> Unit,
    onNotify: (NotifyLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (!subscribed) {
        androidx.compose.material3.Button(
            shapes = androidx.compose.material3.ButtonDefaults.shapes(),
            onClick = onToggle,
            enabled = enabled,
            modifier = modifier,
        ) { Text(stringResource(R.string.action_subscribe)) }
        return
    }
    androidx.compose.foundation.layout.Box(modifier) {
        androidx.compose.material3.FilledTonalButton(
            shapes = androidx.compose.material3.ButtonDefaults.shapes(),
            onClick = { menu = true },
            enabled = enabled,
        ) {
            VIconImage(notifyIcon(notifyLevel), null, Modifier.size(18.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.action_subscribed))
            VIconImage(VIcon.ArrowDropDown, null, Modifier.size(20.dp))
        }
        SubscribeMenu(
            expanded = menu,
            onDismiss = { menu = false },
            available = notifyLevels,
            level = notifyLevel,
            onLevel = onNotify,
            onUnsubscribe = onToggle,
        )
    }
}
