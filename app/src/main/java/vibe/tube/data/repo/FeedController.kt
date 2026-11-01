package vibe.tube.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import vibe.tube.account.AuthState
import vibe.tube.account.SessionManager
import vibe.tube.core.AppError
import vibe.tube.core.Clock
import vibe.tube.core.Diagnostics
import vibe.tube.core.DiagnosticEvent
import vibe.tube.core.Redact
import vibe.tube.network.RequestAuth
import vibe.tube.youtube.ProviderCall
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.model.FeedItem
import vibe.tube.youtube.model.Page

enum class FeedSource { Network, Cache }

sealed interface FeedFailure {
    data object LoginRequired : FeedFailure
    data object SessionExpired : FeedFailure
    data class Network(val httpCode: Int?) : FeedFailure
    data object Empty : FeedFailure
    data class Parse(val where: String) : FeedFailure
    data class Other(val message: String) : FeedFailure
}

enum class FeedPhase { Idle, LoadingFirst, Refreshing, LoadingMore }

data class FeedState(
    val items: List<FeedItem> = emptyList(),
    val continuation: String? = null,
    val phase: FeedPhase = FeedPhase.LoadingFirst,
    val source: FeedSource? = null,
    val cacheFetchedAt: Long? = null,
    val failure: FeedFailure? = null,
    val loadMoreFailed: Boolean = false,
    val scopeKey: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val totalText: String? = null,
    val guest: Boolean = false,
    val loadedFromNetwork: Boolean = false,
    val chips: List<vibe.tube.youtube.model.HomeChip> = emptyList(),
) {
    val isEmptyContent: Boolean
        get() = items.isEmpty() && failure == null && phase == FeedPhase.Idle && loadedFromNetwork
}

data class FeedSpec(
    val type: String,
    val requiresAuth: Boolean,
    val cacheable: Boolean,
    val extra: String = "",
    val expectsContent: Boolean = false,
                                                                                          
    val guestFetch: (suspend (ProviderCall, String?) -> Page<FeedItem>)? = null,
    val fetch: suspend (ProviderCall, String?) -> Page<FeedItem>,
) {
    internal fun fetcherFor(guest: Boolean): suspend (ProviderCall, String?) -> Page<FeedItem> =
        if (guest && guestFetch != null) guestFetch else fetch
}

class FeedEnvironment(
    val session: SessionManager,
    val localeFlow: Flow<ContentLocale>,
    val cache: FeedCache,
    val diagnostics: Diagnostics,
    val providerName: String,
    val clock: Clock = Clock.System,
    val maxCacheAgeMillis: Long = 24L * 60 * 60 * 1000,
    val retryDelaysMillis: List<Long> = listOf(400L, 1_200L),
    val emptyRetryDelayMillis: Long = 700L,
)

