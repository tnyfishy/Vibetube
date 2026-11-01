package vibe.tube.youtube.parser

import kotlinx.serialization.json.JsonElement
import vibe.tube.youtube.model.Comment
import vibe.tube.youtube.model.CommentSort
import vibe.tube.youtube.model.CommentsPage

object CommentsParser {

    fun parse(root: JsonElement): CommentsPage {
        val commentEntities = HashMap<String, JsonElement>()
        WatchParser.entityPayloads(root).forEach { payload ->
            (payload as? kotlinx.serialization.json.JsonObject)?.values?.forEach { e ->
                e.at("key").str?.let { commentEntities[it] = e }
            }
        }
        val comments = ArrayList<Comment>()
        var continuation: String? = null
        var disabled: String? = null
        var createToken: String? = null
        var total: String? = null
        val sorts = ArrayList<CommentSort>()

        val actions = root.at("onResponseReceivedEndpoints").list() + root.at("onResponseReceivedActions").list()
        for (action in actions) {
            val body = action.at("reloadContinuationItemsCommand") ?: action.at("appendContinuationItemsAction") ?: continue
            for (item in body.at("continuationItems").list()) {
                item.at("commentThreadRenderer")?.let { thread ->
                    parseThread(thread, commentEntities)?.let(comments::add)
                }
                item.at("commentViewModel")?.let { vm ->
                    parseView(vm.at("commentViewModel") ?: vm, commentEntities, thread = null)?.let(comments::add)
                }
                item.at("commentRenderer")?.let { parseLegacy(it, null)?.let(comments::add) }
                item.at("continuationItemRenderer")?.let { FeedParser.tokenOf("continuationItemRenderer", it)?.let { t -> continuation = t } }
                item.at("commentsHeaderRenderer")?.let { header ->
                    total = header.at("countText").textOf() ?: header.at("titleText").textOf()
                    createToken = header.firstString("createCommentParams")
                    header.firstObject("sortFilterSubMenuRenderer").at("subMenuItems").list().forEach {
                        val token = it.firstString("token") ?: return@forEach
                        sorts += CommentSort(it.at("title").textOf().orEmpty(), token, it.at("selected").bool == true)
                    }
                }
                item.at("messageRenderer")?.let { disabled = it.at("text").textOf() }
            }
        }
        return CommentsPage(
            comments = comments,
            continuation = continuation,
            sorts = sorts,
            disabledMessage = if (comments.isEmpty()) disabled else null,
            totalText = total,
            createToken = createToken,
        )
    }

    private fun parseThread(thread: JsonElement, entities: Map<String, JsonElement>): Comment? {
        val previewReplies = thread.path("replies", "commentRepliesRenderer", "contents").list().mapNotNull { item ->
            item.at("commentRenderer")?.let { parseLegacy(it, null) }
                ?: item.at("commentViewModel")?.let { vm -> parseView(vm.at("commentViewModel") ?: vm, entities, null) }
        }.map { it.copy(isReply = true) }.distinctBy { it.id }
        val replyToken = thread.path("replies", "commentRepliesRenderer").let {
            it?.firstObject("continuationItemRenderer")?.let { c -> FeedParser.tokenOf("continuationItemRenderer", c) }
        }
        thread.at("commentViewModel")?.let { vm ->
            return parseView(vm.at("commentViewModel") ?: vm, entities, thread, replyToken)?.let { it.copy(replyParams = thread.firstString("createReplyParams") ?: it.replyParams, previewReplies = previewReplies) }
        }
        thread.at("comment")?.at("commentRenderer")?.let { return parseLegacy(it, replyToken)?.let { c -> c.copy(replyParams = thread.firstString("createReplyParams") ?: c.replyParams, previewReplies = previewReplies) } }
        return null
    }

