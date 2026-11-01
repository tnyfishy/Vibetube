package vibe.tube.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.feature.watch.CommentComposer
import vibe.tube.feature.watch.CommentsHeader
import vibe.tube.feature.watch.CommentsTitleRow
import vibe.tube.feature.watch.CommentsUiState
import vibe.tube.feature.watch.clearFocusOnOutsideTap

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CommentsUiTest {

    @get:Rule val rule = createComposeRule()

    @Test fun reactionIconsStayAlignedWithAndWithoutLikeCounts() {
        val comment = vibe.tube.youtube.model.Comment("empty", "Viewer", null, null, "Question", null, null, null,
            false, false, false, false, null)
        rule.setContent {
            Column {
                vibe.tube.feature.watch.CommentItem(comment, null, true, {}, {}, { _, _ -> })
                vibe.tube.feature.watch.CommentItem(comment.copy(id = "count", likeCountText = "1"), null, true, {}, {}, { _, _ -> })
            }
        }
        for (label in listOf("Like", "Dislike", "Reply")) {
            val nodes = rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(label)).fetchSemanticsNodes()
            assertEquals(label, 2, nodes.size)
            assertEquals(label, nodes[0].boundsInRoot.center.x, nodes[1].boundsInRoot.center.x, 1f)
        }
    }

    @Test fun footerStaysAtTheBottomWhileCommentsScroll() {
        rule.setContent {
            Column(Modifier.height(600.dp)) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                    items(80) { Text("Row $it", Modifier.height(50.dp)) }
                }
                vibe.tube.feature.watch.CommentsFooter(CommentsUiState(createToken = "token"),
                    vibe.tube.account.AuthState.Verified("test"), { _, _ -> }, {})
            }
        }
        val field = rule.onNode(hasSetTextAction())
        val before = field.fetchSemanticsNode().boundsInRoot
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToIndex(50)
        val after = field.fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 1f)
        field.performClick()
        rule.onNodeWithContentDescription("Send").assertExists()
    }

    @Test fun creatorReplyBodyIsHiddenUntilExpandedThenAppearsFirst() {
        val creator = vibe.tube.youtube.model.Comment("creator", "Channel owner", null, null, "Creator answer", null,
            null, null, true, true, false, false, null)
        val parent = creator.copy(id = "parent", authorName = "Viewer", text = "Question", isCreator = false,
            isReply = false, repliesToken = "token", previewReplies = listOf(creator))
        val other = creator.copy(id = "other", text = "Other answer", isCreator = false)
        rule.setContent {
            val expanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            vibe.tube.feature.watch.CommentItem(parent,
                vibe.tube.feature.watch.RepliesState(items = listOf(other, creator), previewLoaded = true, expanded = expanded.value),
                false, { expanded.value = !expanded.value }, {}, { _, _ -> })
        }
        assertEquals(0, rule.onAllNodesWithText("Creator answer").fetchSemanticsNodes().size)
        rule.onNodeWithText("Show", substring = true).assertExists()
        rule.onNodeWithContentDescription("Channel owner").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Other answer").fetchSemanticsNodes().size)
        rule.onNodeWithText("Show", substring = true).performClick()
        rule.onNodeWithContentDescription("Channel owner").assertDoesNotExist()
        val first = rule.onNodeWithText("Creator answer").fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithText("Other answer").fetchSemanticsNode().boundsInRoot
        assertTrue(first.bottom <= second.top)
    }

    @Test fun guestActionsWarnAndCreatorHeartShowsAnInformationalIconToast() {
        val comment = vibe.tube.youtube.model.Comment("c", "Viewer", null, null, "Question", null, null, null,
            false, false, false, false, null, creatorHeart = true)
        val notices = mutableListOf<Pair<Int, Int?>>()
        var writes = 0
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                vibe.tube.feature.watch.LocalCommentSignedIn provides false,
                vibe.tube.feature.watch.LocalCommentNotice provides { res, icon -> notices += res to icon },
                vibe.tube.feature.watch.LocalRateComment provides { _, _ -> writes++ },
                vibe.tube.feature.watch.LocalDislikeComment provides { _ -> writes++ },
                vibe.tube.feature.watch.LocalReplyComment provides { _, _, _ -> writes++ },
            ) { vibe.tube.feature.watch.CommentItem(comment, null, false, {}, {}, { _, _ -> }) }
        }
        rule.onNodeWithContentDescription("Like").performClick()
        rule.onNodeWithContentDescription("Dislike").performClick()
        rule.onNodeWithContentDescription("Reply").performClick()
        assertEquals(3, notices.count { it.first == vibe.tube.R.string.msg_login_required })
        assertEquals(0, writes)
        rule.onNodeWithContentDescription("Hearted by the creator").performClick()
        assertEquals(vibe.tube.R.string.comment_creator_heart_notice to vibe.tube.ui.VIcon.HeartSmile, notices.last())
    }

    @Test
    fun replyIconOpensComposerAndTargetsTheNestedComment() {
        val target = vibe.tube.youtube.model.Comment("child", "Reply Author", null, null, "Child text", null, null, null,
            true, false, false, false, null, replyParams = "opaque-reply-token")
        val sent = mutableListOf<String>()
        rule.setContent {
            val input = androidx.compose.runtime.remember { vibe.tube.feature.watch.CommentInputState() }
            androidx.compose.runtime.CompositionLocalProvider(
                vibe.tube.feature.watch.LocalCommentInput provides input,
                vibe.tube.feature.watch.LocalReplyComment provides { c, text, done -> sent += c.id + ":" + text; done() },
            ) {
                Column {
                    vibe.tube.feature.watch.CommentItem(target, null, false, {}, {}, { _, _ -> }, nested = true)
                    vibe.tube.feature.watch.CommentsFooter(CommentsUiState(createToken = "token"), vibe.tube.account.AuthState.Verified("test"), { _, _ -> }, {})
                }
            }
        }
        rule.onNodeWithContentDescription("Reply").performClick()
        rule.onNodeWithText("Reply to Reply Author").assertExists()
        assertEquals(1, rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        rule.onNode(hasSetTextAction()).performTextInput("Hello there")
        rule.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("child:Hello there"), sent)
    }

    @Test
    fun repliesOccupySeparateMeasuredRowsInsideBoxHost() {
        fun c(id: String, text: String) = vibe.tube.youtube.model.Comment(id, "Author", null, null, text, null,
            "1", null, false, false, false, false, null)
        val parent = c("parent", "Parent comment")
        val reply1 = c("reply1", "Trả lời tiếng Việt với emoji 🎉 và nhiều dòng\nDòng thứ hai.")
        val reply2 = c("reply2", "@Author Another reply")
        rule.setContent {
            vibe.tube.ui.theme.VibetubeTheme(vibe.tube.data.settings.ThemeMode.Light, false) {
                Box(Modifier.fillMaxWidth()) {
                    vibe.tube.feature.watch.CommentItem(parent,
                        vibe.tube.feature.watch.RepliesState(items = listOf(reply1, reply2), expanded = true),
                        false, {}, {}, { _, _ -> })
                }
            }
        }
        val a = rule.onNodeWithText(parent.text).fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithText(reply1.text).fetchSemanticsNode().boundsInRoot
        val c = rule.onNodeWithText(reply2.text).fetchSemanticsNode().boundsInRoot
        assertTrue("reply1 below parent", b.top >= a.bottom)
        assertTrue("reply2 below reply1", c.top >= b.bottom)
        assertTrue("reply to reply is indented", c.left > b.left)
    }

    @Test
    fun tappingOutsideFoldsTheComposerAndKeepsTheDraft() {
        val posted = mutableListOf<String>()
        rule.setContent {
            val fm = LocalFocusManager.current
            Column(Modifier.clearFocusOnOutsideTap(fm)) {
                CommentComposer(posting = false, onPost = { text, done -> posted += text; done() })
                Box(Modifier.fillMaxWidth().height(200.dp).testTag("outside")) { Text("A comment") }
            }
        }
        val field = rule.onNode(hasSetTextAction())
        field.performClick()
        field.assertIsFocused()
        rule.onNodeWithContentDescription("Send").assertExists()
        field.performTextInput("half written")
        rule.onNodeWithTag("outside").performClick()
        rule.waitForIdle()
        field.assertIsNotFocused()
        rule.onNodeWithContentDescription("Send").assertExists()
        rule.onNodeWithText("half written").assertExists()
        assertTrue("nothing is sent by leaving", posted.isEmpty())
        // It opens again as before.
        field.performClick()
        field.assertIsFocused()
        rule.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("half written"), posted)
    }

    @Test
    fun aSheetWithItsOwnTitleShowsTheTitleOnce() {
        val state = CommentsUiState(totalText = "12")
        rule.setContent {
            Column {
                CommentsTitleRow(state, onSort = {})
                CommentsHeader(state, vibe.tube.account.AuthState.Guest, onSort = {}, onPost = { _, _ -> }, onLogin = {}, showTitle = false)
            }
        }
        rule.onAllNodesWithText("Comments", substring = true).assertCountEquals(1)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) {
    assertEquals(n, fetchSemanticsNodes().size)
}
