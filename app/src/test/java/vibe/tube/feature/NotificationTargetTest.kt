package vibe.tube.feature

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vibe.tube.notifications.NewVideoNotifier
import vibe.tube.youtube.model.NotificationItem
import vibe.tube.youtube.model.NotificationTarget
import vibe.tube.youtube.parser.NotificationsParser

/** A notification leads to exactly what it is about, and that survives the trip through an Intent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationTargetTest {

    @Test fun commentInteractionWithoutAnIdStillOpensTheCommentsAndSurvivesAnIntent() {
        val item = NotificationsParser.parse(page("""{"notificationId":"reply","shortMessage":{"simpleText":"Creator hearted your comment"},"navigationEndpoint":{"watchEndpoint":{"videoId":"dQw4w9WgXcQ"}}}""")).items.single()
        val target = item.target as NotificationTarget.Video
        org.junit.Assert.assertTrue(target.openComments)
        assertEquals(target, NotificationTarget.fromUrl(target.toUrl()))
    }

    private fun page(renderer: String): JsonElement = Json.parseToJsonElement(
        """{"contents":[{"notificationRenderer":$renderer}]}""",
    )

    @Test
    fun linksAreReadWithTheirTimeCommentAndPlaylist() {
        assertEquals(
            NotificationTarget.Video("dQw4w9WgXcQ", 83, "PLabc", "UgxCOMMENT"),
            NotificationTarget.fromUrl("/watch?v=dQw4w9WgXcQ&t=1m23s&list=PLabc&lc=UgxCOMMENT"),
        )
        assertEquals(NotificationTarget.Video("dQw4w9WgXcQ", 42), NotificationTarget.fromUrl("https://youtu.be/dQw4w9WgXcQ?t=42"))
        assertEquals(NotificationTarget.Video("dQw4w9WgXcQ"), NotificationTarget.fromUrl("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(NotificationTarget.Channel("UC1234567890"), NotificationTarget.fromUrl("/channel/UC1234567890/videos"))
        assertEquals(NotificationTarget.Playlist("PLxyz"), NotificationTarget.fromUrl("https://www.youtube.com/playlist?list=PLxyz"))
        assertNull(NotificationTarget.fromUrl("/watch?v=short"))
        assertNull(NotificationTarget.fromUrl("/feed/history"))
        assertEquals(3723, NotificationTarget.parseSeconds("1h2m3s"))
        assertNull(NotificationTarget.parseSeconds("soon"))
    }

    @Test
    fun theParserTakesTheEndpointFirstAndTheLinkForTheComment() {
        val item = NotificationsParser.parse(
            page(
                """{"notificationId":"n1","shortMessage":{"simpleText":"New reply"},
                "navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/watch?v=dQw4w9WgXcQ&lc=UgyREPLY"}},
                "watchEndpoint":{"videoId":"dQw4w9WgXcQ","startTimeSeconds":95}}}""",
            ),
        ).items.single()
        assertEquals(NotificationTarget.Video("dQw4w9WgXcQ", 95, null, "UgyREPLY"), item.target)

        val channel = NotificationsParser.parse(
            page(
                """{"notificationId":"n2","shortMessage":{"simpleText":"Channel update"},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"UCchannel0001","canonicalBaseUrl":"/@someone"}}}""",
            ),
        ).items.single()
        assertEquals(NotificationTarget.Channel("UCchannel0001"), channel.target)
    }

    @Test
    fun eachSystemNotificationCarriesItsOwnTarget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = NotificationTarget.Video("dQw4w9WgXcQ", 30, null, "UgxC")
        val item = NotificationItem("n1", "New video", null, null, "https://i.ytimg.com/x.jpg", "dQw4w9WgXcQ", null, false, target)
        val intent = NewVideoNotifier.openIntent(context, item)
        assertEquals(NewVideoNotifier.ACTION_OPEN_TARGET, intent.action)
        assertEquals(target, NotificationTarget.fromUrl(intent.dataString))
        assertEquals("New video", intent.getStringExtra(NewVideoNotifier.EXTRA_TITLE))
        // Without a target it still opens the Notifications list.
        val plain = NewVideoNotifier.openIntent(context, item.copy(target = null))
        assertEquals(NewVideoNotifier.ACTION_OPEN_NOTIFICATIONS, plain.action)
    }
}
