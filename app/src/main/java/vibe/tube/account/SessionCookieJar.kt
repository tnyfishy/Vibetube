package vibe.tube.account

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import vibe.tube.core.Clock

class SessionCookieJar(
    initial: List<StoredCookie>,
    private val clock: Clock = Clock.System,
    private val onChanged: (List<StoredCookie>) -> Unit = {},
) : CookieJar {

    private val lock = Any()
    private var store: MutableList<StoredCookie> = initial.filterTo(mutableListOf()) { isAllowedDomain(it.domain) }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!isAllowedHost(url.host)) return
        var changed = false
        synchronized(lock) {
            val now = clock.nowMillis()
            for (cookie in cookies) {
                if (!isAllowedDomain(cookie.domain)) continue
                store.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                changed = true
                val expired = cookie.persistent && cookie.expiresAt <= now
                if (!expired) store.add(cookie.toStored())
            }
        }
        if (changed) onChanged(snapshot())
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!isAllowedHost(url.host)) return emptyList()
        val now = clock.nowMillis()
        return synchronized(lock) {
            store.asSequence()
                .filter { it.expiresAt == null || it.expiresAt > now }
                .map { it.toOkHttp() }
                .filter { it.matches(url) }
                .toList()
        }
    }

    fun snapshot(): List<StoredCookie> = synchronized(lock) { store.toList() }

    fun valueOf(name: String): String? = synchronized(lock) {
        val now = clock.nowMillis()
        store.firstOrNull { it.name == name && (it.expiresAt == null || it.expiresAt > now) }?.value
    }

    fun clear() {
        synchronized(lock) { store.clear() }
    }

    companion object {
        fun isAllowedHost(host: String): Boolean = isAllowedDomain(host)

        fun isAllowedDomain(domain: String): Boolean {
            val d = domain.trimStart('.').lowercase()
            return d == "youtube.com" || d.endsWith(".youtube.com") || d == "google.com" || d.endsWith(".google.com")
        }

        fun fromWebViewHeader(header: String, domain: String, nowMillis: Long): List<StoredCookie> {
            return header.split(';')
                .map { it.trim() }
                .filter { it.contains('=') }
                .mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    val name = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    if (name.isEmpty()) null else StoredCookie(
                        name = name,
                        value = value,
                        domain = domain,
                        path = "/",
                        expiresAt = null,
                        secure = true,
                        httpOnly = false,
                        hostOnly = false,
                    )
                }
        }
    }
}

private fun Cookie.toStored() = StoredCookie(
    name = name,
    value = value,
    domain = domain,
    path = path,
    expiresAt = if (persistent) expiresAt else null,
    secure = secure,
    httpOnly = httpOnly,
    hostOnly = hostOnly,
)

private fun StoredCookie.toOkHttp(): Cookie {
    val builder = Cookie.Builder().name(name).value(value).path(path)
    if (hostOnly) builder.hostOnlyDomain(domain) else builder.domain(domain)
    if (secure) builder.secure()
    if (httpOnly) builder.httpOnly()
    if (expiresAt != null) builder.expiresAt(expiresAt)
    return builder.build()
}
