package vibe.tube.player

import vibe.tube.core.PlaybackFailure
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.player.AudioTrackOption
import vibe.tube.youtube.player.CaptionTrack

enum class PlayerPhase {
    Idle, Resolving, Preparing, Buffering, Recovering, Ready, Ended, Error;

    val isBusy: Boolean get() = this == Resolving || this == Preparing || this == Buffering || this == Recovering
}

enum class RepeatMode { Off, One, All }

data class PlayerError(val failure: PlaybackFailure, val detail: String?)

data class PlayerUiState(
    val ambientFullscreen: Boolean = true,
    val visible: Boolean = false,
    val expanded: Boolean = false,
    val fullscreen: Boolean = false,
    val inPip: Boolean = false,
    val screenLocked: Boolean = false,
    val current: VideoItem? = null,
    val queue: List<VideoItem> = emptyList(),
    val queueIndex: Int = -1,
    val playlistId: String? = null,
                                                                                                           
    val queueTitle: String? = null,
                                                                                                                   
    val unshuffledQueue: List<VideoItem>? = null,
    val phase: PlayerPhase = PlayerPhase.Idle,
    val error: PlayerError? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val tuningRemembered: Boolean = false,
    val linkPitch: Boolean = false,
    val eqPreset: EqPreset = EqPreset.Off,
                  
    val bassBoost: Int = 0,
                                               
    val loudnessMb: Int = 0,
    val skipSilence: Boolean = false,
    val effectsAvailable: Boolean = true,
    val sleepUntilMs: Long? = null,
    val sleepAtEnd: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val isLive: Boolean = false,
    val qualities: List<Int> = emptyList(),
    val selectedHeight: Int? = null,
    val activeHeight: Int? = null,
    val audioTracks: List<AudioTrackOption> = emptyList(),
    val selectedAudioTrack: String? = null,
    val captions: List<CaptionTrack> = emptyList(),
                                                      
    val selectedCaption: String? = null,
                                                                 
    val captionTranslations: List<String> = emptyList(),
    val captionLoading: Boolean = false,
    val clientName: String? = null,
    val publishedAt: String? = null,
    val videoAspect: Float = 16f / 9f,
                                                                                                          
    val staged: Boolean = false,
) {
    val hasNext: Boolean get() = queueIndex in 0 until queue.size - 1
    val hasPrevious: Boolean get() = queueIndex > 0
    val shuffled: Boolean get() = unshuffledQueue != null
}

internal object QueueOps {
                                                                                                  
    fun shuffle(s: PlayerUiState, random: kotlin.random.Random = kotlin.random.Random.Default): PlayerUiState {
        if (s.shuffled || s.queue.size < 2) return s
        val head = s.queue.take(s.queueIndex + 1)
        return s.copy(queue = head + s.queue.drop(head.size).shuffled(random), unshuffledQueue = s.queue)
    }

    fun unshuffle(s: PlayerUiState): PlayerUiState {
        val original = s.unshuffledQueue ?: return s
        val index = original.indexOfFirst { it.id == s.current?.id }.takeIf { it >= 0 } ?: s.queueIndex.coerceAtMost(original.lastIndex)
        return s.copy(queue = original, queueIndex = index, unshuffledQueue = null)
    }

    fun append(s: PlayerUiState, extra: List<VideoItem>, random: kotlin.random.Random = kotlin.random.Random.Default): PlayerUiState {
        val known = s.queue.mapTo(HashSet()) { it.id }
        val fresh = extra.filter { known.add(it.id) }
        if (fresh.isEmpty()) return s
        return if (s.shuffled) {
            s.copy(queue = s.queue + fresh.shuffled(random), unshuffledQueue = s.unshuffledQueue.orEmpty() + fresh)
        } else {
            s.copy(queue = s.queue + fresh)
        }
    }
}