class FeedController(
    private val spec: FeedSpec,
    private val env: FeedEnvironment,
    scope: CoroutineScope,
) {
    private data class Key(val scope: String, val locale: ContentLocale)

    private val mutableState = MutableStateFlow(FeedState())
    val state: StateFlow<FeedState> = mutableState.asStateFlow()

    private var keyScope: CoroutineScope? = null
    private var currentKey: Key? = null
    private var generation = 0L
    private var activeJob: Job? = null
    private var firstJob: Job? = null
    private var lastNetworkAt = 0L

    init {
        scope.launch {
            combine(env.session.context, env.localeFlow) { ctx, locale -> Key(ctx?.scopeKey ?: GUEST_SCOPE, locale) }
                .distinctUntilChanged()
                .collectLatest { key ->
                    coroutineScope {
                        keyScope = this
                        currentKey = key
                        generation++
                        activeJob = null
                        firstJob = null
                        lastNetworkAt = 0L
                        val isGuest = key.scope == GUEST_SCOPE
                        mutableState.value = FeedState(scopeKey = key.scope, guest = isGuest)
                        initialLoad(key, generation)
                        awaitCancellation()
                    }
                }
        }
    }

    fun refresh() {
        if (firstJob?.isActive == true) return
        reload()
    }

    fun reload(clearItems: Boolean = false) {
        val key = currentKey ?: return
        val scope = keyScope ?: return
        generation++
        val gen = generation
        if (clearItems) mutableState.value = FeedState(scopeKey = key.scope, guest = key.scope == GUEST_SCOPE)
        activeJob?.cancel()
        firstJob?.cancel()
        val job = scope.launch { fetchFirst(key, gen) }
        activeJob = job
        firstJob = job
    }

    fun refreshIfStale(maxAgeMillis: Long) {
        val snapshot = mutableState.value
        if (snapshot.phase != FeedPhase.Idle || snapshot.source != FeedSource.Network || snapshot.failure != null) return
        if (env.clock.nowMillis() - lastNetworkAt < maxAgeMillis) return
        refresh()
    }

    fun retry() = refresh()

    private fun isTransient(e: Exception): Boolean {
        if (e !is AppError.Network) return false
        val code = e.httpCode
        return code == null || code == 408 || code == 429 || code >= 500
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isTransient(e) || attempt >= env.retryDelaysMillis.size) throw e
                delay(env.retryDelaysMillis[attempt++])
            }
        }
    }

    fun loadMore() {
        val key = currentKey ?: return
        val scope = keyScope ?: return
        val snapshot = mutableState.value
        val cont = snapshot.continuation ?: return
        if (snapshot.phase != FeedPhase.Idle || snapshot.source != FeedSource.Network) return
        val gen = generation
        mutableState.value = snapshot.copy(phase = FeedPhase.LoadingMore, loadMoreFailed = false)
        activeJob = scope.launch {
            try {
                val call = buildCall(key) ?: return@launch
                val fetch = spec.fetcherFor(key.scope == GUEST_SCOPE)
                val seen = snapshot.items.mapTo(HashSet()) { it.key() }
                var token: String? = cont
                var fresh: List<FeedItem> = emptyList()
                var hops = 0
                while (token != null && fresh.isEmpty() && hops <= MAX_EMPTY_HOPS) {
                    val page = withRetry { fetch(call, token) }
                    if (!isCurrent(key, gen)) return@launch
                    fresh = page.items.filter { seen.add(it.key()) }
                    token = page.continuation
                    hops++
                }
                val existing = mutableState.value
                mutableState.value = existing.copy(
                    items = existing.items + fresh,
                    continuation = token,
                    phase = FeedPhase.Idle,
                    loadMoreFailed = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.SessionExpired) {
                handleExpired(key, gen, e)
            } catch (e: Exception) {
                if (isCurrent(key, gen)) {
                    mutableState.value = mutableState.value.copy(phase = FeedPhase.Idle, loadMoreFailed = true)
                }
            }
        }
    }

    private fun isCurrent(key: Key, gen: Long) = currentKey == key && generation == gen

    private fun cacheKey(key: Key) = "${key.scope}|${key.locale.tag}|${spec.type}|${spec.extra}"

    private suspend fun initialLoad(key: Key, gen: Long) {
        if (spec.requiresAuth && key.scope == GUEST_SCOPE && spec.guestFetch == null) {
            mutableState.value = mutableState.value.copy(phase = FeedPhase.Idle, failure = FeedFailure.LoginRequired, guest = true)
            return
        }
        if (spec.cacheable) {
            val cached = env.cache.read(cacheKey(key))
            val now = env.clock.nowMillis()
            if (cached != null && now - cached.fetchedAt <= env.maxCacheAgeMillis && isCurrent(key, gen)) {
                mutableState.value = mutableState.value.copy(
                    items = cached.items,
                    source = FeedSource.Cache,
                    cacheFetchedAt = cached.fetchedAt,
                    phase = FeedPhase.Refreshing,
                )
            }
        }
        val scope = keyScope ?: return
        val job = scope.launch { fetchFirst(key, gen) }
        activeJob = job
        firstJob = job
    }

    private suspend fun buildCall(key: Key): ProviderCall? {
        val guestAllowed = key.scope == GUEST_SCOPE && spec.guestFetch != null
        val ctx = if (spec.requiresAuth && !guestAllowed) {
            env.session.ensureVerified() ?: return null
        } else {
            env.session.currentContext()?.takeIf { env.session.authState.value is AuthState.Verified }
        }
        val auth: RequestAuth? = ctx?.let { env.session.authFor(it.accountId) }
        if (ctx != null && ctx.scopeKey != key.scope) throw AppError.Stale()
        return ProviderCall(key.locale, auth)
    }

    private suspend fun fetchFirst(key: Key, gen: Long) {
        val started = env.clock.nowMillis()
        val before = mutableState.value
        mutableState.value = before.copy(
            phase = if (before.items.isEmpty()) FeedPhase.LoadingFirst else FeedPhase.Refreshing,
            failure = null,
            loadMoreFailed = false,
        )
        var httpCode: Int? = null
        var parserError: String? = null
        var expired = false
        var page: Page<FeedItem>? = null
        try {
            val call = buildCall(key) ?: run {
                mutableState.value = mutableState.value.copy(phase = FeedPhase.Idle, failure = FeedFailure.LoginRequired)
                return
            }
            val fetch = spec.fetcherFor(key.scope == GUEST_SCOPE)
            suspend fun firstPage(): Page<FeedItem> {
                var page = withRetry { fetch(call, null) }
                var guard = 0
                while (page.items.isEmpty() && page.continuation != null && guard < MAX_EMPTY_HOPS) {
                    guard++
                    page = withRetry { fetch(call, page.continuation) }
                }
                return page
            }
            var result = firstPage()
            if (result.items.isEmpty() && spec.expectsContent) {
                delay(env.emptyRetryDelayMillis)
                if (!isCurrent(key, gen)) return
                result = firstPage()
            }
            if (spec.type == "home" && before.items.isNotEmpty()) {
                val known = before.items.mapTo(HashSet()) { it.key() }
                var hops = 0
                while (result.continuation != null && hops < 2 && result.items.count { it.key() !in known } < result.items.size / 2) {
                    hops++
                    val more = withRetry { fetch(call, result.continuation) }
                    val (fresh, seenBefore) = (result.items + more.items).distinctBy { it.key() }.partition { it.key() !in known }
                    result = result.copy(items = fresh + seenBefore, continuation = more.continuation)
                }
            }
            if (spec.type == "channels" && before.items.isNotEmpty()) {
                val freshById = result.items.associateBy { it.key() }
                result = result.copy(items = (before.items.map { freshById[it.key()] ?: it } + result.items).distinctBy { it.key() })
            }
            page = result
            if (!isCurrent(key, gen)) return
            if (result.items.isEmpty() && spec.expectsContent) {
                mutableState.value = mutableState.value.copy(phase = FeedPhase.Idle, failure = FeedFailure.Empty)
                return
            }
            lastNetworkAt = env.clock.nowMillis()
            mutableState.value = FeedState(
                items = result.items,
                chips = result.chips,
                continuation = result.continuation,
                phase = FeedPhase.Idle,
                source = FeedSource.Network,
                scopeKey = key.scope,
                title = result.title,
                subtitle = result.subtitle,
                totalText = result.totalText,
                guest = key.scope == GUEST_SCOPE,
                loadedFromNetwork = true,
            )
            if (spec.cacheable && key.scope != GUEST_SCOPE) {
                env.cache.write(cacheKey(key), key.scope, spec.type, result.items, env.clock.nowMillis())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError.SessionExpired) {
            expired = true
            handleExpired(key, gen, e)
        } catch (e: AppError.Stale) {
            return
        } catch (e: Exception) {
            val failure = when (e) {
                is AppError.Network -> FeedFailure.Network(e.httpCode).also { httpCode = e.httpCode }
                is AppError.Parse -> FeedFailure.Parse(e.where).also { parserError = e.where }
                is AppError.LoginRequired -> FeedFailure.LoginRequired
                else -> FeedFailure.Other(e.message.orEmpty())
            }
            if (isCurrent(key, gen)) {
                val current = mutableState.value
                mutableState.value = current.copy(phase = FeedPhase.Idle, failure = failure)
            }
        } finally {
            env.diagnostics.record(
                DiagnosticEvent(
                    timestamp = env.clock.nowMillis(),
                    feedType = spec.type,
                    source = if (page != null) "network" else if (mutableState.value.source == FeedSource.Cache) "cache" else "none",
                    provider = env.providerName,
                    sessionVerified = env.session.authState.value is AuthState.Verified,
                    accountRef = Redact.anonymize(key.scope),
                    durationMs = env.clock.nowMillis() - started,
                    hasContinuation = page?.continuation != null,
                    itemCount = page?.items?.size ?: mutableState.value.items.size,
                    httpCode = httpCode,
                    parserError = parserError,
                    sessionExpired = expired,
                    adsSkipped = page?.adsSkipped ?: 0,
                ),
            )
        }
    }

    private fun handleExpired(key: Key, gen: Long, e: AppError.SessionExpired) {
        e.accountId?.let(env.session::markExpired)
        if (isCurrent(key, gen)) {
            mutableState.value = FeedState(
                phase = FeedPhase.Idle,
                failure = FeedFailure.SessionExpired,
                scopeKey = key.scope,
            )
        }
    }

    private fun FeedItem.key(): String = when (this) {
        is vibe.tube.youtube.model.PlaylistItem -> "pl:$id"
        is vibe.tube.youtube.model.ChannelItem -> "ch:$id"
        else -> id
    }

    companion object {
        const val GUEST_SCOPE = "guest"
        private const val MAX_EMPTY_HOPS = 3
    }
}
