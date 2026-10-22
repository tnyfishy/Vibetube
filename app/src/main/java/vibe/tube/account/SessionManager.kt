package vibe.tube.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vibe.tube.core.AppError
import vibe.tube.core.Clock
import vibe.tube.network.RequestAuth
import vibe.tube.youtube.VerifyResult
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.model.ContentLocale
import java.util.UUID

sealed interface LoginOutcome {
    data class Success(val accountId: String) : LoginOutcome
    data object NotLoggedIn : LoginOutcome
    data class Failed(val error: AppError) : LoginOutcome
}

interface SessionHooks {
    fun cancelRequests(accountId: String)
    suspend fun wipeAccountData(accountId: String)
}

class SessionManager(
    private val vault: SessionVault,
    private val provider: YouTubeProvider,
    private val hooks: SessionHooks,
    private val localeProvider: () -> ContentLocale,
    private val clock: Clock = Clock.System,
) {
    private val verifyMutex = Mutex()
    private val jars = HashMap<String, SessionCookieJar>()
    private val sessionsState = MutableStateFlow<Map<String, AccountSession>>(emptyMap())
    private val activeIdState = MutableStateFlow<String?>(null)
    private val authStateFlow = MutableStateFlow<AuthState>(AuthState.Guest)

    val sessions: StateFlow<Map<String, AccountSession>> = sessionsState.asStateFlow()
    val authState: StateFlow<AuthState> = authStateFlow.asStateFlow()

    private val contextState = MutableStateFlow<AccountContext?>(null)
    val context: StateFlow<AccountContext?> = contextState.asStateFlow()

    fun restore() {
        val all = vault.loadAll().associateBy { it.id }
        all.values.forEach { jarFor(it) }
        sessionsState.value = all
        val saved = vault.loadActiveId()?.takeIf { it in all }
        activeIdState.value = saved
        authStateFlow.value = if (saved != null) AuthState.Unverified(saved, "not_verified_this_run") else AuthState.Guest
        publish()
    }

    private fun publish() {
        contextState.value = currentSession()?.toContext()
    }

    fun currentContext(): AccountContext? = sessionsState.value[activeIdState.value]?.toContext()

    fun currentSession(): AccountSession? = sessionsState.value[activeIdState.value]

    fun authFor(accountId: String): RequestAuth? {
        val session = sessionsState.value[accountId] ?: return null
        val jar = jars[accountId] ?: return null
        return RequestAuth(session.id, jar, session.pageId, session.visitorData)
    }

    fun activeAuth(): RequestAuth? = activeIdState.value?.let(::authFor)

    private fun jarFor(session: AccountSession): SessionCookieJar = synchronized(jars) {
        jars.getOrPut(session.id) {
            SessionCookieJar(session.cookies, clock) { cookies -> onCookiesChanged(session.id, cookies) }
        }
    }

    private fun onCookiesChanged(accountId: String, cookies: List<StoredCookie>) {
        var updated: AccountSession? = null
        sessionsState.update { map ->
            val current = map[accountId] ?: return@update map
            updated = current.copy(cookies = cookies)
            map + (accountId to updated!!)
        }
        updated?.let { vault.save(it) }
    }

    suspend fun loginWithCookies(cookies: List<StoredCookie>): LoginOutcome {
        val draft = AccountSession(
            id = UUID.randomUUID().toString(),
            name = "",
            handle = null,
            avatarUrl = null,
            cookies = cookies,
            createdAt = clock.nowMillis(),
        )
        return when (val result = provider.verify(draft, localeProvider())) {
            is VerifyResult.LoggedOut -> LoginOutcome.NotLoggedIn
            is VerifyResult.Failed -> LoginOutcome.Failed(result.error)
            is VerifyResult.Verified -> {
                val existing = sessionsState.value.values.firstOrNull {
                    it.name == result.profile.name && it.handle == result.profile.handle
                }
                val identities = result.identities
                val session = (existing ?: draft).copy(
                    name = result.profile.name,
                    handle = result.profile.handle,
                    avatarUrl = result.profile.avatarUrl,
                    identities = identities,
                    activeIdentityKey = existing?.activeIdentityKey?.takeIf { key -> identities.any { it.key == key } }
                        ?: identities.firstOrNull { it.pageId == null }?.key
                        ?: AccountSession.PRIMARY_IDENTITY,
                    cookies = result.cookies,
                    visitorData = result.visitorData,
                    verifiedAt = clock.nowMillis(),
                )
                synchronized(jars) {
                    jars.remove(session.id)
                }
                hooks.cancelRequests(session.id)
                jarFor(session)
                vault.save(session)
                vault.saveActiveId(session.id)
                sessionsState.update { it + (session.id to session) }
                activeIdState.value = session.id
                authStateFlow.value = AuthState.Verified(session.id)
                publish()
                LoginOutcome.Success(session.id)
            }
        }
    }

    suspend fun verifyActive(): AuthState = verifyMutex.withLock {
        val session = currentSession() ?: run {
            authStateFlow.value = AuthState.Guest
            return authStateFlow.value
        }
        authStateFlow.value = AuthState.Verifying(session.id)
        when (val result = provider.verify(session, localeProvider())) {
            is VerifyResult.LoggedOut -> authStateFlow.value = AuthState.Expired(session.id)
            is VerifyResult.Failed -> authStateFlow.value = AuthState.Unverified(session.id, result.error.message.orEmpty())
            is VerifyResult.Verified -> {
                if (activeIdState.value != session.id) return authStateFlow.value
                val identities = result.identities.ifEmpty { session.identities }
                val updated = session.copy(
                    name = result.profile.name,
                    handle = result.profile.handle,
                    avatarUrl = result.profile.avatarUrl,
                    identities = identities,
                    activeIdentityKey = session.activeIdentityKey.takeIf { k -> identities.any { it.key == k } }
                        ?: identities.firstOrNull { it.pageId == null }?.key
                        ?: AccountSession.PRIMARY_IDENTITY,
                    visitorData = result.visitorData ?: session.visitorData,
                    verifiedAt = clock.nowMillis(),
                )
                sessionsState.update { it + (updated.id to updated) }
                vault.save(updated)
                publish()
                authStateFlow.value = AuthState.Verified(session.id)
            }
        }
        authStateFlow.value
    }

    suspend fun ensureVerified(): AccountContext? {
        val session = currentSession() ?: return null
        when (val state = authStateFlow.value) {
            is AuthState.Verified -> return session.toContext()
            is AuthState.Expired -> throw AppError.SessionExpired(state.accountId)
            else -> Unit
        }
        return when (val state = verifyActive()) {
            is AuthState.Verified -> currentSession()?.toContext()
            is AuthState.Expired -> throw AppError.SessionExpired(state.accountId)
            is AuthState.Unverified -> throw AppError.Network(null, "session_unverified:${state.reason}")
            else -> null
        }
    }

    fun markExpired(accountId: String) {
        if (activeIdState.value == accountId) authStateFlow.value = AuthState.Expired(accountId)
        hooks.cancelRequests(accountId)
    }

    suspend fun switchAccount(accountId: String) {
        if (accountId !in sessionsState.value || activeIdState.value == accountId) return
        currentSession()?.id?.let(hooks::cancelRequests)
        activeIdState.value = accountId
        vault.saveActiveId(accountId)
        publish()
        authStateFlow.value = AuthState.Unverified(accountId, "switched")
        verifyActive()
    }

    suspend fun switchIdentity(identityKey: String) {
        val session = currentSession() ?: return
        if (session.identities.none { it.key == identityKey } || session.activeIdentityKey == identityKey) return
        hooks.cancelRequests(session.id)
        val updated = session.copy(activeIdentityKey = identityKey)
        sessionsState.update { it + (updated.id to updated) }
        vault.save(updated)
        publish()
    }

    suspend fun logout(accountId: String) {
        hooks.cancelRequests(accountId)
        val wasActive = activeIdState.value == accountId
        synchronized(jars) { jars.remove(accountId)?.clear() }
        sessionsState.update { it - accountId }
        vault.delete(accountId)
        if (wasActive) {
            activeIdState.value = null
            vault.saveActiveId(null)
            authStateFlow.value = AuthState.Guest
            publish()
        }
        hooks.wipeAccountData(accountId)
    }

    suspend fun logoutAll() {
        sessionsState.value.keys.toList().forEach { logout(it) }
        vault.wipe()
    }

    private fun AccountSession.toContext() = AccountContext(
        accountId = id,
        identityKey = activeIdentityKey,
        pageId = pageId,
        displayName = activeIdentity?.name ?: name,
    )
}
