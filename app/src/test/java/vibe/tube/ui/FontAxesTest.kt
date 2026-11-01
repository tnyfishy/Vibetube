package vibe.tube.ui

import androidx.compose.ui.text.font.FontVariation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.data.settings.AppFont
import vibe.tube.data.settings.FontAxis
import vibe.tube.feature.settings.weightOffsetRange
import vibe.tube.ui.theme.FontStyleSettings
import vibe.tube.ui.theme.variationFor
import java.io.File
import java.io.RandomAccessFile

/** The font settings use the axes AppFont declares; this keeps those declarations equal to the files' own fvar tables. */
class FontAxesTest {

    private fun fontFile(font: AppFont): File {
        val name = when (font) {
            AppFont.GoogleSansFlex -> "google_sans_flex.ttf"
            AppFont.TikTokSans -> "tiktok_sans.ttf"
            else -> error("no variable file")
        }
        return listOf(File("src/main/res/font/$name"), File("app/src/main/res/font/$name")).first { it.exists() }
    }

    /** Minimal OpenType reader: table directory, then the fvar axis records (Fixed 16.16 values). */
    private fun readAxes(file: File): List<FontAxis> = RandomAccessFile(file, "r").use { f ->
        f.seek(4)
        val tables = f.readUnsignedShort()
        f.seek(12)
        var fvar = -1L
        repeat(tables) {
            val tag = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
            f.readInt()
            val offset = f.readInt().toLong() and 0xFFFFFFFFL
            f.readInt()
            if (tag == "fvar") fvar = offset
        }
        if (fvar < 0) return@use emptyList()
        f.seek(fvar + 4)
        val axesOffset = f.readUnsignedShort()
        f.readUnsignedShort()
        val count = f.readUnsignedShort()
        val size = f.readUnsignedShort()
        (0 until count).map { i ->
            f.seek(fvar + axesOffset + i.toLong() * size)
            val tag = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
            val min = f.readInt() / 65536f
            val def = f.readInt() / 65536f
            val max = f.readInt() / 65536f
            FontAxis(tag, min, def, max)
        }
    }

    @Test
    fun declaredAxesAreExactlyTheFilesAxes() {
        listOf(AppFont.GoogleSansFlex, AppFont.TikTokSans).forEach { font ->
            val real = readAxes(fontFile(font))
            assertEquals("$font axes", real.map { it.tag }.toSet(), font.axes.map { it.tag }.toSet())
            real.forEach { r ->
                val d = font.axis(r.tag)!!
                assertEquals("$font ${r.tag} min", r.min, d.min, 0.01f)
                assertEquals("$font ${r.tag} default", r.default, d.default, 0.01f)
                assertEquals("$font ${r.tag} max", r.max, d.max, 0.01f)
            }
        }
    }

    private fun tags(s: FontVariation.Settings) = s.settings.map { it.axisName }.toSet()

    @Test
    fun onlyAxesTheFontHasAreEverSet() {
        val style = FontStyleSettings(width = 200, roundness = 50, slant = -30)
        assertEquals(setOf("wght", "wdth", "opsz", "ROND", "slnt"), tags(variationFor(AppFont.GoogleSansFlex, 400, 16f, style.copy(font = AppFont.GoogleSansFlex))))
        val tiktok = variationFor(AppFont.TikTokSans, 400, 16f, style.copy(font = AppFont.TikTokSans))
        assertEquals(setOf("wght", "wdth", "opsz", "slnt"), tags(tiktok))
        // Values are clamped to that font's own ranges, not to one range shared by all fonts.
        val values = tiktok.settings.associate { it.axisName to (it as FontVariation.Setting).toVariationValue(null) }
        assertEquals(150f, values.getValue("wdth"), 0f)
        assertEquals(-6f, values.getValue("slnt"), 0f)
        assertEquals(16f, values.getValue("opsz"), 0f)
    }

    @Test
    fun weightRangeFollowsWhatTheFontCanDraw() {
        assertEquals(-100f..300f, weightOffsetRange(AppFont.TikTokSans))
        assertEquals(-150f..300f, weightOffsetRange(AppFont.GoogleSansFlex))
        assertEquals(-150f..300f, weightOffsetRange(AppFont.BeVietnamPro))
        assertTrue(AppFont.BeVietnamPro.axes.isEmpty())
        assertFalse(AppFont.TikTokSans.hasRoundness)
    }
}
