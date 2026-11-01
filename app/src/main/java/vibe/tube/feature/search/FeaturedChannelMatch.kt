package vibe.tube.feature.search

import java.text.Normalizer
import java.util.Locale
import vibe.tube.youtube.model.ChannelBadge
import vibe.tube.youtube.model.ChannelItem

private val DIACRITICS = Regex("\\p{Mn}+")
private val NOT_ALNUM = Regex("[^a-z0-9]")
private val NOISE_SUFFIXES = listOf("official", "channel", "chinhthuc", "vevo")

/** Lowercase, accent-free, letters and digits only: "Sơn Tùng M-TP" and "sontungmtp" compare equal. */
internal fun channelKey(raw: String): String {
    val folded = Normalizer.normalize(raw.lowercase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD).replace(DIACRITICS, "")
    return folded.replace(NOT_ALNUM, "")
}

private fun stripNoise(key: String): String {
    var out = key
    var changed = true
    while (changed) {
        changed = false
        for (s in NOISE_SUFFIXES) if (out.length > s.length && out.endsWith(s)) { out = out.removeSuffix(s); changed = true }
    }
    return out
}

private fun isBigChannel(c: ChannelItem): Boolean {
    if (c.badge != ChannelBadge.None) return true
    val compact = c.subscriberText?.let { vibe.tube.ui.metadata.compactViews(it, Locale.US) } ?: return false
    return compact.endsWith("M") || compact.endsWith("B")
}

/** The channel the query is clearly asking for: name or handle matches ignoring accents, punctuation and "Official", and the channel is verified or very large. */
internal fun pickFeaturedChannel(query: String, candidates: List<ChannelItem>): ChannelItem? {
    val wanted = stripNoise(channelKey(query))
    if (wanted.length < 2) return null
    return candidates.firstOrNull { c ->
        val names = listOfNotNull(c.name, c.handle).map { stripNoise(channelKey(it)) }
        wanted in names && isBigChannel(c)
    }
}
