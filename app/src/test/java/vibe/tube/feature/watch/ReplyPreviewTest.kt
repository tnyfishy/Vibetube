package vibe.tube.feature.watch

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import vibe.tube.youtube.model.Comment
import vibe.tube.youtube.parser.CommentsParser

class ReplyPreviewTest {
    private fun comment(id: String, creator: Boolean = false, channel: String? = null) =
        Comment(id, "Author", channel, null, id, null, null, null, true, creator, false, false, null)

    @Test fun creatorThenOwnRepliesPrecedeOtherReplies() {
        val creator = comment("creator", creator = true)
        val mine = comment("mine").copy(isOwn = true)
        val other = comment("other")
        assertEquals(listOf("creator", "mine", "other"), visibleThreadReplies(comment("parent"),
            RepliesState(expanded = true, items = listOf(other, mine, creator)), null).map { it.id })
    }
    @Test fun collapsedThreadHidesBodiesAndExpandedThreadPutsCreatorFirst() {
        val owner = comment("owner-reply", channel = "channel")
        val parent = comment("parent").copy(previewReplies = listOf(owner))
        val state = RepliesState(items = listOf(comment("other"), owner), previewLoaded = true)
        assertTrue(visibleThreadReplies(parent, state, "channel").isEmpty())
        assertEquals(listOf("owner-reply", "other"), visibleThreadReplies(parent, state.copy(expanded = true), "channel").map { it.id })
        assertTrue(visibleThreadReplies(parent, state.copy(expanded = false), "channel").isEmpty())
    }

    @Test fun creatorBadgeWorksWithoutChannelMetadata() {
        val creator = comment("creator", creator = true)
        val parent = comment("parent").copy(previewReplies = listOf(creator))
        assertEquals(listOf(creator), visibleThreadReplies(parent, RepliesState(expanded = true), null))
    }

    @Test fun parserKeepsInlineCreatorReplyAndContinuation() {
        val raw = """{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[
          {"commentThreadRenderer":{
            "comment":{"commentRenderer":{"commentId":"parent","authorText":{"simpleText":"Viewer"},"contentText":{"simpleText":"Question"}}},
            "replies":{"commentRepliesRenderer":{"contents":[
              {"commentRenderer":{"commentId":"creator","authorIsChannelOwner":true,"authorText":{"simpleText":"Creator"},"contentText":{"simpleText":"Answer"}}},
              {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"replies-token"}}}}
            ]}}
          }}
        ]}}]}"""
        val parent = CommentsParser.parse(Json.parseToJsonElement(raw)).comments.single()
        assertEquals("replies-token", parent.repliesToken)
        val preview = parent.previewReplies.single()
        assertTrue(preview.isCreator)
        assertTrue(preview.isReply)
        assertEquals("Answer", preview.text)
    }
}
