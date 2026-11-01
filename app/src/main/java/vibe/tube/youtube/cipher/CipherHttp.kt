package vibe.tube.youtube.cipher

import okhttp3.OkHttpClient

/** The app's shared OkHttp client for the cipher package, installed once at startup. */
object CipherHttp {
    @Volatile private var base: OkHttpClient? = null

    fun install(client: OkHttpClient) {
        base = client
    }

    fun get(): OkHttpClient = base ?: OkHttpClient()
}
