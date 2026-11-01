package vibe.tube.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import vibe.tube.BuildConfig

data class DiagnosticEvent(
    val timestamp: Long,
    val feedType: String,
    val source: String,
    val provider: String,
    val sessionVerified: Boolean,
    val accountRef: String,
    val durationMs: Long,
    val hasContinuation: Boolean,
    val itemCount: Int,
    val httpCode: Int?,
    val parserError: String?,
    val sessionExpired: Boolean,
    val adsSkipped: Int,
)

class Diagnostics(private val enabled: Boolean = BuildConfig.DEBUG) {
    private val state = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticEvent>> = state.asStateFlow()

    fun record(event: DiagnosticEvent) {
        if (!enabled) return
        state.update { (listOf(event) + it).take(MAX_EVENTS) }
    }

    fun clear() {
        state.value = emptyList()
    }

    private companion object {
        const val MAX_EVENTS = 100
    }
}
