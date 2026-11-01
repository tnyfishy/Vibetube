package vibe.tube.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import vibe.tube.youtube.model.NotificationTarget
import vibe.tube.youtube.parser.FeedParser
import vibe.tube.youtube.parser.NotificationsParser

class FeedMetadataTest {
    @Test fun nativeChipKeepsItsOpaqueNavigationAndContinuation() {
        val page = FeedParser.parse(Json.parseToJsonElement("""{"header":{"chipCloudChipRenderer":{"text":{"simpleText":"Âm nhạc"},"navigationEndpoint":{"browseEndpoint":{"browseId":"FEwhat_to_watch","params":"opaque-topic"}},"isSelected":false}},"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next-page"}}}}"""))
        assertEquals("opaque-topic", page.chips.single().params)
        assertEquals("Âm nhạc", page.chips.single().title)
        assertEquals("next-page", page.continuation)
    }
    @Test fun notificationPrefersNavigationOverThumbnailAndRetainsReplyId() {
        val page = NotificationsParser.parse(Json.parseToJsonElement("""{"notificationRenderer":{"shortMessage":{"simpleText":"Reply"},"thumbnail":{"thumbnails":[{"url":"https://image.example/avatar.jpg"}]},"navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/watch?v=abcdefghijk&lc=UgParent.reply123"}},"watchEndpoint":{"videoId":"abcdefghijk"}},"notificationId":"n"}}"""))
        val target = page.items.single().target as NotificationTarget.Video
        assertEquals("UgParent.reply123", target.commentId)
        assertEquals(target, NotificationTarget.fromUrl(target.toUrl()))
    }
}
