package vibe.tube.player

import android.content.SharedPreferences

enum class EqPreset(val shape: (Float) -> Float) {
    Off({ 0f }),
    Bass({ x -> if (x < 0.25f) 0.8f else if (x < 0.5f) 0.4f else 0f }),
    Treble({ x -> if (x > 0.75f) 0.8f else if (x > 0.5f) 0.4f else 0f }),
    Vocal({ x -> if (x in 0.4f..0.8f) 0.5f else if (x < 0.15f || x > 0.9f) -0.3f else 0f }),
    Loud({ x -> if (x < 0.2f || x > 0.8f) 0.6f else -0.2f }),
}

class AudioEffects(private val prefs: SharedPreferences) {
    var preset: EqPreset = runCatching { EqPreset.valueOf(prefs.getString("eq_preset", "Off")!!) }.getOrDefault(EqPreset.Off)
        private set
    var bassStrength = prefs.getInt("bass_boost", 0)
        private set
    var gainMb = prefs.getInt("loudness_mb", 0)
        private set
    var processor: vibe.tube.player.audio.eq.EqualizerAudioProcessor? = null
        set(value) { field = value; sync() }
    val available get() = processor != null
    fun attach(sessionId: Int) { sync() }
    fun release() { processor?.setSpec(vibe.tube.data.audio.eq.EqProcessingSpec.OFF) }
    fun setPreset(value: EqPreset) { preset = value; prefs.edit().putString("eq_preset", value.name).remove("flow_eq_preset").apply(); sync() }
    fun setBass(value: Int) { bassStrength = value.coerceIn(0,1000); prefs.edit().putInt("bass_boost", bassStrength).apply(); sync() }
    fun setGain(value: Int) { gainMb = value.coerceIn(0,1000); prefs.edit().putInt("loudness_mb",gainMb).apply(); sync() }
    private fun sync() {
        val bands = listOf(60.0,230.0,910.0,3600.0,14000.0).mapIndexed { i,f -> vibe.tube.data.audio.eq.EqBand(f,preset.shape(i/4f)*10.0) } +
            if (bassStrength > 0) listOf(vibe.tube.data.audio.eq.EqBand(60.0, bassStrength/100.0, type=vibe.tube.data.audio.eq.EqFilterType.LOW_SHELF)) else emptyList()
        processor?.setSpec(vibe.tube.data.audio.eq.EqProcessingSpec(preset != EqPreset.Off || bassStrength > 0 || gainMb > 0,
            gainMb/100.0, bands))
    }
}
