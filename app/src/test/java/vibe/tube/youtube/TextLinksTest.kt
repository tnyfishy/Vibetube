package vibe.tube.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import vibe.tube.youtube.parser.textLinks

class TextLinksTest {
    @Test fun legacyRunsKeepTheFullTargetBehindTheLabel() {
        val text = Json.parseToJsonElement("""{"runs":[{"text":"Visit "},{"text":"website","navigationEndpoint":{"urlEndpoint":{"url":"https://example.com/full/path"}}}]}""")
        val link = text.textLinks().single()
        assertEquals(6, link.start)
        assertEquals(13, link.end)
        assertEquals("https://example.com/full/path", link.url)
    }

    @Test fun commandRunsRetainSafeTargetsAndRejectInvalidRanges() {
        val text = Json.parseToJsonElement("""{"content":"Read here", "commandRuns":[
          {"startIndex":5,"length":4,"onTap":{"innertubeCommand":{"urlEndpoint":{"url":"https://example.com"}}}},
          {"startIndex":0,"length":4,"onTap":{"urlEndpoint":{"url":"javascript:alert(1)"}}},
          {"startIndex":90,"length":4,"onTap":{"urlEndpoint":{"url":"https://example.org"}}}
        ]}""")
        assertEquals(1, text.textLinks().size)
        assertEquals(5, text.textLinks().single().start)
    }
}
