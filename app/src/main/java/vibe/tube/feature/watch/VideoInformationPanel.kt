package vibe.tube.feature.watch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.remember
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.em
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vibe.tube.R
import vibe.tube.player.PlayerUiState
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.Avatar
import vibe.tube.ui.components.TooltipIconButton
import vibe.tube.ui.metadata.*

@Composable
internal fun VideoInformationPanel(ui: WatchUiState, state: PlayerUiState, onClose: () -> Unit) {
    BackHandler(ui.descriptionOpen, onClose)
    AnimatedVisibility(ui.descriptionOpen, enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()) {
        Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(40.dp).height(4.dp).background(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
                    Text(ui.info?.title ?: state.current?.title.orEmpty(), Modifier.weight(1f).padding(top = 8.dp), style = MaterialTheme.typography.titleLarge)
                    TooltipIconButton(VIcon.Close, stringResource(R.string.action_close), onClose)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        val channel = ui.info?.channel
                        val channelName = channel?.name?.takeIf { it.isNotBlank() } ?: state.current?.channelName.orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Avatar(channel?.avatar ?: state.current?.channelAvatar, 32.dp, channelId = channel?.id ?: state.current?.channelId)
                            vibe.tube.ui.components.ChannelMetaText(channelName.ifBlank { "—" }, channel?.badge ?: state.current?.channelBadge ?: vibe.tube.youtube.model.ChannelBadge.None, emptyList(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    item {
                        val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoStat(ui.votes?.likes?.let { compactCount(it.toString()) } ?: ui.info?.likeCountText ?: "—", stringResource(R.string.video_likes), Modifier.weight(1f))
                            InfoStat(compactViews(ui.info?.viewCountText, locale) ?: "—", stringResource(R.string.channel_about_views), Modifier.weight(1f))
                            val date = shortPublicationDate(ui.info?.dateText, locale)
                            val year = date?.let { Regex("\\b(?:19|20)\\d{2}\\b").find(it)?.value }
                            InfoStat(year ?: date ?: "—", if (year != null) date.replace(year, "").trim(' ', ',', '.', '-') else stringResource(R.string.video_published), Modifier.weight(1f))
                        }
                    }
                    item {
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            SelectionContainer {
                                val seek = LocalMediaSeek.current
                                val description = remember(ui.info?.description, ui.info?.descriptionLinks, seek) {
                                    socialDescription(interactiveText(ui.info?.description.orEmpty(), onSeek = seek, links = ui.info?.descriptionLinks.orEmpty()))
                                }
                                Text(description.first, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium, inlineContent = description.second)
                            }
                        }
                    }
                    item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
                }
            }
        }
    }
}

@Composable
private fun InfoStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun shortPublicationDate(raw: String?, locale: java.util.Locale): String? {
    val date = displayDate(raw, locale) ?: return null
    return date.replace(Regex("^(?:Đã công chiếu vào|Công chiếu vào|Premiered on|Premiered|Published on|Published)\\s+", RegexOption.IGNORE_CASE), "")
}

internal fun socialIconFor(url: String): Int? {
    val input = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
    val destination = runCatching { input.getQueryParameter("q") }.getOrNull()?.takeIf { it.startsWith("http") } ?: url
    val host = runCatching { android.net.Uri.parse(destination).host?.lowercase() }.getOrNull() ?: return null
    fun matches(domain: String) = host == domain || host.endsWith(".$domain")
    return when {
        matches("facebook.com") || matches("fb.com") || matches("fb.me") -> R.drawable.social_facebook
        matches("instagram.com") -> R.drawable.social_instagram
        matches("tiktok.com") -> R.drawable.social_tiktok
        matches("x.com") || matches("twitter.com") -> R.drawable.social_x
        matches("youtube.com") || matches("youtu.be") -> R.drawable.social_youtube
        matches("spotify.com") -> R.drawable.social_spotify
        matches("t.me") || matches("telegram.org") -> R.drawable.social_telegram
        matches("github.com") -> R.drawable.social_github
        else -> null
    }
}

internal fun socialDescription(base: androidx.compose.ui.text.AnnotatedString): Pair<androidx.compose.ui.text.AnnotatedString, Map<String, InlineTextContent>> {
    val links = base.getLinkAnnotations(0, base.length).mapNotNull { range ->
        val url = (range.item as? LinkAnnotation.Url)?.url ?: return@mapNotNull null
        val icon = socialIconFor(url) ?: return@mapNotNull null
        Triple(range.start, range.end, icon)
    }.sortedBy { it.first }
    val icons = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        var cursor = 0
        links.forEachIndexed { index, (start, end, icon) ->
            if (start < cursor || end > base.length) return@forEachIndexed
            append(base.subSequence(cursor, start))
            val key = "social-$index"
            icons[key] = InlineTextContent(Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter)) {
                androidx.compose.foundation.Image(painterResource(icon), contentDescription = null, modifier = Modifier.fillMaxSize(),
                    colorFilter = if (icon == R.drawable.social_x || icon == R.drawable.social_github) androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurface) else null)
            }
            appendInlineContent(key, "●")
            append(" ")
            append(base.subSequence(start, end))
            cursor = end
        }
        append(base.subSequence(cursor, base.length))
    }
    return text to icons
}
