package vibe.tube.feature.channel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import vibe.tube.R
import vibe.tube.ui.theme.Spacing

@Composable
internal fun ChannelAbout(state: ChannelUiState, onRetry: () -> Unit) {
    val channel = state.detail ?: return
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(R.string.channel_tab_about), style = MaterialTheme.typography.titleLarge)
            channel.handle?.let { Text(it) }
            channel.subscriberText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            channel.description?.takeIf { it.isNotBlank() }?.let {
                Text(if (Regex("<\\w").containsMatchIn(it)) vibe.tube.ui.metadata.descriptionText(it) else AnnotatedString(it), style = MaterialTheme.typography.bodyMedium)
            }
            channel.aboutDetails.forEach { (key, value) ->
                Text(stringResource(when (key) {
                    "country" -> R.string.channel_about_country
                    "joinedDateText" -> R.string.channel_about_joined
                    "videoCountText" -> R.string.channel_about_videos
                    "subscriberCountText" -> R.string.channel_about_subscribers
                    else -> R.string.channel_about_views
                }) + ": " + value)
            }
            (channel.links + (channel.name to "https://www.youtube.com/channel/${channel.id}")).distinct().forEach { (label, url) ->
                Text(buildAnnotatedString { withLink(LinkAnnotation.Url(url)) { append(label.ifBlank { url }) } }, color = MaterialTheme.colorScheme.primary)
            }
            if (state.aboutLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.aboutError) TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}
