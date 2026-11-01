package vibe.tube.feature.watch

import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.gestures.awaitEachGesture

import androidx.compose.ui.input.pointer.PointerEventPass

import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.ui.focus.onFocusChanged

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vibe.tube.R
import vibe.tube.account.AuthState
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.Avatar
import vibe.tube.ui.components.StateMessage
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.theme.Sizing
import vibe.tube.ui.theme.Spacing
import vibe.tube.youtube.model.Comment

internal val LocalCurrentUserAvatar = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

internal class CommentInputState {
    var target by mutableStateOf<Comment?>(null)
    var send by mutableStateOf<((String, () -> Unit) -> Unit)?>(null)
    var focusKey by androidx.compose.runtime.mutableIntStateOf(0)
    val draft = mutableStateOf("")
    fun reply(comment: Comment, post: (String, () -> Unit) -> Unit) {
        if (target?.id != comment.id) draft.value = ""
        target = comment; send = post; focusKey++
    }
    fun dismiss() { if (target != null) draft.value = ""; target = null; send = null }
}
internal val LocalCommentInput = androidx.compose.runtime.staticCompositionLocalOf<CommentInputState?> { null }

/** Puts comments into Vietnamese; none (previews, tests) leaves them as written. */
internal val LocalCommentTranslator = androidx.compose.runtime.staticCompositionLocalOf<vibe.tube.data.translate.CommentTranslator?> { null }
internal val LocalMediaSeek = androidx.compose.runtime.staticCompositionLocalOf<(Long) -> Unit> { {} }

internal val LocalRateComment = androidx.compose.runtime.staticCompositionLocalOf<(Comment, Boolean) -> Unit> { { _, _ -> } }
internal val LocalHighlightComment = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }
internal val LocalDislikeComment = androidx.compose.runtime.staticCompositionLocalOf<(Comment) -> Unit> { {} }
internal val LocalReplyComment = androidx.compose.runtime.staticCompositionLocalOf<((Comment, String, () -> Unit) -> Unit)?> { null }
internal val LocalBusyComments = androidx.compose.runtime.staticCompositionLocalOf<Set<String>> { emptySet() }
internal val LocalPreviewReplies = androidx.compose.runtime.staticCompositionLocalOf<(Comment) -> Unit> { {} }
internal val LocalCommentSignedIn = androidx.compose.runtime.staticCompositionLocalOf { true }
internal val LocalCommentNotice = androidx.compose.runtime.staticCompositionLocalOf<(Int, Int?) -> Unit> { { _, _ -> } }

internal fun visibleThreadReplies(comment: Comment, replies: RepliesState?, ownerId: String?): List<Comment> {
    val items = (replies?.items.orEmpty() + comment.previewReplies).distinctBy { it.id }
    return if (replies?.expanded == true) items.sortedByDescending {
        when { it.isCreator || (ownerId != null && it.authorChannelId == ownerId) -> 2; it.isOwn -> 1; else -> 0 }
    } else emptyList()
}

