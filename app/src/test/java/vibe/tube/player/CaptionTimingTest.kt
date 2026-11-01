package vibe.tube.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionTimingTest {

    /** The shape of YouTube's automatic (speech-recognised) WebVTT: a blank-ish first line, then timed words. */
    private val asr = """WEBVTT
Kind: captions
Language: en

00:00:00.000 --> 00:00:02.149 align:start position:0%
 
we're<00:00:00.240><c> going</c><00:00:00.480><c> to</c>

00:00:02.149 --> 00:00:02.159 align:start position:0%
we're going to
 

00:00:02.159 --> 00:00:04.789 align:start position:0%
we're going to
talk<00:00:02.399><c> about</c><00:00:03.000><c> captions</c>
"""

    @Test
    fun automaticCaptionsRevealEachWordWhenItIsSpoken() {
        val cues = VttParser.parse(asr)
        assertEquals("we're", cues.textAt(100))
        assertEquals("we're going", cues.textAt(300))
        assertEquals("we're going to", cues.textAt(1_000))
        // Next line: the finished line stays above, the new one grows word by word.
        assertEquals("we're going to\ntalk", cues.textAt(2_200))
        assertEquals("we're going to\ntalk about", cues.textAt(2_500))
        assertEquals("we're going to\ntalk about captions", cues.textAt(3_500))
        assertNull(cues.textAt(5_000))
    }

    @Test
    fun ordinaryCaptionsAreUnchanged() {
        val cues = VttParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHello <i>there</i>\n\n00:00:03.500 --> 00:00:05.000\nSecond\nline\n")
        assertNull(cues.textAt(500))
        assertEquals("Hello there", cues.textAt(1_500))
        assertEquals("Second\nline", cues.textAt(4_000))
    }
}
