package vibe.tube.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vibe.tube.R
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.components.pressScale
import vibe.tube.ui.theme.Spacing

private const val FACEBOOK_URL = "https://facebook.com/dtrngh"
private const val TELEGRAM_URL = "https://t.me/tnyfishy"

@Composable
fun AboutCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, spring(dampingRatio = 0.8f, stiffness = 300f), label = "arrow")
    Surface(
        modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerHigh,
    ) {
        Column(Modifier.animateContentSize(spring(dampingRatio = 0.85f, stiffness = 320f))) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(52.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.author), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.about_author), style = MaterialTheme.typography.titleMedium)
                }
                VIconImage(VIcon.ExpandMore, null, Modifier.size(24.dp).graphicsLayer { rotationZ = arrow }, tint = scheme.onSurfaceVariant)
            }
            if (expanded) {
                Column(Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg)) {
                    LinkRow(VIcon.Facebook, stringResource(R.string.about_facebook), "facebook.com/dtrngh", Color(0xFF1877F2)) { open(FACEBOOK_URL) }
                    Spacer(Modifier.height(Spacing.xs))
                    LinkRow(VIcon.Telegram, stringResource(R.string.about_telegram), "@tnyfishy", Color(0xFF229ED9)) { open(TELEGRAM_URL) }
                    LinkRow(VIcon.Github, "GitHub", "github.com/tnyfishy", scheme.onSurface) { open("https://github.com/tnyfishy") }
                    Spacer(Modifier.height(Spacing.md))

                }
            }
        }
    }
}

@Composable
private fun LinkRow(initial: Int, title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(initial), null, Modifier.size(28.dp), colorFilter = if (initial == VIcon.Github) androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurface) else null)
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VIconImage(VIcon.OpenInNew, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
