package vibe.tube.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.account.AuthState
import vibe.tube.account.LoginOutcome
import vibe.tube.core.AppError
import vibe.tube.core.Diagnostics
import vibe.tube.data.repo.FeedController
import vibe.tube.data.repo.FeedEnvironment
import vibe.tube.data.repo.FeedFailure
import vibe.tube.data.repo.FeedPhase
import vibe.tube.data.repo.FeedRepository
import vibe.tube.data.repo.FeedSource
import vibe.tube.data.repo.FeedSpec
import vibe.tube.support.FakeProvider
import vibe.tube.support.TestSessionEnv
import vibe.tube.support.appScope
import vibe.tube.support.cookiesFor
import vibe.tube.support.video
import vibe.tube.youtube.ProviderCall
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.model.FeedItem
import vibe.tube.youtube.model.Page

@OptIn(ExperimentalCoroutinesApi::class)
class FeedControllerTest {

    private class HomeProvider : FakeProvider() {
        val calls = mutableListOf<Triple<String?, String?, String?>>()
        var handler: suspend (ProviderCall, String?) -> Page<FeedItem> = { _, _ -> Page(emptyList(), null) }

        val trendingCalls = mutableListOf<String?>()

        override suspend fun trending(call: ProviderCall, continuation: String?): Page<FeedItem> {
            trendingCalls += continuation
            return Page(listOf(video("t1"), video("t2")), null)
        }

        override suspend fun home(call: ProviderCall, continuation: String?): Page<FeedItem> {
            calls += Triple(call.auth?.accountId, continuation, call.auth?.jar?.valueOf("SAPISID"))
            return handler(call, continuation)
        }
    }

    private fun TestScope.setup(provider: HomeProvider = HomeProvider()): Triple<TestSessionEnv, FeedEnvironment, FeedRepository> {
        val env = TestSessionEnv(provider = provider)
        val feedEnv = FeedEnvironment(
            env.manager,
            MutableStateFlow(ContentLocale("en", "US")),
            env.cache,
            Diagnostics(enabled = true),
            provider.name,
            { env.now },
        )
        return Triple(env, feedEnv, FeedRepository(provider, feedEnv))
    }

    @Test
    fun guestSeesTrendingFeedAndPersonalHomeIsNeverCalled() = runTest(StandardTestDispatcher()) {
        val provider = HomeProvider()
        val (_, _, repo) = setup(provider)
        val controller = repo.home(appScope())
        advanceUntilIdle()
        val state = controller.state.value
        assertNull(state.failure)
        assertTrue(state.guest)
        assertEquals(listOf("t1", "t2"), state.items.map { it.id })
        assertTrue(provider.calls.isEmpty())
        assertEquals(1, provider.trendingCalls.size)
    }

    @Test
    fun verifiedAccountLoadsFirstPageThenLoadMoreUsesResponseContinuation() = runTest(StandardTestDispatcher()) {
        val provider = HomeProvider()
        provider.handler = { _, cont ->
            when (cont) {
                null -> Page(listOf(video("a1"), video("a2")), "TOKEN_FROM_PAGE_1")
                "TOKEN_FROM_PAGE_1" -> Page(listOf(video("a3"), video("a2")), "TOKEN_FROM_PAGE_2")
                else -> error("unexpected continuation $cont")
            }
        }
        val (env, _, repo) = setup(provider)
        assertTrue(env.manager.loginWithCookies(cookiesFor("alice")) is LoginOutcome.Success)
        val controller = repo.home(appScope())
        advanceUntilIdle()
        var s = controller.state.value
        assertEquals(listOf("a1", "a2"), s.items.map { it.id })
        assertEquals(FeedSource.Network, s.source)
        assertEquals("TOKEN_FROM_PAGE_1", s.continuation)
        assertEquals(1, provider.calls.size)
        assertEquals("alice", provider.calls[0].third)

        controller.loadMore()
        advanceUntilIdle()
        s = controller.state.value
        assertEquals(listOf("a1", "a2", "a3"), s.items.map { it.id })
        assertEquals("TOKEN_FROM_PAGE_2", s.continuation)
        assertEquals("TOKEN_FROM_PAGE_1", provider.calls[1].second)
    }