    private fun parseView(
        vm: JsonElement,
        entities: Map<String, JsonElement>,
        thread: JsonElement?,
        replyToken: String? = null,
    ): Comment? {
        val key = vm.at("commentKey").str ?: return null
        val entity = entities[key] ?: return null
        val props = entity.at("properties")
        val author = entity.at("author")
        val toolbar = entity.at("toolbar")
        val id = props.at("commentId").str ?: vm.at("commentId").str ?: return null
        val toolbarState = vm.at("toolbarStateKey").str?.let { k ->
            entities[k]
        }
        return Comment(
            id = id,
            authorName = author.at("displayName").str.orEmpty(),
            authorChannelId = author.at("channelId").str,
            authorAvatar = author.at("avatarThumbnailUrl").str,
            text = props.at("content").at("content").str.orEmpty(),
            links = props.at("content").textLinks(),
            publishedText = props.at("publishedTime").str,
            likeCountText = (if (toolbarState.at("likeState").str == "TOOLBAR_LIKE_STATE_LIKED") toolbar.at("likeCountLiked").str ?: toolbar.at("likeCountNotliked").str else toolbar.at("likeCountNotliked").str)?.takeIf { it.isNotBlank() && it != "0" },
            replyCountText = toolbar.at("replyCount").str?.takeIf { it.isNotBlank() && it != "0" },
            isReply = (props.at("replyLevel").int ?: 0) > 0,
            isCreator = author.at("isCreator").bool == true,
            authorBadge = when {
                author.at("isOfficialArtist").bool == true -> vibe.tube.youtube.model.ChannelBadge.Artist
                author.at("isVerified").bool == true -> vibe.tube.youtube.model.ChannelBadge.Verified
                else -> vibe.tube.youtube.model.ChannelBadge.None
            },
            isOwn = author.at("isCurrentUser").bool == true,
            isLiked = toolbarState != null && toolbarState.at("likeState").str == "TOOLBAR_LIKE_STATE_LIKED",
            repliesToken = replyToken,
            creatorHeart = toolbarState.at("heartState").str == "TOOLBAR_HEART_STATE_HEARTED",
            likeAction = action(vm.firstObject("likeCommand")) ?: action(entities[vm.at("toolbarSurfaceKey").str].firstObject("likeCommand")),
            unlikeAction = action(vm.firstObject("unlikeCommand")) ?: action(entities[vm.at("toolbarSurfaceKey").str].firstObject("unlikeCommand")),
            heartAction = action(entities[vm.at("toolbarSurfaceKey").str].firstObject("heartCommand")),
            unheartAction = action(entities[vm.at("toolbarSurfaceKey").str].firstObject("unheartCommand")),
            isDisliked = toolbarState.at("likeState").str == "TOOLBAR_LIKE_STATE_DISLIKED",
            dislikeAction = action(vm.firstObject("dislikeCommand")) ?: action(entities[vm.at("toolbarSurfaceKey").str].firstObject("dislikeCommand")),
            undislikeAction = action(vm.firstObject("undislikeCommand")) ?: action(entities[vm.at("toolbarSurfaceKey").str].firstObject("undislikeCommand")),
            creatorHeartAvatar = toolbar.at("creatorThumbnailUrl").str ?: toolbarState.at("creatorThumbnailUrl").str,
            replyParams = entities[vm.at("toolbarSurfaceKey").str].firstObject("replyCommand").firstString("createReplyParams")
                ?: entities[vm.at("toolbarSurfaceKey").str].firstString("createReplyParams")
                ?: entities[vm.at("commentSurfaceKey").str].firstString("createReplyParams")
                ?: thread?.firstString("createReplyParams") ?: vm.firstString("createReplyParams"),
        )
    }

    private fun parseLegacy(renderer: JsonElement, replyToken: String?): Comment? {
        val id = renderer.at("commentId").str ?: return null
        return Comment(
            id = id,
            authorName = renderer.at("authorText").textOf().orEmpty(),
            authorChannelId = renderer.path("authorEndpoint", "browseEndpoint", "browseId").str,
            authorAvatar = ItemParsers.thumbs(renderer.at("authorThumbnail")).lastOrNull()?.url,
            text = renderer.at("contentText").textOf().orEmpty(),
            links = renderer.at("contentText").textLinks(),
            publishedText = renderer.at("publishedTimeText").textOf(),
            likeCountText = renderer.at("voteCount").textOf(),
            replyCountText = null,
            isReply = false,
            isCreator = renderer.at("authorIsChannelOwner").bool == true,
            isOwn = false,
            authorBadge = when (renderer.firstObject("authorCommentBadgeRenderer").at("icon").at("iconType").str) {
                "OFFICIAL_ARTIST_BADGE" -> vibe.tube.youtube.model.ChannelBadge.Artist
                "CHECK_CIRCLE_THICK", "CHECK_CIRCLE" -> vibe.tube.youtube.model.ChannelBadge.Verified
                else -> vibe.tube.youtube.model.ChannelBadge.None
            },
            isLiked = renderer.at("isLiked").bool == true,
            repliesToken = replyToken,
            creatorHeart = renderer.firstObject("creatorHeartRenderer") != null,
            likeAction = action(renderer.firstObject("likeButton").firstObject("defaultServiceEndpoint")),
            unlikeAction = action(renderer.firstObject("likeButton").firstObject("toggledServiceEndpoint")),
            heartAction = action(renderer.firstObject("creatorHeartRenderer").firstObject("unheartedServiceEndpoint")),
            unheartAction = action(renderer.firstObject("creatorHeartRenderer").firstObject("heartedServiceEndpoint")),
            isDisliked = renderer.at("isDisliked").bool == true,
            dislikeAction = action(renderer.firstObject("dislikeButton").firstObject("defaultServiceEndpoint")),
            undislikeAction = action(renderer.firstObject("dislikeButton").firstObject("toggledServiceEndpoint")),
            creatorHeartAvatar = ItemParsers.thumbs(renderer.firstObject("creatorHeartRenderer").at("creatorThumbnail")).lastOrNull()?.url,
            replyParams = renderer.firstString("createReplyParams"),
        )
    }

    private fun action(endpoint: JsonElement?): vibe.tube.youtube.model.CommentAction? {
        val command = endpoint.firstObject("performCommentActionEndpoint") ?: return null
        val token = command.at("action").str?.takeIf { it.isNotBlank() } ?: return null
        return vibe.tube.youtube.model.CommentAction(token)
    }
}
