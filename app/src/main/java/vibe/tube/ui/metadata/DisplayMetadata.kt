package vibe.tube.ui.metadata

import android.text.Html
import android.text.style.URLSpan
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import java.text.NumberFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val VIEW_NUMBER_PATTERN = Regex("^([0-9][0-9.,\\s\\u00a0]*?)\\s*(thousand|million|billion|triệu|nghìn|ngàn|Tr|Tỷ|T|K|M|B|N)?(?:\\s|$)", RegexOption.IGNORE_CASE)

fun compactViews(raw: String?, locale: Locale): String? {
    if (raw.isNullOrBlank()) return null
    val match = VIEW_NUMBER_PATTERN.find(raw.trim()) ?: return raw
    val unit = match.groupValues[2].lowercase()
    val digits = match.groupValues[1].trim()
    val number = if (unit.isNotEmpty()) digits.filterNot(Char::isWhitespace).replace(',', '.').toDoubleOrNull()
        else digits.filter(Char::isDigit).toDoubleOrNull()
    val value = (number ?: return raw) * when (unit) {
        "k", "n", "thousand", "nghìn", "ngàn" -> 1_000.0
        "m", "tr", "million", "triệu" -> 1_000_000.0
        "b", "t", "tỷ", "billion" -> 1_000_000_000.0
        else -> 1.0
    }
    val vi = locale.language == "vi"
    val divisor = when { value >= 1_000_000_000 -> 1_000_000_000.0; value >= 1_000_000 -> 1_000_000.0; value >= 1_000 -> 1_000.0; else -> 1.0 }
    val suffix = when (divisor) { 1_000_000_000.0 -> if (vi) "T" else "B"; 1_000_000.0 -> if (vi) "Tr" else "M"; 1_000.0 -> if (vi) "N" else "K"; else -> "" }
    val compact = kotlin.math.floor(value / divisor * 10) / 10
    val text = if (compact == compact.toLong().toDouble()) compact.toLong().toString() else compact.toString()
    return text + suffix
}

fun displayCount(raw: String?, locale: Locale): String? {
    if (raw.isNullOrBlank()) return null
    val number = raw.toLongOrNull() ?: return raw
    return NumberFormat.getIntegerInstance(locale).format(number)
}

fun displayDate(raw: String?, locale: Locale): String? {
    if (raw.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(raw).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    } catch (_: Exception) {
        try { java.time.LocalDate.parse(raw).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) }
        catch (_: Exception) { raw }
    }
}

fun descriptionText(raw: String): AnnotatedString {
    val source = Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT)
    return buildAnnotatedString {
        append(source.toString())
        source.getSpans(0, source.length, URLSpan::class.java).forEach { span ->
            val uri = android.net.Uri.parse(span.url)
            if (uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank()) {
                val start = source.getSpanStart(span); val end = source.getSpanEnd(span)
                if (start >= 0 && end > start) addLink(LinkAnnotation.Url(span.url), start, end)
            }
        }
    }
}

fun detailedViews(raw: String?, locale: Locale): String? {
    val compact = compactViews(raw, locale) ?: return null
    val spaced = Regex("([0-9])([A-Za-zÀ-ỹ]+)$").replace(compact, "$1 $2")
    return spaced + if (locale.language == "vi") " lượt xem" else " views"
}

private val TIMESTAMP = Regex("(?<![\\w:])(?:(\\d{1,3}):)?([0-5]?\\d):([0-5]\\d)(?![\\d:])")
private val WEB_LINK = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)

fun interactiveText(raw: String, onSeek: (Long) -> Unit, links: List<vibe.tube.youtube.model.TextLink> = emptyList()): AnnotatedString {
    val original = if (Regex("<[/a-zA-Z][^>]*>").containsMatchIn(raw)) descriptionText(raw) else AnnotatedString(raw)
    val base = buildAnnotatedString {
        append(original)
        links.forEach { link ->
            val uri = runCatching { java.net.URI(link.url) }.getOrNull()
            if (link.start >= 0 && link.end > link.start && link.end <= length &&
                uri?.scheme?.lowercase() in listOf("http", "https") && !uri?.host.isNullOrBlank())
                addLink(LinkAnnotation.Url(link.url), link.start, link.end)
        }
    }
    return buildAnnotatedString {
        append(base.text)
        val style = androidx.compose.ui.text.TextLinkStyles(
            style = androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))
        base.getLinkAnnotations(0, base.length).forEach { range ->
            val stamp = TIMESTAMP.matchEntire(base.text.substring(range.start, range.end))
            val url = (range.item as? LinkAnnotation.Url)?.url
            val host = url?.let { android.net.Uri.parse(it).host }.orEmpty()
            if (stamp != null && (host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be")) {
                val seconds = (stamp.groupValues[1].toLongOrNull() ?: 0) * 3600 +
                    stamp.groupValues[2].toLong() * 60 + stamp.groupValues[3].toLong()
                addLink(LinkAnnotation.Clickable("seek:$seconds", styles = style, linkInteractionListener = { onSeek(seconds * 1000) }), range.start, range.end)
            } else if (url != null) addLink(LinkAnnotation.Url(url, styles = style), range.start, range.end)
        }
        val occupied = base.getLinkAnnotations(0, base.length).map { it.start until it.end }.toMutableList()
        WEB_LINK.findAll(base.text).forEach { match ->
            if (occupied.none { match.range.first in it }) {
                val url = match.value.trimEnd('.', ',', ')', ';', '!')
                addLink(LinkAnnotation.Url(url, styles = style), match.range.first, match.range.first + url.length)
                occupied += match.range.first until match.range.first + url.length
            }
        }
        TIMESTAMP.findAll(base.text).forEach { match ->
            if (occupied.none { match.range.first in it }) {
                val seconds = (match.groupValues[1].toLongOrNull() ?: 0) * 3600 +
                    match.groupValues[2].toLong() * 60 + match.groupValues[3].toLong()
                addLink(LinkAnnotation.Clickable("seek:$seconds", styles = style, linkInteractionListener = { onSeek(seconds * 1000) }),
                    match.range.first, match.range.last + 1)
            }
        }
    }
}

fun highlightMentions(text: AnnotatedString, color: androidx.compose.ui.graphics.Color, onOpenChannel: ((String) -> Unit)? = null): AnnotatedString = buildAnnotatedString {
    val mentions = Regex("@[^\\s@,;:!?<>]+").findAll(text.text).toList()
    append(text.text)
    text.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
    text.getLinkAnnotations(0, text.length).filter { link -> mentions.none { it.range.first < link.end && it.range.last + 1 > link.start } }
        .forEach { range -> when (val link = range.item) {
            is LinkAnnotation.Url -> addLink(link, range.start, range.end)
            is LinkAnnotation.Clickable -> addLink(link, range.start, range.end)
        } }
    mentions.forEach { mention ->
        val start = mention.range.first
        val end = mention.range.last + 1
        val handle = mention.value.trimEnd('.', ')', ']')
        val url = text.getLinkAnnotations(start, end).firstOrNull()?.item as? LinkAnnotation.Url
        val target = url?.url?.substringAfter("/channel/", "")?.substringBefore('?')?.takeIf { it.startsWith("UC") } ?: handle
        addStyle(androidx.compose.ui.text.SpanStyle(color = color), start, end)
        if (onOpenChannel != null) addLink(LinkAnnotation.Clickable("channel:$target", linkInteractionListener = { onOpenChannel(target) }), start, end)
        else addLink(LinkAnnotation.Url("https://www.youtube.com/$handle"), start, end)
    }
}
