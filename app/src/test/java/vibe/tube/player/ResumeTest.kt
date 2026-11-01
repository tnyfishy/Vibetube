package vibe.tube.player

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vibe.tube.core.Clock
import vibe.tube.data.db.AppDatabase
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.repo.FeedController
import vibe.tube.data.repo.LocalHistoryRepository
import vibe.tube.data.repo.ResumeRecord
import vibe.tube.data.repo.ResumeStore
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.feature.common.UiEvents
import vibe.tube.network.InnerTubeClient
import vibe.tube.support.TestSessionEnv
import vibe.tube.support.video
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.player.StreamResolver
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResumeTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var history: LocalHistoryRepository
    private lateinit var store: ResumeStore
    private lateinit var env: TestSessionEnv
    private lateinit var settings: SettingsRepository
    private val scopes = mutableListOf<CoroutineScope>()
    private val guest = FeedController.GUEST_SCOPE

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        history = LocalHistoryRepository(db.playbackHistory(), db.searchHistory(), Clock { 1_000L })
        store = ResumeStore(context.getSharedPreferences("resume_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        env = TestSessionEnv()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        val file = File.createTempFile("settings", ".preferences_pb").apply { delete() }
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }), scope)
    }

    @After
    fun tearDown() {
        scopes.forEach { it.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        db.close()
    }

    private fun controller(): PlayerController {
        val offline = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
        val client = InnerTubeClient(offline, vibe.tube.support.Fixtures.json)
        val events = UiEvents()
        return PlayerController(
            context, PlayerEngine(context), StreamResolver(client), env.manager, settings, history, store, env.provider,
            AccountActions(env.provider, env.manager) { ContentLocale("en", "US") }, events,
            WatchHistorySync(env.provider, env.manager, settings), offline, vibe.tube.youtube.potoken.NoPoTokens,
        )
    }

    private fun waitUntil(timeoutMs: Long = 4_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun record(id: String, positionMs: Long, durationMs: Long = 300_000) =
        ResumeRecord(id, "Title $id", "Channel", "https://i.ytimg.com/vi/$id/hq.jpg", positionMs, durationMs)

    private fun decide(
        state: PlayerUiState,
        pending: Boolean = false,
        position: Long = 60_000,
        duration: Long = 300_000,
        completed: Boolean = false,
        records: Boolean = true,
    ) = ResumePolicy.decide(state, records, pending, position, duration, completed)

    @Test
    fun policyRecordsOnlyRealPartialWatchesOfNormalVideos() {
        val playing = PlayerUiState(visible = true, current = video("a"), phase = PlayerPhase.Ready)
        assertEquals(ResumePolicy.Decision.Record, decide(playing))
        assertEquals(ResumePolicy.Decision.Ignore, decide(playing, records = false))
        assertEquals(ResumePolicy.Decision.Ignore, decide(playing, position = 5_000))
        assertEquals(ResumePolicy.Decision.Record, decide(playing, pending = true, position = 5_000))
        assertEquals(ResumePolicy.Decision.Clear, decide(playing, position = 290_000))
        assertEquals(ResumePolicy.Decision.Clear, decide(playing, completed = true))
        assertEquals(ResumePolicy.Decision.Ignore, decide(playing, position = 30_000, duration = 60_000))
        assertEquals(ResumePolicy.Decision.Ignore, decide(playing.copy(isLive = true)))
        assertEquals(ResumePolicy.Decision.Ignore, decide(playing.copy(staged = true)))
        assertEquals(ResumePolicy.Decision.Ignore, decide(playing.copy(visible = false)))
        assertEquals(ResumePolicy.Decision.Ignore, decide(playing.copy(current = video("s").copy(isShort = true))))
        assertEquals(ResumePolicy.Decision.Ignore, decide(playing.copy(current = null)))
    }

    @Test
    fun storeRoundTripsTheLatestPositionAndIsPerScope() {
        store.put(guest, record("v1", 60_000))
        store.put(guest, record("v1", 120_000))
        store.put("acc/primary", record("v2", 30_000))
        assertEquals(120_000L, store.get(guest)?.positionMs)
        assertEquals("v2", store.get("acc/primary")?.videoId)
        store.clearVideo(guest, "other")
        assertEquals("v1", store.get(guest)?.videoId)
        store.clearVideo(guest, "v1")
        assertNull(store.get(guest))
        store.clearAccount("acc")
        assertNull(store.get("acc/primary"))
    }

    @Test
    fun nothingPendingMeansNoCard() {
        val c = controller()
        waitUntil(300) { c.state.value.visible }
        assertFalse(c.state.value.visible)
    }

    @Test
    fun coldStartShowsTheCardImmediatelyWithProgress() {
        store.put(guest, record("v1", 60_000))
        val c = controller()
        val s = c.state.value
        assertTrue(s.visible && !s.expanded && s.staged)
        assertEquals("v1", s.current?.id)
        assertEquals(60_000L, s.positionMs)
        assertEquals(300_000L, s.durationMs)
        assertEquals(60_000L, store.get(guest)?.positionMs)
    }

    @Test
    fun tappingTheCardKeepsTheProgressRecord() {
        store.put(guest, record("v1", 60_000))
        val c = controller()
        c.togglePlay()
        assertFalse(c.state.value.staged)
        assertEquals("v1", c.state.value.current?.id)
        assertEquals("v1", store.get(guest)?.videoId)
        assertEquals(60_000L, store.get(guest)?.positionMs)
    }

    @Test
    fun openingTheCardKeepsTheRecordAndExpandsThePlayer() {
        store.put(guest, record("v1", 60_000))
        val c = controller()
        c.expand()
        assertTrue(c.state.value.expanded)
        assertFalse(c.state.value.staged)
        assertEquals("v1", store.get(guest)?.videoId)
    }

    @Test
    fun theSecondLaunchShowsTheLatestPositionAgain() {
        store.put(guest, record("v1", 60_000))
        val first = controller()
        first.expand()
        store.put(guest, record("v1", 150_000))
        val second = controller()
        assertTrue(second.state.value.staged)
        assertEquals(150_000L, second.state.value.positionMs)
        assertEquals("v1", second.state.value.current?.id)
    }

    @Test
    fun collapsingOrReopeningInTheSameProcessNeverBringsTheCardBack() {
        store.put(guest, record("v1", 60_000))
        val c = controller()
        c.expand()
        c.collapse()
        assertFalse(c.state.value.staged)
        assertTrue(c.state.value.visible)
        c.expand()
        assertFalse(c.state.value.staged)
    }

    @Test
    fun closingTheCardDiscardsIt() {
        store.put(guest, record("v1", 60_000))
        val c = controller()
        c.dismiss()
        assertFalse(c.state.value.visible)
        assertNull(store.get(guest))
        assertFalse(controller().state.value.visible)
    }

    @Test
    fun finishedOrTinyProgressIsNeverOffered() {
        store.put(guest, record("done", 295_000))
        val c = controller()
        assertFalse(c.state.value.visible)
        assertNull(store.get(guest))
        store.put(guest, record("tiny", 4_000))
        assertFalse(controller().state.value.visible)
    }

    @Test
    fun pendingIsPerAccount() {
        store.put("other/primary", record("mine", 60_000))
        val c = controller()
        assertFalse(c.state.value.visible)
        assertEquals("mine", store.get("other/primary")?.videoId)
    }

    @Test
    fun removingTheVideoFromHistoryDropsItsCard() = runBlocking {
        val tracked = LocalHistoryRepository(db.playbackHistory(), db.searchHistory(), Clock { 1_000L }, store)
        store.put(guest, record("v1", 60_000))
        tracked.removePlayback(guest, "v1")
        assertNull(store.get(guest))
        store.put(guest, record("v2", 60_000))
        tracked.clearPlayback(guest)
        assertNull(store.get(guest))
    }
}
