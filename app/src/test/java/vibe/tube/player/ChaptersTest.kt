package vibe.tube.player

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.youtube.model.VideoChapter
import vibe.tube.youtube.parser.WatchParser

class ChaptersTest {

    private fun titles(c: List<Chapter>) = c.map { it.title }
    private fun starts(c: List<Chapter>) = c.map { it.startMs / 1000 }

    @Test
    fun classicListParses() {
        val c = Chapters.parse("Hello\n0:00 Intro\n1:30 Verse\n3:05 Chorus\n1:02:03 End")
        assertEquals(listOf("Intro", "Verse", "Chorus", "End"), titles(c))
        assertEquals(listOf(0L, 90L, 185L, 3723L), starts(c))
    }

    @Test
    fun timestampAfterTheTitleAndInBracketsCount() {
        assertEquals(listOf("Intro", "Main part", "Outro (Live)"), titles(Chapters.parse("Intro - 0:00\nMain part – 2:00\nOutro (Live) | 4:10")))
        assertEquals(listOf("Intro", "Setup", "Demo"), titles(Chapters.parse("[0:00] Intro\n(01:00) Setup\n• 02:30 Demo")))
        assertEquals(listOf("Intro", "Part", "End"), titles(Chapters.parse("00:00:00 Intro\n00:01:00 Part\n00:02:00 End")))
    }

    @Test
    fun aStrayTimestampBeforeTheListDoesNotHideIt() {
        val text = "The best bit is at 3:20, don't miss it!\n\nChapters:\n0:00 Start\n0:45 Middle\n2:10 Finish\n\nFollow me"
        assertEquals(listOf("Start", "Middle", "Finish"), titles(Chapters.parse(text)))
    }

    @Test
    fun theListEndsWhereTimestampsStopAscending() {
        val text = "0:00 A\n1:00 B\n2:00 C\n\nAlso: 0:30 replay of A"
        assertEquals(listOf("A", "B", "C"), titles(Chapters.parse(text)))
    }

    @Test
    fun invalidListsGiveNoChaptersRatherThanInventedOnes() {
        assertTrue(Chapters.parse("1:00 A\n2:00 B\n3:00 C").isEmpty())
        assertTrue(Chapters.parse("0:00 A\n1:00 B").isEmpty())
        assertTrue(Chapters.parse("0:00 A\n0:05 B\n1:00 C").isEmpty())
        assertTrue(Chapters.parse(null).isEmpty())
        // Timestamps past the end of the video are not chapters.
        assertEquals(listOf("A", "B", "C"), titles(Chapters.parse("0:00 A\n1:00 B\n2:00 C\n9:00 D", durationMs = 300_000)))
    }

    @Test
    fun youtubeMarkersWinOverTheDescription() {
        val marked = listOf(VideoChapter(0, "Auto one"), VideoChapter(60_000, "Auto two"))
        assertEquals(listOf("Auto one", "Auto two"), titles(Chapters.of(marked, "0:00 A\n1:00 B\n2:00 C")))
        assertEquals(listOf("A", "B", "C"), titles(Chapters.of(emptyList(), "0:00 A\n1:00 B\n2:00 C")))
    }

    @Test
    fun watchPageMarkersAreRead() {
        val json = Json.parseToJsonElement(
            """{"playerOverlays":{"playerOverlayRenderer":{"decoratedPlayerBarRenderer":{"decoratedPlayerBarRenderer":{"playerBar":
            {"multiMarkersPlayerBarRenderer":{"markersMap":[
              {"key":"HEATSEEKER","value":{"heatmap":{}}},
              {"key":"AUTO_CHAPTERS","value":{"chapters":[
                {"chapterRenderer":{"title":{"simpleText":"Opening"},"timeRangeStartMillis":0}},
                {"chapterRenderer":{"title":{"simpleText":"Tutorial"},"timeRangeStartMillis":95000}},
                {"chapterRenderer":{"title":{"simpleText":"Wrap-up"},"timeRangeStartMillis":"600000"}}]}}]}}}}}}}""",
        )
        val chapters = WatchParser.chapters(json)
        assertEquals(listOf("Opening", "Tutorial", "Wrap-up"), chapters.map { it.title })
        assertEquals(listOf(0L, 95_000L, 600_000L), chapters.map { it.startMs })

        val panel = Json.parseToJsonElement(
            """{"engagementPanels":[{"engagementPanelSectionListRenderer":{"content":{"macroMarkersListRenderer":{"contents":[
              {"macroMarkersListItemRenderer":{"title":{"simpleText":"One"},"onTap":{"watchEndpoint":{"startTimeSeconds":0}}}},
              {"macroMarkersListItemRenderer":{"title":{"simpleText":"Two"},"onTap":{"watchEndpoint":{"startTimeSeconds":42}}}}]}}}}]}""",
        )
        assertEquals(listOf(0L, 42_000L), WatchParser.chapters(panel).map { it.startMs })
        assertTrue(WatchParser.chapters(Json.parseToJsonElement("{}")).isEmpty())
    }
}