@Composable
fun CommentItem(
    comment: Comment,
    replies: RepliesState?,
    canReply: Boolean,
    onToggleReplies: () -> Unit,
    onMoreReplies: () -> Unit,
    onReply: (String, () -> Unit) -> Unit,
    nested: Boolean = false,
    onOpenAuthor: ((String) -> Unit)? = null,
    replyDepth: Int = 0,
) {
    val actions = vibe.tube.ui.components.LocalVideoActions.current
    val authorId = comment.authorChannelId
    val input = LocalCommentInput.current
    val replyHandler = LocalReplyComment.current
    val signedIn = LocalCommentSignedIn.current
    val notice = LocalCommentNotice.current
    val previewReplies = LocalPreviewReplies.current
    val ownerId = LocalCommentOwner.current
    val shownReplies = remember(comment, replies, ownerId) { visibleThreadReplies(comment, replies, ownerId) }
    androidx.compose.runtime.LaunchedEffect(comment.id, comment.repliesToken, nested) {
        if (!nested && comment.repliesToken != null && comment.previewReplies.none {
                it.isCreator || (ownerId != null && it.authorChannelId == ownerId)
            }) {
            
            kotlinx.coroutines.delay(200)
            previewReplies(comment)
        }
    }
    val highlight = LocalHighlightComment.current == comment.id
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(highlight) { if (highlight && nested) { kotlinx.coroutines.delay(100); bring.bringIntoView() } }
    val highlightColor = MaterialTheme.colorScheme.secondaryContainer
    val threadColor = MaterialTheme.colorScheme.outlineVariant
    val ownLines = remember(comment.id) { ReplyThreadLines() }
    val inherited = LocalReplyThreadLines.current
    androidx.compose.runtime.DisposableEffect(comment.id, inherited) { onDispose { inherited?.children?.remove(comment.id); inherited?.let { it.epoch++ } } }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(bring)
        .onGloballyPositioned { if (!nested) { ownLines.root = it; ownLines.epoch++ } }
        .then(if (highlight) Modifier.background(highlightColor, MaterialTheme.shapes.medium) else Modifier)
        .drawWithCache {
            val path = androidx.compose.ui.graphics.Path()
                val epoch = ownLines.epoch
                if (!nested) {
                    val parent = ownLines.rect(ownLines.parent)
                    val children = (listOfNotNull(ownLines.rect(ownLines.toggle)) + ownLines.children.filterKeys { it !in ownLines.replyParents }.values.mapNotNull(ownLines::rect)).sortedBy { it.top }
                    if (parent != null && children.isNotEmpty()) {
                        val x = parent.center.x
                        path.rewind()
                        val end = children.last().center.y
                        path.moveTo(x, parent.bottom + 3.dp.toPx())
                        path.lineTo(x, end - 16.dp.toPx())
                        children.forEach { child ->
                            val y = child.center.y
                            val right = (child.left - 4.dp.toPx()).coerceAtLeast(x)
                            val radius = minOf(16.dp.toPx(), (right-x).coerceAtLeast(0f))
                            path.moveTo(x, y-radius)
                            path.quadraticTo(x, y, x+radius, y)
                            path.lineTo(right, y)
                        }
                        ownLines.replyParents.forEach { (childId, parentId) ->
                            val from = ownLines.rect(ownLines.children[parentId])
                            val to = ownLines.rect(ownLines.children[childId])
                            if (from != null && to != null && to.top > from.top) {
                                val radius = 16.dp.toPx()
                                path.moveTo(from.center.x, from.bottom + 3.dp.toPx())
                                path.lineTo(from.center.x, to.center.y - radius)
                                path.quadraticTo(from.center.x, to.center.y, from.center.x + radius, to.center.y)
                                path.lineTo(to.left - 4.dp.toPx(), to.center.y)
                            }
                        }
                    }
                }
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            onDrawBehind { drawPath(path, threadColor, style = stroke) }
        }) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (nested) Spacing.xl + Spacing.sm + (replyDepth * 20).dp else 0.dp, top = Spacing.sm, bottom = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Avatar(
            comment.authorAvatar,
            channelId = comment.authorChannelId,
            size = if (nested) Sizing.avatarSmall + 8.dp else Sizing.avatarMedium,
            modifier = Modifier
                .onGloballyPositioned { if (nested) { inherited?.children?.set(comment.id, it); inherited?.let { it.epoch++ } } else { ownLines.parent = it; ownLines.epoch++ } }
                .clip(androidx.compose.foundation.shape.CircleShape)
                .then(
                    if (authorId != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = comment.authorName) {
                        (onOpenAuthor ?: actions::openChannel)(authorId)
                    } else Modifier,
                ),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            val owner = comment.isCreator || (authorId != null && authorId == LocalCommentOwner.current)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(1f, fill = false).clip(MaterialTheme.shapes.small)
                    .then(if (owner) Modifier.background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 6.dp, vertical = 2.dp) else Modifier),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(comment.authorName, Modifier.weight(1f, fill = false).then(if (authorId != null) Modifier.clickable { (onOpenAuthor ?: actions::openChannel)(authorId) } else Modifier), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, color = if (owner) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                    if (comment.authorBadge != vibe.tube.youtube.model.ChannelBadge.None) {
                        VIconImage(if (comment.authorBadge == vibe.tube.youtube.model.ChannelBadge.Artist) VIcon.ArtistBadge else VIcon.VerifiedFilled, null, Modifier.size(14.dp))
                    } else if (owner) VIconImage(VIcon.Person, stringResource(R.string.comment_video_owner), Modifier.size(12.dp))
                }
                comment.publishedText?.let { Text(it, Modifier.widthIn(max = 120.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            // A comment in another language shows in Vietnamese, with a toggle back to what was written.
            val translator = LocalCommentTranslator.current
            var translation by remember(comment.id, comment.text) { mutableStateOf<vibe.tube.data.translate.Translation?>(null) }
            var showOriginal by androidx.compose.runtime.saveable.rememberSaveable(comment.id) { mutableStateOf(false) }
            if (translator != null && translator.needsTranslation(comment.text)) LaunchedEffect(comment.id, comment.text) {
                translation = translator.translate(comment.text)
            }
            val translated = translation?.takeIf { !showOriginal }
            Text(vibe.tube.ui.metadata.highlightMentions(vibe.tube.ui.metadata.interactiveText(translated?.text ?: comment.text, onSeek = LocalMediaSeek.current, links = if (translated == null) comment.links else emptyList()), if (vibe.tube.ui.theme.LocalClassicPalette.current) androidx.compose.ui.graphics.Color.Red else MaterialTheme.colorScheme.primary, onOpenAuthor ?: actions::openChannel), style = MaterialTheme.typography.bodyMedium)
            translation?.let {
                Text(
                    stringResource(if (showOriginal) R.string.comment_translate else R.string.comment_show_original),
                    Modifier.padding(top = 2.dp).clickable { showOriginal = !showOriginal },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                val rate = LocalRateComment.current
                val busy = comment.id in LocalBusyComments.current
                val supported = (if (comment.isLiked) comment.unlikeAction else comment.likeAction) != null
                androidx.compose.material3.TextButton(
                    onClick = { if (!signedIn) notice(R.string.msg_login_required, null) else rate(comment, false) }, enabled = (!signedIn || supported) && !busy,
                    modifier = Modifier.width(64.dp).height(40.dp).clip(androidx.compose.foundation.shape.CircleShape),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface, disabledContentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    vibe.tube.ui.components.SelectableSymbol(VIcon.ThumbUp, VIcon.ThumbUpFilled, comment.isLiked,
                        stringResource(R.string.action_like), Modifier.size(Sizing.iconSmall),
                        if (comment.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    val count = comment.likeCountText?.let { compactCount(it) ?: it }.orEmpty()
                    Text(count, Modifier.padding(start = 4.dp).width(36.dp), maxLines = 1, softWrap = false,
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall.copy(fontSize = (if (count.length >= 5) 9 else 11).sp, fontFeatureSettings = "tnum"))
                }
                val dislike = LocalDislikeComment.current
                vibe.tube.ui.components.TooltipIconButton(
                    if (comment.isDisliked) VIcon.ThumbDownFilled else VIcon.ThumbDown,
                    stringResource(R.string.action_dislike), { if (!signedIn) notice(R.string.msg_login_required, null) else dislike(comment) },
                    modifier = Modifier.size(40.dp), enabled = !busy && (!signedIn || (if (comment.isDisliked) comment.undislikeAction else comment.dislikeAction) != null),
                    animateIcon = true, iconSize = Sizing.iconSmall,
                    tint = if (comment.isDisliked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                val replyAction = LocalReplyComment.current
                if (canReply || replyAction != null) {
                    vibe.tube.ui.components.TooltipIconButton(VIcon.Reply, stringResource(R.string.comment_reply), { if (!signedIn) notice(R.string.msg_login_required, null) else input?.reply(comment) { text, done -> if (replyHandler != null) replyHandler(comment, text, done) else onReply(text, done) } }, modifier = Modifier.padding(start = 6.dp).size(40.dp), enabled = !busy, iconSize = Sizing.iconSmall)
                }
                if (comment.creatorHeart || comment.heartAction != null) {
                    androidx.compose.material3.IconButton(modifier = Modifier.size(40.dp), onClick = { notice(R.string.comment_creator_heart_notice, VIcon.HeartSmile) }) {
                        androidx.compose.foundation.layout.Box(Modifier.size(28.dp)) {
                            VIconImage(VIcon.HeartSmile, stringResource(R.string.comment_creator_heart), Modifier.size(24.dp).align(Alignment.Center),
                                if (comment.creatorHeart) vibe.tube.ui.components.WordmarkRed else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

        }
    }
    if (!nested) {
        ownLines.replyParents.clear()
        shownReplies.forEach { reply ->
            val handle = Regex("^@[^\\s]+").find(reply.text)?.value?.trimEnd(',', ':')
            val parentReply = shownReplies.firstOrNull { it.id != reply.id && handle != null && it.authorName.trimStart('@').equals(handle.trimStart('@'), ignoreCase = true) }
            if (parentReply != null) ownLines.replyParents[reply.id] = parentReply.id
            androidx.compose.runtime.key(reply.id) {
                androidx.compose.runtime.CompositionLocalProvider(LocalReplyThreadLines provides ownLines) {
                    CommentItem(reply, null, false, {}, {}, { _, _ -> }, nested = true, onOpenAuthor = onOpenAuthor, replyDepth = if (parentReply != null) 1 else 0)
                }
            }
        }
        if (comment.repliesToken != null || comment.previewReplies.isNotEmpty()) {
            TextButton(shapes = ButtonDefaults.shapes(), onClick = onToggleReplies,
                modifier = Modifier.padding(start = Sizing.avatarMedium + Spacing.md)
                    .onGloballyPositioned { ownLines.toggle = it; ownLines.epoch++ }) {
                val creator = (replies?.items.orEmpty() + comment.previewReplies).firstOrNull {
                    it.isCreator || (ownerId != null && it.authorChannelId == ownerId)
                }
                if (creator != null && replies?.expanded != true) {
                    Avatar(creator.authorAvatar, 28.dp, channelId = creator.authorChannelId, description = creator.authorName)
                    Text(" · ")
                }
                Text(if (replies?.expanded == true) stringResource(R.string.comment_hide_replies)
                    else stringResource(R.string.comment_show_replies, compactCount(comment.replyCountText).orEmpty()))
                VIconImage(if (replies?.expanded == true) VIcon.KeyboardControlKey else VIcon.ChevronRight, null, Modifier.size(20.dp).padding(top = if (replies?.expanded == true) 3.dp else 0.dp))
            }
        }
        if (replies?.expanded == true && replies.loading) {
            Row(Modifier.fillMaxWidth().padding(Spacing.md), horizontalArrangement = Arrangement.Center) { ContainedLoadingIndicator(Modifier.size(40.dp)) }
        }
        if (replies?.expanded == true && replies.failed) {
            TextButton(shapes = ButtonDefaults.shapes(), onClick = onToggleReplies) { Text(stringResource(R.string.action_retry)) }
        }
        if (replies?.expanded == true && replies.continuation != null && !replies.loading) {
            TextButton(shapes = ButtonDefaults.shapes(), onClick = onMoreReplies, modifier = Modifier.padding(start = Spacing.xl)) {
                Text(stringResource(R.string.comment_more_replies))
            }
        }
    }
    }
}

@Composable
fun CommentsTitleRow(
    state: CommentsUiState,
    onSort: (vibe.tube.youtube.model.CommentSort) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            compactCount(state.totalText)?.let { stringResource(R.string.comments_title_with_total, it) } ?: stringResource(R.string.comments_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (state.sorts.size > 1) {
            androidx.compose.foundation.layout.Box {
                Row(Modifier.sizeIn(minHeight = 48.dp).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { menu = true }.padding(horizontal = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    VIconImage(VIcon.Sort, null)
                    Text(state.sorts.firstOrNull { it.selected }?.title ?: stringResource(R.string.comments_sort), Modifier.padding(start = Spacing.sm))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                    state.sorts.forEach { sort ->
                        DropdownMenuItem(text = { Text(sort.title) }, onClick = { menu = false; onSort(sort) })
                    }
                }
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun CommentsHeader(
    state: CommentsUiState,
    authState: AuthState,
    onSort: (vibe.tube.youtube.model.CommentSort) -> Unit,
    onPost: (String, () -> Unit) -> Unit,
    onLogin: () -> Unit,
    showTitle: Boolean = true,
    showComposer: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (showTitle) CommentsTitleRow(state, onSort)
        if (showComposer && state.disabledMessage == null) {
            if (authState is AuthState.Verified) {
                if (state.createToken != null) CommentComposer(posting = state.posting, onPost = onPost)
            } else {
                OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.comment_sign_in_to_comment)) }
            }
        }
    }
}

@Composable
fun CommentComposer(
    posting: Boolean, onPost: (String, () -> Unit) -> Unit, modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.comment_add_hint),
    postLabel: String = stringResource(R.string.comment_send),
    autoFocus: Boolean = false, onDismiss: () -> Unit = {},
    draftState: androidx.compose.runtime.MutableState<String>? = null,
    focusRequestKey: Int = 0,
) {
    val localDraft = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var draft by (draftState ?: localDraft)
    var focused by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(autoFocus, focusRequestKey) { if (autoFocus) requester.requestFocus() }
    androidx.activity.compose.BackHandler(enabled = focused) { focusManager.clearFocus(); onDismiss() }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Avatar(LocalCurrentUserAvatar.current, 32.dp)
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.weight(1f).focusRequester(requester).onFocusChanged {
                    val lost = focused && !it.isFocused
                    focused = it.isFocused
                    if (lost) onDismiss()
                },
                placeholder = { androidx.compose.animation.AnimatedContent(placeholder, label = "commentHint") { hint -> Text(hint, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } }, enabled = !posting,
                trailingIcon = { androidx.compose.material3.IconButton(enabled = draft.isNotBlank() && !posting,
                    onClick = { onPost(draft) { draft = ""; focusManager.clearFocus(); onDismiss() } }) {
                    VIconImage(R.drawable.ic_send, postLabel, Modifier.size(22.dp))
                } },
                textStyle = MaterialTheme.typography.bodyMedium,
                minLines = 1, maxLines = 5, shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            )
        }

    }
}

@Composable
internal fun CommentsFooter(state: CommentsUiState, auth: AuthState, onPost: (String, () -> Unit) -> Unit, onLogin: () -> Unit) {
    if (state.disabledMessage != null) return
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = Spacing.lg, vertical = Spacing.xs)) {
            if (auth is AuthState.Verified) {
                val input = LocalCommentInput.current
                val target = input?.target
                if (state.createToken != null || target != null) CommentComposer(
                    posting = if (target != null) target.id in state.busyComments else state.posting,
                    onPost = input?.send ?: onPost,
                    placeholder = if (target == null) stringResource(R.string.comment_add_hint) else stringResource(R.string.comment_reply_hint_to, target.authorName),
                    postLabel = stringResource(R.string.comment_send), autoFocus = target != null,
                    onDismiss = { input?.dismiss() }, draftState = input?.draft, focusRequestKey = input?.focusKey ?: 0)

            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Avatar(LocalCurrentUserAvatar.current, 32.dp)
                OutlinedButton(onClick = onLogin, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.comment_sign_in_to_comment))
                }
            }
        }
    }
}

fun Modifier.clearFocusOnOutsideTap(focusManager: androidx.compose.ui.focus.FocusManager): Modifier =
    pointerInput(focusManager) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var handled = false
            while (true) {
                
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) handled = true
                if (!change.pressed) break
            }
            if (!handled) focusManager.clearFocus()
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

fun compactCount(text: String?): String? =
    vibe.tube.ui.metadata.compactViews(text, java.util.Locale.US)
