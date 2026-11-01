package vibe.tube.ui.metadata

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayMetadataTest {
    @Test fun viewsUseLocalizedCompactUnits() {
        val vi = Locale.forLanguageTag("vi-VN")
        assertEquals("600N", compactViews("600000", vi))
        assertEquals("6Tr", compactViews("6000000", vi))
        assertEquals("6Tr", compactViews("6,000,000 views", vi))
        assertEquals("1.2Tr", compactViews("1.2M views", vi))
        assertEquals("1.2Tr", compactViews("1,2 triệu lượt xem", vi))
        assertEquals("600K", compactViews("600000", Locale.US))
        assertEquals("999", compactViews("999", vi))
        assertNull(compactViews(null, vi))
    }
    @Test fun linkedYouTubeTimestampsSeekInsteadOfOpeningAnExternalPage() {
        val value = interactiveText("<a href=\"https://www.youtube.com/watch?v=id&amp;t=83\">1:23</a>", {})
        assertEquals("seek:83", (value.getLinkAnnotations(0, value.length).single().item as androidx.compose.ui.text.LinkAnnotation.Clickable).tag)
    }
    @Test fun expandedViewsIncludeUnitsAndLocalizedLabel() {
        assertEquals("1.1 Tr lượt xem", detailedViews("1100000", Locale.forLanguageTag("vi-VN")))
        assertEquals("3 N lượt xem", detailedViews("3000", Locale.forLanguageTag("vi-VN")))
        assertEquals("7 T lượt xem", detailedViews("7000000000", Locale.forLanguageTag("vi-VN")))
        assertEquals("1.1 M views", detailedViews("1100000", Locale.US))
    }
    @Test fun plainTextLinksAndTimestampsAreInteractiveWithoutLosingNewlines() {
        val value = interactiveText("Start 1:23\nhttps://example.com/watch?t=2:34 then 1:02:03", {})
        val links = value.getLinkAnnotations(0, value.length)
        assertEquals(3, links.size)
        assertTrue(value.text.contains("\n"))
        assertTrue(links.any { (it.item as? androidx.compose.ui.text.LinkAnnotation.Clickable)?.tag == "seek:83" })
        assertTrue(links.any { (it.item as? androidx.compose.ui.text.LinkAnnotation.Clickable)?.tag == "seek:3723" })
    }
    @Test fun commentTotalsIgnoreTheLocalizedLabelAndKeepOneDecimal() {
        assertEquals("1.2K", vibe.tube.feature.watch.compactCount("1.234 bình luận"))
        assertEquals("23.4K", vibe.tube.feature.watch.compactCount("23400"))
        assertEquals("1.2K", vibe.tube.feature.watch.compactCount("1,2 N bình luận"))
        assertEquals("999", vibe.tube.feature.watch.compactCount("999 comments"))
    }
    @Test fun formattedDateUsesTheDatesOriginalOffset() {
        val value = displayDate("2025-07-29T06:00:26-07:00", Locale.US)!!
        assertTrue(value.contains("Jul")); assertTrue(value.contains("29")); assertFalse(value.contains("T06:"))
        assertEquals("unknown", displayDate("unknown", Locale.US))
    }
    @Test fun numericCountsAreGroupedWithoutInventingPrecision() {
        assertEquals("28,383,888", displayCount("28383888", Locale.US))
        assertEquals("2.1M", displayCount("2.1M", Locale.US))
    }
    @Test fun htmlProducesTextAndOnlySafeLinks() {
        val value = descriptionText("Xin chào<br>Nhạc &amp; <a href=\"https://example.com/a\">nghe 🎵</a> <a href=\"javascript:alert(1)\">bad</a>")
        assertTrue(value.text.contains("Xin chào\nNhạc & nghe 🎵")); assertFalse(value.text.contains("<br>"))
        assertEquals(1, value.getLinkAnnotations(0, value.length).size)
    }
}
