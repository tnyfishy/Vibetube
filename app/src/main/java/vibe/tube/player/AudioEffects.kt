package vibe.tube.player

import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer

/** Equalizer shapes. Each is a gain per band as a fraction of the device's own range, from the lowest band (x = 0) to the highest (x = 1). */
enum class EqPreset(val shape: (Float) -> Float) {
    Off({ 0f }),
    Bass({ x -> if (x < 0.25f) 0.8f else if (x < 0.5f) 0.4f else 0f }),
    Treble({ x -> if (x > 0.75f) 0.8f else if (x > 0.5f) 0.4f else 0f }),
    Vocal({ x -> if (x in 0.4f..0.8f) 0.5f else if (x < 0.15f || x > 0.9f) -0.3f else 0f }),
    Loud({ x -> if (x < 0.2f || x > 0.8f) 0.6f else -0.2f }),
}

/**
 * The platform's audio effects on the player's audio session: equalizer presets, bass boost and a loudness gain. They
 * are re-created whenever the player hands out a new session id and silently do nothing on devices without them.
 */
class AudioEffects(private val prefs: SharedPreferences) {
    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null
    private var session = 0

    var preset: EqPreset = runCatching { EqPreset.valueOf(prefs.getString("eq_preset", null) ?: "Off") }.getOrDefault(EqPreset.Off)
        private set
    /** 0..1000 */
    var bassStrength: Int = prefs.getInt("bass_boost", 0)
        private set
    /** Extra gain in millibels, 0..1000 */
    var gainMb: Int = prefs.getInt("loudness_mb", 0)
        private set

    /** True once an effect could actually be created (so the page can say when the device has none). */
    var available: Boolean = true
        private set

    fun attach(sessionId: Int) {
        if (sessionId == session && (equalizer != null || bass != null || loudness != null)) return
        release()
        session = sessionId
        if (sessionId == 0) return
        equalizer = runCatching { Equalizer(0, sessionId).apply { enabled = true } }.getOrNull()
        bass = runCatching { BassBoost(0, sessionId).apply { enabled = true } }.getOrNull()
        loudness = runCatching { LoudnessEnhancer(sessionId).apply { enabled = true } }.getOrNull()
        available = equalizer != null || bass != null || loudness != null
        applyAll()
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bass?.release() }
        runCatching { loudness?.release() }
        equalizer = null
        bass = null
        loudness = null
        session = 0
    }

    fun setPreset(value: EqPreset) {
        preset = value
        prefs.edit().putString("eq_preset", value.name).apply()
        applyEqualizer()
    }

    fun setBass(strength: Int) {
        bassStrength = strength.coerceIn(0, 1000)
        prefs.edit().putInt("bass_boost", bassStrength).apply()
        applyBass()
    }

    fun setGain(mb: Int) {
        gainMb = mb.coerceIn(0, 1000)
        prefs.edit().putInt("loudness_mb", gainMb).apply()
        applyLoudness()
    }

    private fun applyAll() {
        applyEqualizer()
        applyBass()
        applyLoudness()
    }

    private fun applyEqualizer() {
        val eq = equalizer ?: return
        runCatching {
            val range = eq.bandLevelRange
            val bands = eq.numberOfBands.toInt()
            val max = range[1].toInt()
            val min = range[0].toInt()
            for (band in 0 until bands) {
                val x = if (bands <= 1) 0f else band.toFloat() / (bands - 1)
                val fraction = preset.shape(x)
                val level = if (fraction >= 0f) (fraction * max).toInt() else (-fraction * min).toInt()
                eq.setBandLevel(band.toShort(), level.coerceIn(min, max).toShort())
            }
        }
    }

    private fun applyBass() {
        val b = bass ?: return
        runCatching { if (b.strengthSupported) b.setStrength(bassStrength.toShort()) }
    }

    private fun applyLoudness() {
        val l = loudness ?: return
        runCatching { l.setTargetGain(gainMb) }
    }
}
