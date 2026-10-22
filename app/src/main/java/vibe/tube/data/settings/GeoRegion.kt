package vibe.tube.data.settings

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Country of the device's public IP address (two-letter ISO code), used as the content region when the user
 * has not picked one. The last answer is cached on disk so the first feed after launch already has a region.
 */
class GeoRegion(
    context: Context,
    http: OkHttpClient,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences("geo_region", Context.MODE_PRIVATE)
    private val client = http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
    private val mutableRegion = MutableStateFlow(prefs.getString(KEY_REGION, null))

    /** `null` until the first lookup (or cache entry) is available. */
    val region: StateFlow<String?> = mutableRegion.asStateFlow()

    init {
        scope.launch { refreshIfStale() }
    }

    private suspend fun refreshIfStale() {
        val fetchedAt = prefs.getLong(KEY_AT, 0L)
        if (mutableRegion.value != null && now() - fetchedAt < TTL_MS) return
        val code = withContext(io) { lookup() } ?: return
        prefs.edit().putString(KEY_REGION, code).putLong(KEY_AT, now()).apply()
        mutableRegion.value = code
    }

    private fun lookup(): String? {
        for ((url, field) in ENDPOINTS) {
            val code = runCatching {
                client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    JSONObject(response.body.string()).optString(field)
                }
            }.getOrNull()?.trim()?.uppercase()
            if (code != null && code.length == 2 && code.all { it in 'A'..'Z' }) return code
        }
        return null
    }

    private companion object {
        const val KEY_REGION = "region"
        const val KEY_AT = "at"
        const val TTL_MS = 6L * 60 * 60 * 1000
        val ENDPOINTS = listOf(
            "https://api.country.is/" to "country",
            "https://ipwho.is/" to "country_code",
            "https://ipapi.co/json/" to "country_code",
        )
    }
}
