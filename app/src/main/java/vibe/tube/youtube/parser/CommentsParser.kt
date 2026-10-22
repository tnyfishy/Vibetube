package vibe.tube.youtube.parser

import kotlinx.serialization.json.JsonElement
import vibe.tube.youtube.model.Comment
import vibe.tube.youtube.model.CommentSort
import vibe.tube.youtube.model.CommentsPage

object CommentsParser {

    fun parse(root: JsonElement): CommentsPage {
        val commentEntities = HashMap<String, JsonElement>()
        WatchParser.entityPayloads(root).forEach { payload ->
            payload.at("commentEntityPayload")?.let { e ->
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
        val replyToken = thread.path("replies", "commentRepliesRenderer").let {
            it?.firstObject("continuationItemRenderer")?.let { c -> FeedParser.tokenOf("continuationItemRenderer", c) }
        }
        thread.at("commentViewModel")?.let { vm ->
            return parseView(vm.at("commentViewModel") ?: vm, entities, thread, replyToken)
        }
        thread.at("comment")?.at("commentRenderer")?.let { return parseLegacy(it, replyToken) }
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
            publishedText = props.at("publishedTime").str,
            likeCountText = toolbar.at("likeCountNotliked").str?.takeIf { it.isNotBlank() && it != "0" },
            replyCountText = toolbar.at("replyCount").str?.takeIf { it.isNotBlank() && it != "0" },
            isReply = (props.at("replyLevel").int ?: 0) > 0,
            isCreator = author.at("isCreator").bool == true,
            isOwn = author.at("isCurrentUser").bool == true,
            isLiked = toolbarState != null && toolbarState.at("likeState").str == "TOOLBAR_LIKE_STATE_LIKED",
            repliesToken = replyToken,
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
            publishedText = renderer.at("publishedTimeText").textOf(),
            likeCountText = renderer.at("voteCount").textOf(),
            replyCountText = null,
            isReply = false,
            isCreator = renderer.at("authorIsChannelOwner").bool == true,
            isOwn = false,
            isLiked = renderer.at("isLiked").bool == true,
            repliesToken = replyToken,
        )
    }
}
