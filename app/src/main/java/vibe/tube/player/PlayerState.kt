package vibe.tube.player

import vibe.tube.core.PlaybackFailure
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.player.AudioTrackOption
import vibe.tube.youtube.player.CaptionTrack

enum class PlayerPhase { Idle, Resolving, Buffering, Ready, Ended, Error }

data class PlayerError(val failure: PlaybackFailure, val detail: String?)

data class PlayerUiState(
    val visible: Boolean = false,
    val expanded: Boolean = false,
    val fullscreen: Boolean = false,
    val inPip: Boolean = false,
    val current: VideoItem? = null,
    val queue: List<VideoItem> = emptyList(),
    val queueIndex: Int = -1,
    val playlistId: String? = null,
    val phase: PlayerPhase = PlayerPhase.Idle,
    val error: PlayerError? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val speed: Float = 1f,
    val isLive: Boolean = false,
    val qualities: List<Int> = emptyList(),
    val selectedHeight: Int? = null,
    val activeHeight: Int? = null,
    val audioTracks: List<AudioTrackOption> = emptyList(),
    val selectedAudioTrack: String? = null,
    val captions: List<CaptionTrack> = emptyList(),
    val selectedCaption: String? = null,
    val clientName: String? = null,
    val videoAspect: Float = 16f / 9f,
) {
    val hasNext: Boolean get() = queueIndex in 0 until queue.size - 1
    val hasPrevious: Boolean get() = queueIndex > 0
}
