package vibe.tube.player

object ResumePolicy {
    const val MIN_DURATION_MS = 90_000L
    const val MIN_POSITION_MS = 15_000L
    const val TAIL_MS = 20_000L

    enum class Decision { Record, Clear, Ignore }

    fun isWorthResuming(positionMs: Long, durationMs: Long): Boolean =
        durationMs > MIN_DURATION_MS && positionMs > MIN_POSITION_MS && positionMs < durationMs - TAIL_MS

    fun decide(
        state: PlayerUiState,
        recordsHistory: Boolean,
        alreadyPending: Boolean,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
    ): Decision {
        val video = state.current ?: return Decision.Ignore
        if (!state.visible || state.staged || state.isLive || video.isShort || !recordsHistory) return Decision.Ignore
        if (completed) return Decision.Clear
        if (durationMs <= MIN_DURATION_MS) return Decision.Ignore
        if (positionMs >= durationMs - TAIL_MS) return Decision.Clear
        return if (alreadyPending || positionMs > MIN_POSITION_MS) Decision.Record else Decision.Ignore
    }
}
