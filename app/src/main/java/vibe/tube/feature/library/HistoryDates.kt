package vibe.tube.feature.library

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal object HistoryDates {
    fun remoteDate(label: String?, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): LocalDate? {
        val text = label?.trim() ?: return null
        when (text.lowercase(locale)) {
            "today", "hôm nay" -> return today
            "yesterday", "hôm qua" -> return today.minusDays(1)
        }
        val formats = listOf(DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
        return formats.firstNotNullOfOrNull { format -> runCatching { LocalDate.parse(text, format) }.getOrNull() }
    }
}
