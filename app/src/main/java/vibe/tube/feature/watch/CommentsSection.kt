package vibe.tube.feature.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vibe.tube.R
import vibe.tube.account.AuthState
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.Avatar
import vibe.tube.ui.components.StateMessage
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.theme.Sizing
import vibe.tube.ui.theme.Spacing
import vibe.tube.youtube.model.Comment

@Composable
fun CommentItem(
    comment: Comment,
    replies: RepliesState?,
    canReply: Boolean,
    onToggleReplies: () -> Unit,
    onMoreReplies: () -> Unit,
    onReply: (String, () -> Unit) -> Unit,
    nested: Boolean = false,
) {
    var replying by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(start = if (nested) Spacing.xl else 0.dp, top = Spacing.sm, bottom = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Avatar(comment.authorAvatar, if (nested) Sizing.avatarSmall + 8.dp else Sizing.avatarMedium)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                listOfNotNull(comment.authorName, comment.publishedText).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = if (comment.isCreator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(comment.text, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                comment.likeCountText?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        VIconImage(VIcon.ThumbUp, null, Modifier.size(Sizing.iconSmall), MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (canReply && comment.replyParams != null && !nested) {
                    TextButton(shapes = ButtonDefaults.shapes(), onClick = { replying = !replying }) { Text(stringResource(R.string.comment_reply)) }
                }
            }
            if (replying) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.comment_reply_hint)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(
                        shapes = ButtonDefaults.shapes(),
                        enabled = draft.isNotBlank(),
                        onClick = { onReply(draft) { draft = ""; replying = false } },
                    ) { Text(stringResource(R.string.comment_send)) }
                    TextButton(shapes = ButtonDefaults.shapes(), onClick = { replying = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            }
            if (comment.repliesToken != null && !nested) {
                TextButton(shapes = ButtonDefaults.shapes(), onClick = onToggleReplies) {
                    Text(
                        if (replies?.expanded == true) stringResource(R.string.comment_hide_replies)
                        else stringResource(R.string.comment_show_replies, comment.replyCountText.orEmpty()),
                    )
                }
            }
        }
    }
    if (replies?.expanded == true) {
        replies.items.forEach { reply ->
            CommentItem(reply, null, false, {}, {}, { _, _ -> }, nested = true)
        }
        if (replies.loading) {
            Row(Modifier.fillMaxWidth().padding(Spacing.md), horizontalArrangement = Arrangement.Center) { ContainedLoadingIndicator(Modifier.size(40.dp)) }
        }
        if (replies.failed) {
            TextButton(shapes = ButtonDefaults.shapes(), onClick = onToggleReplies) { Text(stringResource(R.string.action_retry)) }
        }
        if (replies.continuation != null && !replies.loading) {
            TextButton(shapes = ButtonDefaults.shapes(), onClick = onMoreReplies, modifier = Modifier.padding(start = Spacing.xl)) {
                Text(stringResource(R.string.comment_more_replies))
            }
        }
    }
}

@Composable
fun CommentsHeader(
    state: CommentsUiState,
    authState: AuthState,
    onSort: (vibe.tube.youtube.model.CommentSort) -> Unit,
    onPost: (String, () -> Unit) -> Unit,
    onLogin: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.totalText?.let { stringResource(R.string.comments_title_with_total, it) } ?: stringResource(R.string.comments_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (state.sorts.size > 1) {
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = { menu = true }) {
                        VIconImage(VIcon.Sort, null)
                        Text(state.sorts.firstOrNull { it.selected }?.title ?: stringResource(R.string.comments_sort), Modifier.padding(start = Spacing.sm))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        state.sorts.forEach { sort ->
                            DropdownMenuItem(text = { Text(sort.title) }, onClick = { menu = false; onSort(sort) })
                        }
                    }
                }
            }
        }
        if (state.disabledMessage == null) {
            if (authState is AuthState.Verified) {
                if (state.createToken != null) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.comment_add_hint)) },
                        enabled = !state.posting,
                    )
                    Button(
                        shapes = ButtonDefaults.shapes(),
                        enabled = draft.isNotBlank() && !state.posting,
                        onClick = { onPost(draft) { draft = "" } },
                    ) { Text(stringResource(R.string.comment_post)) }
                }
            } else {
                OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.comment_sign_in_to_comment)) }
            }
        }
    }
}

@Composable
fun CommentsEmptyStates(state: CommentsUiState, onRetry: () -> Unit) {
    when {
        state.loading -> Row(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalArrangement = Arrangement.Center) { ContainedLoadingIndicator() }
        state.error -> StateMessage(VIcon.Error, stringResource(R.string.comments_error_title), null, primaryLabel = stringResource(R.string.action_retry), onPrimary = onRetry)
        state.disabledMessage != null -> StateMessage(
            VIcon.Lock,
            stringResource(R.string.comments_disabled_title),
            state.disabledMessage.takeIf { it.isNotBlank() },
        )
        state.comments.isEmpty() -> StateMessage(VIcon.Lyrics, stringResource(R.string.comments_empty_title), null)
    }
}
