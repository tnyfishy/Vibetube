package vibe.tube.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.account.AuthState
import vibe.tube.core.AppError
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.repo.ToggleResult
import vibe.tube.data.repo.optimisticToggle
import vibe.tube.support.FakeProvider
import vibe.tube.support.TestSessionEnv
import vibe.tube.support.cookiesFor
import vibe.tube.youtube.ProviderCall
import vibe.tube.youtube.model.ContentLocale

class ActionsTest {

    @Test
    fun successKeepsOptimisticValueAndReportsConfirmed() = runTest {
        var shown = false
        val result = optimisticToggle(false, true, { shown = it }, { })
        assertEquals(ToggleResult.Confirmed(true), result)
        assertTrue(shown)
    }

    @Test
    fun serverRejectionRollsBackAndSurfacesTheError() = runTest {
        val seen = mutableListOf<Boolean>()
        val error = AppError.Rejected("like", "nope")
        val result = optimisticToggle(false, true, { seen += it }, { throw error })
        assertEquals(listOf(true, false), seen)
        assertTrue(result is ToggleResult.RolledBack)
        assertEquals(error, (result as ToggleResult.RolledBack).error)
        assertEquals(false, result.value)
    }

    @Test
    fun cancellationAlsoRollsBackAndPropagates() = runTest {
        val seen = mutableListOf<Boolean>()
        val thrown = runCatching { optimisticToggle(true, false, { seen += it }, { throw CancellationException("c") }) }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(listOf(false, true), seen)
    }

    private class LikeProvider : FakeProvider() {
        var calls = 0
        var failure: Throwable? = null
        override suspend fun like(call: ProviderCall, videoId: String, liked: Boolean) {
            calls++
            failure?.let { throw it }
        }
    }

    @Test
    fun guestActionsAreRefusedWithoutNetwork() = runTest {
        val provider = LikeProvider()
        val env = TestSessionEnv(provider = provider)
        val actions = AccountActions(provider, env.manager) { ContentLocale("en", "US") }
        val failure = runCatching { actions.like("v", true) }.exceptionOrNull()
        assertTrue(failure is AppError.LoginRequired)
        assertEquals(0, provider.calls)
    }

    @Test
    fun sessionExpiryDuringActionMarksSessionExpired() = runTest {
        val provider = LikeProvider()
        val env = TestSessionEnv(provider = provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        val id = env.manager.currentSession()!!.id
        provider.failure = AppError.SessionExpired(id)
        val actions = AccountActions(provider, env.manager) { ContentLocale("en", "US") }
        val failure = runCatching { actions.like("v", true) }.exceptionOrNull()
        assertTrue(failure is AppError.SessionExpired)
        assertTrue(env.manager.authState.value is AuthState.Expired)
        val second = runCatching { actions.like("v", true) }.exceptionOrNull()
        assertTrue(second is AppError.SessionExpired)
        assertEquals(1, provider.calls)
    }

    @Test
    fun likeIsSentWithTheActiveAccountsCredentialsOnly() = runTest {
        val seen = mutableListOf<String?>()
        val provider = object : FakeProvider() {
            override suspend fun like(call: ProviderCall, videoId: String, liked: Boolean) {
                seen += call.auth?.jar?.valueOf("SAPISID")
            }
        }
        val env = TestSessionEnv(provider = provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        val aliceId = env.manager.currentSession()!!.id
        env.manager.loginWithCookies(cookiesFor("bob"))
        val actions = AccountActions(provider, env.manager) { ContentLocale("en", "US") }
        actions.like("v", true)
        env.manager.switchAccount(aliceId)
        actions.like("v", true)
        assertEquals(listOf("bob", "alice"), seen)
    }
}
