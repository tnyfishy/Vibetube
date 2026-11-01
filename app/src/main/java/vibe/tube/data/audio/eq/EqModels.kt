package vibe.tube.data.audio.eq

import kotlinx.serialization.Serializable

@Serializable
enum class EqFilterType {
    PEAK,
    LOW_SHELF,
    HIGH_SHELF,
    LOW_PASS,
    HIGH_PASS,
    ;

    val hasGain: Boolean
        get() = this == PEAK || this == LOW_SHELF || this == HIGH_SHELF
}

@Serializable
data class EqBand(
    val frequency: Double,
    val gain: Double = 0.0,
    val q: Double = EqLimits.DEFAULT_PEAK_Q,
    val type: EqFilterType = EqFilterType.PEAK,
    val enabled: Boolean = true,
)

data class EqProcessingSpec(
    val enabled: Boolean,
    val preampDb: Double,
    val bands: List<EqBand>,
) {
    companion object {
        val OFF = EqProcessingSpec(enabled = false, preampDb = 0.0, bands = emptyList())
    }
}
