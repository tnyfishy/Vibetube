package vibe.tube.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import vibe.tube.youtube.parser.CommentsParser

class ReplySurfaceTest {
    @Test fun readsReplyDialogFromItsOwnToolbarEntity() {
        val page = CommentsParser.parse(Json.parseToJsonElement("""{
          "onResponseReceivedEndpoints":[{"reloadContinuationItemsCommand":{"continuationItems":[
            {"commentThreadRenderer":{"commentViewModel":{"commentViewModel":{"commentKey":"comment1","toolbarSurfaceKey":"toolbar1"}}}}
          ]}}],
          "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
            {"payload":{"commentEntityPayload":{"key":"comment1","properties":{"commentId":"parent1","content":{"content":"hello"}},"author":{"displayName":"Author"}}}},
            {"payload":{"commentToolbarSurfaceEntityPayload":{"key":"toolbar1","replyCommand":{"createCommentReplyDialogEndpoint":{"dialog":{"commentReplyDialogRenderer":{"replyButton":{"buttonRenderer":{"serviceEndpoint":{"createCommentReplyEndpoint":{"createReplyParams":"opaque-parent1-token"}}}}}}}}}}}
          ]}}
        }"""))
        assertEquals("opaque-parent1-token", page.comments.single().replyParams)
    }
}
