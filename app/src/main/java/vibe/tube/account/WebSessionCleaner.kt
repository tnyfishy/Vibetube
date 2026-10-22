package vibe.tube.account

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object WebSessionCleaner {

    suspend fun clear() {
        suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    CookieManager.getInstance().removeAllCookies { if (cont.isActive) cont.resume(Unit) }
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                }.onFailure { if (cont.isActive) cont.resume(Unit) }
            }
        }
    }

    fun readYoutubeCookieHeader(): String? = runCatching {
        CookieManager.getInstance().getCookie("https://www.youtube.com")
    }.getOrNull()
}
