package vibe.tube.feature.library

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class HistoryDatesTest {
    @Test fun relativeDaysCrossMonthBoundariesWithoutInventingUnknownDates() {
        val today = LocalDate.of(2026, 3, 1)
        assertEquals(today, HistoryDates.remoteDate("Hôm nay", today))
        assertEquals(LocalDate.of(2026, 2, 28), HistoryDates.remoteDate("Yesterday", today))
        assertEquals(LocalDate.of(2025, 12, 31), HistoryDates.remoteDate("2025-12-31", today, Locale.US))
        assertNull(HistoryDates.remoteDate("long ago", today))
    }
}