    @Test
    fun refreshIssuesNewFirstPageRequestAndReplacesItems() = runTest(StandardTestDispatcher()) {
        val provider = HomeProvider()
        var round = 0
        provider.handler = { _, cont ->
            if (cont == null) {
                round++
                Page(listOf(video("r$round")), "C$round")
            } else Page(emptyList(), null)
        }
        val (env, _, repo) = setup(provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        val controller = repo.home(appScope())
        advanceUntilIdle()
        assertEquals(listOf("r1"), controller.state.value.items.map { it.id })
        controller.refresh()
        advanceUntilIdle()
        assertEquals(listOf("r2"), controller.state.value.items.map { it.id })
        assertEquals("C2", controller.state.value.continuation)
        assertEquals(2, provider.calls.count { it.second == null })
    }

    @Test
    fun switchingAccountCancelsInFlightRequestAndOldResponseNeverOverwrites() = runTest(StandardTestDispatcher()) {
        val provider = HomeProvider()
        val gate = CompletableDeferred<Unit>()
        provider.handler = { call, _ ->
            val who = call.auth!!.jar.valueOf("SAPISID")!!
            if (who == "alice") {
                gate.await()
                Page(listOf(video("alice-video")), "ALICE_TOKEN")
            } else Page(listOf(video("bob-video")), "BOB_TOKEN")
        }
        val (env, _, repo) = setup(provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        val aliceId = env.manager.currentSession()!!.id
        val controller = repo.home(appScope())
        advanceUntilIdle()
        assertEquals(FeedPhase.LoadingFirst, controller.state.value.phase)

        env.manager.loginWithCookies(cookiesFor("bob"))
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        val s = controller.state.value
        assertEquals(listOf("bob-video"), s.items.map { it.id })
        assertEquals("BOB_TOKEN", s.continuation)
        assertTrue(s.scopeKey!!.startsWith(env.manager.currentSession()!!.id))
    }

    @Test
    fun olderRefreshResponseIsDiscardedWhenNewerRefreshWasStarted() = runTest(StandardTestDispatcher()) {
        val provider = HomeProvider()
        val first = CompletableDeferred<Page<FeedItem>>()
        var n = 0
        provider.handler = { _, _ ->
            n++
            if (n == 1) Page(listOf(video("initial")), null)
            else if (n == 2) first.await()
            else Page(listOf(video("newest")), null)
        }
        val (env, _, repo) = setup(provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        val controller = repo.home(appScope())
        advanceUntilIdle()
        controller.refresh()
        advanceUntilIdle()
        controller.refresh()
        advanceUntilIdle()
        first.complete(Page(listOf(video("stale")), null))
        advanceUntilIdle()
        assertEquals(listOf("newest"), controller.state.value.items.map { it.id })
    }

    @Test
    fun sessionExpiryShowsLoginErrorHidesItemsAndDoesNotRetry() = runTest(StandardTestDispatcher()) {
        val provider = HomeProvider()
        provider.handler = { call, _ -> throw AppError.SessionExpired(call.auth!!.accountId) }
        val (env, _, repo) = setup(provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        val controller = repo.home(appScope())
        advanceUntilIdle()
        val s = controller.state.value
        assertEquals(FeedFailure.SessionExpired, s.failure)
        assertTrue(s.items.isEmpty())
        assertEquals(1, provider.calls.size)
        assertTrue(env.manager.authState.value is AuthState.Expired)
        controller.refresh()
        advanceUntilIdle()
        assertEquals(1, provider.calls.size)
        assertEquals(FeedFailure.SessionExpired, controller.state.value.failure)
    }

    @Test
    fun cacheIsPerAccountAndNeverLeaksToAnotherAccount() = runTest(StandardTestDispatcher()) {
        val provider = HomeProvider()
        provider.handler = { call, _ ->
            val who = call.auth!!.jar.valueOf("SAPISID")!!
            Page(listOf(video("$who-video")), null)
        }
        val (env, _, repo) = setup(provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        val c1 = repo.home(appScope())
        advanceUntilIdle()
        assertEquals(1, env.cache.entries.size)

        env.manager.loginWithCookies(cookiesFor("bob"))
        advanceUntilIdle()
        val keys = env.cache.entries.keys.toList()
        assertEquals(2, keys.size)
        assertTrue(keys.map { it.substringBefore('|') }.distinct().size == 2)
        assertEquals(listOf("bob-video"), c1.state.value.items.map { it.id })
    }

    @Test
    fun offlineShowsCachedItemsWithNetworkFailureAndDisablesLoadMore() = runTest(StandardTestDispatcher()) {
        val provider = HomeProvider()
        var offline = false
        provider.handler = { _, _ ->
            if (offline) throw AppError.Network(null, "offline") else Page(listOf(video("cached-1")), "TOK")
        }
        val (env, feedEnv, repo) = setup(provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        val first = repo.home(appScope())
        advanceUntilIdle()
        assertEquals(FeedSource.Network, first.state.value.source)

        offline = true
        val second = FeedController(
            FeedSpec("home", true, true) { c, t -> provider.home(c, t) },
            feedEnv,
            appScope(),
        )
        advanceUntilIdle()
        val s = second.state.value
        assertEquals(listOf("cached-1"), s.items.map { it.id })
        assertEquals(FeedSource.Cache, s.source)
        assertTrue(s.failure is FeedFailure.Network)
        assertNull(s.continuation)
        second.loadMore()
        advanceUntilIdle()
        assertEquals(FeedPhase.Idle, second.state.value.phase)
    }

    @Test
    fun loadMoreFailureKeepsItemsAndAllowsRetryWithSameContinuation() = runTest(StandardTestDispatcher()) {
        val provider = HomeProvider()
        var fail = true
        provider.handler = { _, cont ->
            if (cont == null) Page(listOf(video("x1")), "NEXT")
            else if (fail) throw AppError.Network(503, "boom") else Page(listOf(video("x2")), null)
        }
        val (env, _, repo) = setup(provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        val c = repo.home(appScope())
        advanceUntilIdle()
        c.loadMore()
        advanceUntilIdle()
        assertTrue(c.state.value.loadMoreFailed)
        assertEquals("NEXT", c.state.value.continuation)
        fail = false
        c.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("x1", "x2"), c.state.value.items.map { it.id })
        assertNull(c.state.value.continuation)
    }

    @Test
    fun logoutWipesVaultCacheAndReturnsToGuestWithoutSwitchingAccount() = runTest(StandardTestDispatcher()) {
        val provider = HomeProvider()
        provider.handler = { _, _ -> Page(listOf(video("v")), null) }
        val (env, _, repo) = setup(provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        env.manager.loginWithCookies(cookiesFor("bob"))
        val controller = repo.home(appScope())
        advanceUntilIdle()
        val bobId = env.manager.currentSession()!!.id
        assertTrue(env.cache.entries.isNotEmpty())

        env.manager.logout(bobId)
        advanceUntilIdle()

        assertTrue(env.manager.authState.value is AuthState.Guest)
        assertNull(env.manager.currentSession())
        assertEquals(1, env.vault.loadAll().size)
        assertTrue(env.cache.entries.keys.none { it.startsWith(bobId) })
        assertNull(controller.state.value.failure)
        assertTrue(controller.state.value.guest)
        assertEquals(listOf("t1", "t2"), controller.state.value.items.map { it.id })
        assertTrue(bobId in env.hooks.cancelled)
        assertTrue(bobId in env.hooks.wiped)
    }

    @Test
    fun loginWithLoggedOutCookiesIsRejectedAndStoresNothing() = runTest(StandardTestDispatcher()) {
        val provider = HomeProvider()
        provider.defaultVerify = { vibe.tube.youtube.VerifyResult.LoggedOut }
        val (env, _, _) = setup(provider)
        assertEquals(LoginOutcome.NotLoggedIn, env.manager.loginWithCookies(cookiesFor("ghost")))
        assertTrue(env.vault.loadAll().isEmpty())
        assertTrue(env.manager.authState.value is AuthState.Guest)
    }

    @Test
    fun switchingBetweenAccountsKeepsSessionCookiesIsolated() = runTest(StandardTestDispatcher()) {
        val provider = HomeProvider()
        val (env, _, _) = setup(provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        val aliceId = env.manager.currentSession()!!.id
        env.manager.loginWithCookies(cookiesFor("bob"))
        val bobId = env.manager.currentSession()!!.id
        assertEquals("alice", env.manager.authFor(aliceId)!!.jar.valueOf("SAPISID"))
        assertEquals("bob", env.manager.authFor(bobId)!!.jar.valueOf("SAPISID"))
        env.manager.switchAccount(aliceId)
        assertEquals(aliceId, env.manager.currentSession()!!.id)
        assertEquals("alice", env.manager.activeAuth()!!.jar.valueOf("SAPISID"))
    }
}
