package vibe.tube.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vibe.tube.core.Clock
import vibe.tube.data.db.AppDatabase
import vibe.tube.data.repo.LocalHistoryRepository
import vibe.tube.data.repo.RoomFeedCache
import vibe.tube.data.settings.AppSettings
import vibe.tube.data.settings.QualityCap
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.data.settings.ThemeMode
import vibe.tube.support.Fixtures
import vibe.tube.support.video
import vibe.tube.youtube.model.ChannelItem
import vibe.tube.youtube.model.PlaylistItem
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersistenceTest {

    private lateinit var db: AppDatabase
    private var now = 1_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun feedCacheRoundTripsPolymorphicItemsAndIsolatesAccounts() = runBlocking {
        val cache = RoomFeedCache(db.feedCache(), Fixtures.json)
        val items = listOf(
            video("a"),
            PlaylistItem("PL1", "pl", "me", null, "3 videos", emptyList()),
            ChannelItem("UC1", "ch", "@h", emptyList(), "1M", null),
        )
        cache.write("acc1/primary|en-US|home|", "acc1/primary", "home", items, 5)
        cache.write("acc2/primary|en-US|home|", "acc2/primary", "home", listOf(video("b")), 6)
        assertEquals(items, cache.read("acc1/primary|en-US|home|")!!.items)
        assertNull(cache.read("acc1/primary|vi-VN|home|"))
        cache.clearAccount("acc1")
        assertNull(cache.read("acc1/primary|en-US|home|"))
        assertEquals(listOf("b"), cache.read("acc2/primary|en-US|home|")!!.items.map { it.id })
        cache.purgeOlderThan(100)
        assertNull(cache.read("acc2/primary|en-US|home|"))
    }

    @Test
    fun localHistoryTracksResumeCompletionAndScopes() = runBlocking {
        val repo = LocalHistoryRepository(db.playbackHistory(), db.searchHistory(), Clock { now })
        repo.recordProgress("a1/primary", "v1", "T", "C", null, 100, 40_000, 100_000)
        assertEquals(40_000L, repo.resumePosition("a1/primary", "v1"))
        assertNull(repo.resumePosition("a2/primary", "v1"))
        repo.recordProgress("a1/primary", "v1", "T", "C", null, 100, 95_000, 100_000)
        assertNull(repo.resumePosition("a1/primary", "v1"))
        repo.recordProgress("a1/primary", "v2", "T2", null, null, 50, 2_000, 50_000)
        assertNull(repo.resumePosition("a1/primary", "v2"))
        assertEquals(2, repo.recentPlayback("a1/primary").first().size)
        repo.recordProgress("a2/primary", "v9", "T9", null, null, 50, 20_000, 50_000)
        repo.clearAccount("a1")
        assertTrue(repo.recentPlayback("a1/primary").first().isEmpty())
        assertEquals(1, repo.recentPlayback("a2/primary").first().size)
    }

    @Test
    fun searchHistoryIsPerScopeDedupedAndClearable() = runBlocking {
        val repo = LocalHistoryRepository(db.playbackHistory(), db.searchHistory(), Clock { now++ })
        repo.recordSearch("guest", "lofi")
        repo.recordSearch("guest", "jazz")
        repo.recordSearch("guest", "lofi")
        repo.recordSearch("guest", "   ")
        repo.recordSearch("a1/primary", "private")
        assertEquals(listOf("lofi", "jazz"), repo.recentSearches("guest").first())
        repo.removeSearch("guest", "jazz")
        assertEquals(listOf("lofi"), repo.recentSearches("guest").first())
        repo.clearSearches("guest")
        assertTrue(repo.recentSearches("guest").first().isEmpty())
        assertEquals(listOf("private"), repo.recentSearches("a1/primary").first())
    }

    @Test
    fun settingsPersistAcrossRepositoryInstances() = runBlocking {
        val file = File.createTempFile("settings", ".preferences_pb").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }), scope)
        assertEquals(AppSettings(), first.settings.first())
        first.update { it.copy(themeMode = ThemeMode.Amoled, dynamicColor = false, mobileQuality = QualityCap.P360, privateMode = true, contentRegion = "VN", uiLanguage = "vi") }
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val second = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope2, produceFile = { file }), scope2)
        val s = second.settings.first()
        assertEquals(ThemeMode.Amoled, s.themeMode)
        assertFalse(s.dynamicColor)
        assertEquals(QualityCap.P360, s.mobileQuality)
        assertTrue(s.privateMode)
        assertFalse(s.recordsLocalHistory)
        assertEquals("VN", s.contentLocale(Locale("en", "US")).region)
        assertEquals("vi", s.uiLanguage)
        scope2.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        file.delete()
        Unit
    }

    @Test
    fun contentLocaleSeparatesLanguageFromRegionAndFallsBackToDevice() {
        val device = Locale("vi", "VN")
        assertEquals("vi-VN", AppSettings().contentLocale(device).tag)
        assertEquals("en-VN", AppSettings(contentLanguage = "en").contentLocale(device).tag)
        assertEquals("vi-JP", AppSettings(contentRegion = "JP").contentLocale(device).tag)
        assertEquals("en-US", AppSettings().contentLocale(Locale.ROOT).tag)
    }
}
