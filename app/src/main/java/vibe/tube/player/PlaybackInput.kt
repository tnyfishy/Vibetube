package vibe.tube.player

import kotlin.math.ceil

internal fun parsePlaybackNumber(text: String, range: ClosedFloatingPointRange<Float>): Float? =
    text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in range }

internal fun remainingSleepMinutes(s: PlayerUiState): Int? =
    if (s.isLive || s.durationMs <= 0 || !s.speed.isFinite() || s.speed <= 0f) null
    else ceil((s.durationMs - s.positionMs).coerceAtLeast(0L) / (60_000.0 * s.speed)).toInt()
