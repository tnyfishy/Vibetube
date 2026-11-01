package vibe.tube.update

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import vibe.tube.BuildConfig
import vibe.tube.network.await
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the published update manifest; shared by the in-app update panel and the background check. */
@Singleton
class UpdateSource @Inject constructor(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The published manifest, or null when none is published. Throws when the check itself fails. */
    suspend fun fetch(onCall: (okhttp3.Call) -> Unit = {}): UpdateManifest? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(MANIFEST_URL).header("Cache-Control", "no-cache").build()).also(onCall).await().use { response ->
            if (response.code == 404) return@withContext null
            check(response.isSuccessful)
            val body = response.body ?: error("empty manifest")
            val bytes = body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (out.size() <= 65_536) {
                    val n = input.read(buffer, 0, minOf(buffer.size, 65_537 - out.size()))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            check(bytes.size <= 65_536)
            json.decodeFromString<UpdateManifest>(bytes.toString(Charsets.UTF_8))
        }
    }

    /** A newer release that this install can take, or null. */
    suspend fun newer(): UpdateManifest? = fetch()?.takeIf {
        it.versionCode > BuildConfig.VERSION_CODE &&
            it.validate(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT)
    }

    companion object {
        const val MANIFEST_URL = "https://raw.githubusercontent.com/tnyfishy/Vibetube/apk-downloads-20261027/update-${BuildConfig.APPLICATION_ID}.json"
    }
}
