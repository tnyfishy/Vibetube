package vibe.tube.data.repo

import android.content.SharedPreferences
import org.json.JSONObject

data class ResumeRecord(
    val videoId: String,
    val title: String,
    val channelName: String?,
    val thumbnailUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
)

class ResumeStore(private val prefs: SharedPreferences) {

    fun get(scopeKey: String): ResumeRecord? {
        val raw = prefs.getString(key(scopeKey), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            ResumeRecord(
                videoId = json.getString("id"),
                title = json.optString("title"),
                channelName = json.optString("channel").takeIf { it.isNotEmpty() },
                thumbnailUrl = json.optString("thumb").takeIf { it.isNotEmpty() },
                positionMs = json.optLong("pos"),
                durationMs = json.optLong("dur"),
            )
        }.getOrNull()
    }

    fun put(scopeKey: String, record: ResumeRecord, sync: Boolean = false) {
        val json = JSONObject()
            .put("id", record.videoId)
            .put("title", record.title)
            .put("channel", record.channelName.orEmpty())
            .put("thumb", record.thumbnailUrl.orEmpty())
            .put("pos", record.positionMs)
            .put("dur", record.durationMs)
        val editor = prefs.edit().putString(key(scopeKey), json.toString())
        if (sync) editor.commit() else editor.apply()
    }

    fun clear(scopeKey: String) {
        prefs.edit().remove(key(scopeKey)).apply()
    }

    fun clearVideo(scopeKey: String, videoId: String) {
        if (get(scopeKey)?.videoId == videoId) clear(scopeKey)
    }

    fun clearAccount(accountId: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$PREFIX$accountId/") }.forEach(editor::remove)
        editor.apply()
    }

    private fun key(scopeKey: String) = PREFIX + scopeKey

    companion object {
        private const val PREFIX = "pending:"
        const val PREFS_NAME = "resume_state"
    }
}
