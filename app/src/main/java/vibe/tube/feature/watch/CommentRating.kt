package vibe.tube.feature.watch

import vibe.tube.youtube.model.Comment
import vibe.tube.youtube.model.CommentAction

internal enum class CommentReaction { Like, Dislike, Heart }

internal object CommentRating {
    fun action(c: Comment, reaction: CommentReaction): CommentAction? = when (reaction) {
        CommentReaction.Like -> if (c.isLiked) c.unlikeAction else c.likeAction
        CommentReaction.Dislike -> if (c.isDisliked) c.undislikeAction else c.dislikeAction
        CommentReaction.Heart -> if (c.creatorHeart) c.unheartAction else c.heartAction
    }
    fun optimistic(c: Comment, reaction: CommentReaction): Comment {
        if (reaction == CommentReaction.Heart) return c.copy(creatorHeart = !c.creatorHeart)
        val liked = reaction == CommentReaction.Like && !c.isLiked
        val delta = (if (liked) 1 else 0) - (if (c.isLiked) 1 else 0)
        val count = c.likeCountText?.replace(",", "")?.toLongOrNull()?.let { (it + delta).coerceAtLeast(0).toString() }
            ?: if (liked && c.likeCountText.isNullOrBlank()) "1" else c.likeCountText
        return c.copy(isLiked = liked, isDisliked = reaction == CommentReaction.Dislike && !c.isDisliked, likeCountText = count)
    }
}
