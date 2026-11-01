package vibe.tube.feature.watch

import org.junit.Assert.*
import org.junit.Test
import vibe.tube.youtube.model.Comment
import vibe.tube.youtube.model.CommentAction

class CommentRatingTest {
    private val comment = Comment("c", "a", null, null, "text", null, "5", null, false, false, false, false, null,
        likeAction = CommentAction("like"), unlikeAction = CommentAction("unlike"), dislikeAction = CommentAction("dislike"), undislikeAction = CommentAction("undislike"))
    @Test fun likeAndDislikeAreMutuallyExclusiveAndCountsAreHonest() {
        val liked = CommentRating.optimistic(comment, CommentReaction.Like)
        assertTrue(liked.isLiked); assertFalse(liked.isDisliked); assertEquals("6", liked.likeCountText)
        val disliked = CommentRating.optimistic(liked, CommentReaction.Dislike)
        assertFalse(disliked.isLiked); assertTrue(disliked.isDisliked); assertEquals("5", disliked.likeCountText)
        val reset = CommentRating.optimistic(disliked, CommentReaction.Dislike)
        assertFalse(reset.isLiked); assertFalse(reset.isDisliked); assertEquals("5", reset.likeCountText)
        assertEquals("undislike", CommentRating.action(disliked, CommentReaction.Dislike)?.params)
    }
    @Test fun compactCountsAreNotFabricatedAndMissingActionsStayUnavailable() {
        assertEquals("1.2K", CommentRating.optimistic(comment.copy(likeCountText = "1.2K"), CommentReaction.Like).likeCountText)
        assertNull(CommentRating.action(comment, CommentReaction.Heart))
    }
}
