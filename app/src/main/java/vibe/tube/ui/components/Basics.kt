package vibe.tube.ui.components

import androidx.annotation.DrawableRes
import vibe.tube.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import vibe.tube.ui.theme.LocalReducedMotion
import vibe.tube.ui.theme.Sizing
import vibe.tube.ui.theme.Spacing

@Composable
fun VIconImage(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint,
    )
}

@Composable
fun TooltipIconButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            modifier = modifier.size(Sizing.minTouchTarget),
            enabled = enabled,
        ) {
            VIconImage(icon, label, tint = tint)
        }
    }
}

@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    val request = ImageRequest.Builder(context).data(url).crossfade(!reduced).build()
    AsyncImage(model = request, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
}

/**
 * Image that walks through [urls] in order: when one fails to load (for example a missing
 * `maxresdefault.jpg`, which YouTube answers with 404) the next candidate is tried.
 */
@Composable
fun RemoteImage(
    urls: List<String>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    var index by androidx.compose.runtime.remember(urls) { androidx.compose.runtime.mutableIntStateOf(0) }
    val request = androidx.compose.runtime.remember(urls, index, reduced) {
        ImageRequest.Builder(context).data(urls.getOrNull(index)).crossfade(!reduced).build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onError = { if (index < urls.lastIndex) index++ },
    )
}

/** Google/YouTube avatar URLs end with a size token such as `=s88-c-k-...`; ask for a large one. */
fun upscaleAvatarUrl(url: String?, px: Int = 512): String? {
    if (url == null) return null
    val host = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
    if (!(host.endsWith("googleusercontent.com") || host.endsWith("ggpht.com"))) return url
    val sized = Regex("=s\\d+").replace(url, "=s$px")
    if (sized != url) return sized
    return Regex("=w\\d+-h\\d+").replace(url, "=w$px-h$px")
}

@Composable
fun Avatar(url: String?, size: Dp, modifier: Modifier = Modifier, description: String? = null) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.size(size),
    ) {
        if (url != null) RemoteImage(upscaleAvatarUrl(url, (size.value * 4).toInt().coerceIn(128, 800)), description, Modifier.fillMaxSize())
        else VIconImage(
            vibe.tube.ui.VIcon.AccountCircleFilled,
            description,
            Modifier.padding(size / 8),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun CenterLoading(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ContainedLoadingIndicator()
    }
}

@Composable
fun StateMessage(
    @DrawableRes icon: Int,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
    ) {
        VIconImage(icon, null, Modifier.size(48.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (primaryLabel != null && onPrimary != null) {
            Spacer(Modifier.height(Spacing.xs))
            Button(shapes = ButtonDefaults.shapes(), onClick = onPrimary) { Text(primaryLabel) }
        }
        if (secondaryLabel != null && onSecondary != null) {
            FilledTonalButton(shapes = ButtonDefaults.shapes(), onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}

@Composable
fun Banner(
    text: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            VIconImage(icon, null, Modifier.size(Sizing.iconMedium))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                androidx.compose.material3.TextButton(shapes = ButtonDefaults.shapes(), onClick = onAction) { Text(actionLabel) }
            }
            if (onDismiss != null) {
                TooltipIconButton(vibe.tube.ui.VIcon.Close, androidx.compose.ui.res.stringResource(R.string.action_close), onDismiss)
            }
        }
    }
}

@Composable
fun ChipLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.labelLarge)
}
