package vibe.tube.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import vibe.tube.youtube.parser.ChannelAboutParser

class ChannelAboutTest {
    @Test fun fullDescriptionMetadataAndSafeExternalLinksArePreserved() {
        val raw = """{"aboutChannelViewModel":{"description":"Full first line\nFull second line","country":"Vietnam",
          "joinedDateText":{"content":"Joined 2020"},"viewCountText":"6000000 views",
          "links":[{"channelExternalLinkViewModel":{"title":{"content":"Website"},"link":{"urlEndpoint":{"url":"https://example.com"}}}},
          {"channelExternalLinkViewModel":{"title":"Unsafe","urlEndpoint":{"url":"javascript:alert(1)"}}}]}}"""
        val about = ChannelAboutParser.parse(Json.parseToJsonElement(raw), "UCtest")
        assertEquals("Full first line\nFull second line", about.description)
        assertTrue(about.aboutDetails.contains("country" to "Vietnam"))
        assertEquals(listOf("Website" to "https://example.com"), about.links)
    }
}
