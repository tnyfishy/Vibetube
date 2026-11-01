package vibe.tube.feature

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vibe.tube.core.Clock
import vibe.tube.core.Diagnostics
import vibe.tube.data.db.AppDatabase
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.repo.FeedEnvironment
import vibe.tube.data.repo.FeedRepository
import vibe.tube.data.repo.LocalHistoryRepository
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.feature.search.SearchViewModel
import vibe.tube.support.FakeProvider
import vibe.tube.support.TestSessionEnv
import vibe.tube.support.cookiesFor
import vibe.tube.support.video
import vibe.tube.youtube.ProviderCall
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.model.FeedItem
import vibe.tube.youtube.model.Page
import vibe.tube.youtube.model.SearchKind
import vibe.tube.youtube.model.SearchSort
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchHistoryTest {

    private class SearchProvider : FakeProvider() {
        val suggestionCalls = mutableListOf<String>()
        val searchAuth = mutableListOf<String?>()
        var fail = false

        override suspend fun suggestions(locale: ContentLocale, query: String): List<String> {
            suggestionCalls += query
            if (fail) throw IOException("down")
            return listOf("$query one", "$query two")
        }

        override suspend fun search(call: ProviderCall, query: String, kind: SearchKind, sort: SearchSort, continuation: String?): Page<FeedItem> {
            searchAuth += call.auth?.accountId
            return Page(listOf(video("r1")), null)
        }
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        val file = File.createTempFile("settings", ".preferences_pb").apply { delete() }
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }), scope)
    }

    @After
    fun tearDown() {
        scopes.forEach { it.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        db.close()
        Dispatchers.resetMain()
    }

    private fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun viewModel(provider: SearchProvider, env: TestSessionEnv): SearchViewModel {
        val feedEnv = FeedEnvironment(
            env.manager, MutableStateFlow(ContentLocale("en", "US")), env.cache, Diagnostics(enabled = true), provider.name, { env.now },
            retryDelaysMillis = emptyList(),
        )
        val repo = FeedRepository(provider, feedEnv)
        val history = LocalHistoryRepository(db.playbackHistory(), db.searchHistory(), Clock { env.now })
        return SearchViewModel(
            SavedStateHandle(mapOf("query" to "")), repo, provider,
            AccountActions(provider, env.manager) { ContentLocale("en", "US") }, history, settings, env.manager,
            vibe.tube.data.repo.ChannelSubscriptionStore(),
        )
    }

    @Test
    fun openingSearchSignedInFetchesNothingFromTheAccount() = runTest(dispatcher) {
        val provider = SearchProvider()
        val env = TestSessionEnv(provider = provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        viewModel(provider, env)
        Thread.sleep(300)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // No empty-query "history" request, nor any other request, just for opening the screen.
        assertTrue(provider.suggestionCalls.isEmpty())
        assertTrue(provider.searchAuth.isEmpty())
    }

    @Test
    fun suggestionsStillWorkForTypedText() = runTest(dispatcher) {
        val provider = SearchProvider()
        val env = TestSessionEnv(provider = provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        val vm = viewModel(provider, env)
        backgroundScope.launch(dispatcher) { vm.suggestions.collect { } }
        vm.onInput("lofi")
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertEquals(listOf("lofi one", "lofi two"), vm.suggestions.value)
        assertTrue(provider.suggestionCalls.none { it.isBlank() })
    }

    @Test
    fun typingNeverWritesHistoryButSubmittingDoes() = runTest(dispatcher) {
        val provider = SearchProvider()
        val env = TestSessionEnv(provider = provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        val vm = viewModel(provider, env)
        backgroundScope.launch(dispatcher) { vm.recent.collect { } }
        val searchesBefore = provider.searchAuth.size
        "kitten".indices.forEach { i -> vm.onInput("kitten".substring(0, i + 1)) }
        assertEquals(searchesBefore, provider.searchAuth.size)
        assertTrue(vm.recent.value.isEmpty())
        vm.submit("kitten")
        assertEquals(searchesBefore + 2, provider.searchAuth.size)
        // Sent without the account: YouTube does not add it to the account's search history.
        assertTrue(provider.searchAuth.drop(searchesBefore).all { it == null })
        waitUntil { vm.recent.value.isNotEmpty() }
        assertEquals(listOf("kitten"), vm.recent.value)
    }

    @Test
    fun privateModeKeepsNothingLocallyEither() = runTest(dispatcher) {
        val provider = SearchProvider()
        val env = TestSessionEnv(provider = provider)
        env.manager.loginWithCookies(cookiesFor("alice"))
        settings.update { it.copy(privateMode = true) }
        val vm = viewModel(provider, env)
        backgroundScope.launch(dispatcher) { vm.recent.collect { } }
        vm.submit("secret")
        assertTrue(provider.searchAuth.all { it == null })
        Thread.sleep(300)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(vm.recent.value.isEmpty())
    }
}
