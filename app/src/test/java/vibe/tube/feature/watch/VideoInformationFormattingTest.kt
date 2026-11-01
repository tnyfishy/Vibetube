package vibe.tube.feature.watch

import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import vibe.tube.youtube.model.TextLink
import vibe.tube.ui.metadata.interactiveText

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoInformationFormattingTest {
    @Test fun publicationDateRemovesPremierePrefix() {
        assertEquals("28 thg 5, 2026", shortPublicationDate("Đã công chiếu vào 28 thg 5, 2026", Locale.forLanguageTag("vi-VN")))
        assertEquals("May 28, 2026", shortPublicationDate("Premiered on May 28, 2026", Locale.US))
    }
    @Test fun socialIconsKeepOriginalLinkAndSeekAnnotations() {
        val text = "At 1:23 /mtp.fan"
        val url = "https://www.youtube.com/redirect?q=https%3A%2F%2Ffacebook.com%2Fmtp.fan"
        val base = interactiveText(text, {}, listOf(TextLink(8, text.length, url)))
        val (decorated, icons) = socialDescription(base)
        assertEquals(1, icons.size)
        val links = decorated.getLinkAnnotations(0, decorated.length)
        assertTrue(links.any { (it.item as? LinkAnnotation.Url)?.url == url })
        assertTrue(links.any { (it.item as? LinkAnnotation.Clickable)?.tag == "seek:83" })
    }
}
