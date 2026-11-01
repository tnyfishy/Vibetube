package vibe.tube.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vibe.tube.R

val WordmarkRed = Color(0xFFFF1F1F)

/** Google Sans Flex as the wordmark sets it: condensed (width 50), upright, optical size 43, at the given weight. */
private fun wordmarkFamily(weight: Int) = FontFamily(
    Font(
        resId = R.font.google_sans_flex,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight),
            FontVariation.width(50f),
            FontVariation.Setting("opsz", 43f),
        ),
    ),
)

/** The header wordmark, at weight 500. */
val WordmarkFont = wordmarkFamily(500)

/** The welcome screen's brand mark, at weight 700. */
val BrandFont = wordmarkFamily(700)

/** Cap height of Google Sans Flex as a share of its em (1432 of 2000 units). */
private const val CAP_HEIGHT = 0.716f

/** The name in the wordmark's type, sized by its cap height so it can be matched to the logo beside it. */
@Composable
fun Wordmark(text: String, modifier: Modifier = Modifier, capHeight: Dp = 13.6.dp) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Sized in dp, not sp: the mark keeps its proportion to the logo whatever the text scale.
    val size = with(density) { (capHeight / CAP_HEIGHT).toSp() }
    Text(
        text,
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        style = MaterialTheme.typography.titleLarge.copy(
            fontFamily = WordmarkFont,
            fontWeight = FontWeight.Medium,
            fontSize = size,
            lineHeight = size,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            letterSpacing = 0.sp,
            color = WordmarkRed,
        ),
    )
}
