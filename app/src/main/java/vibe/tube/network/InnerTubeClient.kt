package vibe.tube.network

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import vibe.tube.account.AccountTag
import vibe.tube.account.SapisidAuth
import vibe.tube.account.SessionCookieJar
import vibe.tube.core.AppError
import vibe.tube.core.Clock
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.parser.AccountParser
import vibe.tube.youtube.parser.at
import vibe.tube.youtube.parser.str
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RequestAuth(
    val accountId: String,
    val jar: SessionCookieJar,
    val pageId: String?,
    val visitorData: String?,
)

data class InnerTubeConfig(
    val baseUrl: HttpUrl = "https://www.youtube.com/".toHttpUrl(),
    val clientName: String = "WEB",
    val clientNameId: String = "1",
    val clientVersion: String = "2.20260918.01.00",
    val userAgent: String = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
    val maxAttempts: Int = 3,
    val backoffMillis: Long = 400,
)

data class InnerTubeResponse(
    val root: JsonObject,
    val httpCode: Int,
    val loggedOut: Boolean?,
    val visitorData: String?,
)

class InnerTubeClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val clock: Clock = Clock.System,
    private val config: InnerTubeConfig = InnerTubeConfig(),
) {
    private val guestClient: OkHttpClient = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    private val accountClients = ConcurrentHashMap<SessionCookieJar, OkHttpClient>()

    @Volatile
    private var guestVisitorData: String? = null

    val clientConfig: InnerTubeConfig get() = config

    suspend fun post(
        endpoint: String,
        body: JsonObject = JsonObject(emptyMap()),
        locale: ContentLocale = ContentLocale.Default,
        auth: RequestAuth? = null,
        skipLoggedOutCheck: Boolean = false,
    ): InnerTubeResponse {
        val payload = withContext(Dispatchers.Default) { buildBody(body, locale, auth) }
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < config.maxAttempts) {
            attempt++
            try {
                return execute(endpoint, payload, locale, auth, skipLoggedOutCheck)
            } catch (e: AppError.Network) {
                lastError = e
                val retriable = e.httpCode == null || e.httpCode == 429 || e.httpCode >= 500
                if (!retriable || attempt >= config.maxAttempts) throw e
                delay(config.backoffMillis * (1L shl (attempt - 1)))
            }
        }
        throw lastError ?: AppError.Network(null, "unknown")
    }

    suspend fun getText(url: HttpUrl, locale: ContentLocale): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", config.userAgent)
            .header("Accept-Language", locale.tag)
            .build()
        return withContext(Dispatchers.IO) {
            guestClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw AppError.Network(response.code, "http_${response.code}")
                response.body.string()
            }
        }
    }

    private fun buildBody(body: JsonObject, locale: ContentLocale, auth: RequestAuth?): String {
        val visitor = auth?.visitorData ?: guestVisitorData
        val context = buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", config.clientName)
                put("clientVersion", config.clientVersion)
                put("hl", locale.language)
                put("gl", locale.region)
                put("userAgent", config.userAgent)
                put("platform", "DESKTOP")
                if (visitor != null) put("visitorData", visitor)
            })
            put("user", buildJsonObject {
                put("lockedSafetyMode", false)
                if (auth?.pageId != null) put("onBehalfOfUser", auth.pageId)
            })
            put("request", buildJsonObject { put("useSsl", true) })
        }
        val merged = buildJsonObject {
            put("context", context)
            for ((k, v) in body) put(k, v)
        }
        return json.encodeToString(JsonObject.serializer(), merged)
    }

    private suspend fun execute(
        endpoint: String,
        payload: String,
        locale: ContentLocale,
        auth: RequestAuth?,
        skipLoggedOutCheck: Boolean,
    ): InnerTubeResponse {
        val url = config.baseUrl.newBuilder()
            .addPathSegments("youtubei/v1/$endpoint")
            .addQueryParameter("prettyPrint", "false")
            .build()
        val builder = Request.Builder().url(url)
            .post(payload.toRequestBody(JSON_MEDIA))
            .header("User-Agent", config.userAgent)
            .header("Accept-Language", locale.tag)
            .header("Origin", SapisidAuth.ORIGIN)
            .header("X-Origin", SapisidAuth.ORIGIN)
            .header("X-YouTube-Client-Name", config.clientNameId)
            .header("X-YouTube-Client-Version", config.clientVersion)
        val visitor = auth?.visitorData ?: guestVisitorData
        if (visitor != null) builder.header("X-Goog-Visitor-Id", visitor)
        val client: OkHttpClient
        if (auth != null) {
            val header = SapisidAuth.authorization(auth.jar, clock.nowMillis() / 1000)
            if (header != null) {
                builder.header("Authorization", header)
                builder.header("X-Goog-AuthUser", "0")
            }
            if (auth.pageId != null) builder.header("X-Goog-PageId", auth.pageId)
            builder.tag(AccountTag::class.java, AccountTag(auth.accountId))
            client = accountClients.getOrPut(auth.jar) { http.newBuilder().cookieJar(auth.jar).build() }
        } else {
            client = guestClient
        }
        val raw = try {
            withContext(Dispatchers.IO) {
                client.newCall(builder.build()).await().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) {
                        if (auth != null && (response.code == 401 || text.contains("UNAUTHENTICATED"))) {
                            throw AppError.SessionExpired(auth.accountId)
                        }
                        throw AppError.Network(response.code, "http_${response.code}")
                    }
                    response.code to text
                }
            }
        } catch (e: IOException) {
            throw AppError.Network(null, e.message ?: "io", e)
        }
        val root = try {
            withContext(Dispatchers.Default) { json.parseToJsonElement(raw.second) as JsonObject }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AppError.Parse("response_json", e)
        }
        val loggedOut = AccountParser.isLoggedOut(root)
        val responseVisitor = AccountParser.visitorData(root)
        if (auth == null && responseVisitor != null) guestVisitorData = responseVisitor
        if (auth != null && !skipLoggedOutCheck && loggedOut == true) throw AppError.SessionExpired(auth.accountId)
        return InnerTubeResponse(root, raw.first, loggedOut, responseVisitor)
    }

    fun cancelAccount(accountId: String) {
        val clients = accountClients.values.toList()
        clients.forEach { client ->
            (client.dispatcher.queuedCalls() + client.dispatcher.runningCalls())
                .filter { it.request().tag(AccountTag::class.java)?.accountId == accountId }
                .forEach(Call::cancel)
        }
    }

    fun forgetAccount(jar: SessionCookieJar) {
        accountClients.remove(jar)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont: CancellableContinuation<Response> ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

fun JsonElement?.errorStatus(): String? = at("error").at("status").str
