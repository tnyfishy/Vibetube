package vibe.tube.player

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vibe.tube.core.Clock
import vibe.tube.core.PlaybackFailure
import vibe.tube.data.db.AppDatabase
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.repo.LocalHistoryRepository
import vibe.tube.data.repo.ResumeStore
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.feature.common.UiEvents
import vibe.tube.network.InnerTubeClient
import vibe.tube.network.InnerTubeConfig
import vibe.tube.support.TestSessionEnv
import vibe.tube.support.video
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.player.StreamResolver
import java.io.File
import java.io.IOException

/**
 * The tap → resolve → error path of the main player, offline: a tap always ends in a visible state (never a silent,
 * frozen card), a failed video can be asked for again, and a newer request always wins over an older one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackRequestTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private val scopes = mutableListOf<CoroutineScope>()
    private var requests = 0

    @Before
    fun setUp() {
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
    }

    /** Mix pages served at once (or held back, or failing) so the queue side can be told apart from playback. */
    private class MixProvider : vibe.tube.support.FakeProvider() {
        var items: List<vibe.tube.youtube.model.VideoItem> = listOf(video("seed0000001"), video("next0000002"), video("next0000003"))
        var fail = false
        var calls = 0
        /** When set, the first page is held back until it completes (a slow network). */
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun playlist(call: vibe.tube.youtube.ProviderCall, playlistId: String, continuation: String?): vibe.tube.youtube.model.PlaylistDetail {
            calls++
            gate?.await()
            if (fail) throw IOException("down")
            return vibe.tube.youtube.model.PlaylistDetail(playlistId, "Mix", null, null, null, null, items, null)
        }
    }

    private val mixProvider = MixProvider()

    private fun mix(id: String = "RDseed0000001", first: String? = null) = vibe.tube.youtube.model.PlaylistItem(
        id = id, title = "Mix - Song", ownerName = "YouTube", ownerId = null, videoCountText = null,
        thumbnails = listOf(vibe.tube.youtube.model.Thumb("https://i.ytimg.com/vi/seed0000001/hqdefault.jpg", 480, 360)),
        firstVideoId = first,
    )

    private lateinit var engine: PlayerEngine

    /** When set, every request waits for it before failing (a slow network). */
    private var networkGate: java.util.concurrent.CountDownLatch? = null

    private fun controller(): PlayerController {
        val offline = OkHttpClient.Builder().addInterceptor {
            requests++
            networkGate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            throw IOException("offline")
        }.build()
        val client = InnerTubeClient(offline, vibe.tube.support.Fixtures.json, config = InnerTubeConfig(maxAttempts = 1))
        val env = TestSessionEnv(provider = mixProvider)
        val history = LocalHistoryRepository(db.playbackHistory(), db.searchHistory(), Clock { 1_000L })
        val store = ResumeStore(context.getSharedPreferences("resume_req_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        engine = PlayerEngine(context)
        return PlayerController(
            context, engine, StreamResolver(client), env.manager, settings, history, store, env.provider,
            AccountActions(env.provider, env.manager) { ContentLocale("en", "US") }, UiEvents(),
            WatchHistorySync(env.provider, env.manager, settings), offline, vibe.tube.youtube.potoken.NoPoTokens,
        )
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun suggestedNextNeverTemporarilyExpandsTheBackgroundPlayer() {
        val gate = java.util.concurrent.CountDownLatch(1)
        networkGate = gate
        val c = controller()
        c.play(video("first000001"))
        c.collapse()
        c.offerAutoplay(video("second00002"))
        val expanded = mutableListOf<Boolean>()
        val observer = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        val job = observer.launch { c.state.map { it.expanded }.collect { expanded += it } }
        try {
            c.next()
            assertEquals("second00002", c.state.value.current?.id)
            assertTrue("background handoff must never expand: $expanded", expanded.none { it })
        } finally { job.cancel(); gate.countDown(); c.dismiss() }
    }

    @Test
    fun aTapShowsProgressAtOnceAndEndsInANetworkErrorWhenOffline() {
        val c = controller()
        c.play(video("a"))
        val s = c.state.value
        assertTrue(s.visible && s.expanded)
        assertEquals(PlayerPhase.Resolving, s.phase)
        assertTrue(s.phase.isBusy)
        waitUntil { c.state.value.phase == PlayerPhase.Error }
        assertEquals(PlayerPhase.Error, c.state.value.phase)
        assertEquals(PlaybackFailure.Network, c.state.value.error?.failure)
        assertTrue("every client was tried", requests >= StreamResolver.DEFAULT_PROFILES.size)
    }

    @Test
    fun theSessionKeepsReportingPlaybackWhileTheNextStreamIsFetched() {
        val gate = java.util.concurrent.CountDownLatch(1)
        networkGate = gate
        val c = controller()
        engine.player.playWhenReady = true
        c.play(video("a"))
        // ExoPlayer itself is idle (the old stream is gone, the new one not there yet) ...
        assertEquals(androidx.media3.common.Player.STATE_IDLE, engine.player.playbackState)
        // ... but the media session, and so the notification and its foreground service, see playback going on.
        assertTrue(engine.inTransition)
        assertEquals(androidx.media3.common.Player.STATE_BUFFERING, engine.sessionPlayer.playbackState)
        gate.countDown()
        waitUntil { c.state.value.phase == PlayerPhase.Error }
        // Once it failed nothing is on its way: the session says so, and holds no lock any more.
        assertTrue(!engine.inTransition)
        assertEquals(androidx.media3.common.Player.STATE_IDLE, engine.sessionPlayer.playbackState)
        assertTrue(!engine.player.playWhenReady)
    }

    @Test
    fun tappingTheFailedVideoAgainLoadsItAgain() {
        val c = controller()
        c.play(video("a"))
        waitUntil { c.state.value.phase == PlayerPhase.Error }
        val before = requests
        c.collapse()
        c.play(video("a"))
        assertTrue(c.state.value.expanded)
        assertEquals(PlayerPhase.Resolving, c.state.value.phase)
        waitUntil { c.state.value.phase == PlayerPhase.Error }
        assertTrue(requests > before)
    }

    @Test
    fun playOnAFailedVideoRetriesIt() {
        val c = controller()
        c.play(video("a"))
        waitUntil { c.state.value.phase == PlayerPhase.Error }
        c.togglePlay()
        assertEquals(PlayerPhase.Resolving, c.state.value.phase)
        assertEquals(null, c.state.value.error)
    }

    @Test
    fun theNewestTapWinsAndAnOlderFailureNeverOverwritesIt() {
        val c = controller()
        c.play(video("a"))
        c.play(video("b"))
        waitUntil { c.state.value.phase == PlayerPhase.Error }
        assertEquals("b", c.state.value.current?.id)
        // Nothing from "a" arrives later and flips the card back.
        waitUntil(300) { false }
        assertEquals("b", c.state.value.current?.id)
        assertEquals(PlayerPhase.Error, c.state.value.phase)
    }

    @Test
    fun closingThePlayerDropsTheRequestInFlight() {
        val c = controller()
        c.play(video("a"))
        c.dismiss()
        waitUntil(500) { false }
        assertEquals(PlayerPhase.Idle, c.state.value.phase)
        assertEquals(false, c.state.value.visible)
    }

    @Test
    fun aMixOpensThePlayerAtOnceOnItsFirstVideoBeforeAnyQueueArrives() {
        mixProvider.fail = true
        val c = controller()
        c.play(video("other000001"))
        c.playMix(mix())
        val s = c.state.value
        // Same frame as the tap: open, on the Mix's first video, already resolving it, with the card's picture.
        assertTrue(s.visible && s.expanded)
        assertEquals("seed0000001", s.current?.id)
        assertEquals("RDseed0000001", s.playlistId)
        assertEquals(PlayerPhase.Resolving, s.phase)
        assertEquals("https://i.ytimg.com/vi/seed0000001/hqdefault.jpg", s.current?.thumbnails?.first()?.url)
        // The queue failing does not stop the video; only playback itself decides the outcome.
        waitUntil { c.state.value.phase == PlayerPhase.Error }
        assertEquals(PlaybackFailure.Network, c.state.value.error?.failure)
        assertEquals("seed0000001", c.state.value.current?.id)
    }

    @Test
    fun theQueueFillsInWithoutReloadingWhatIsPlaying() {
        val c = controller()
        c.playMix(mix(first = "seed0000001"))
        val before = requests
        waitUntil { c.state.value.queue.size == 3 }
        assertEquals(listOf("seed0000001", "next0000002", "next0000003"), c.state.value.queue.map { it.id })
        assertEquals(0, c.state.value.queueIndex)
        assertEquals("title seed0000001", c.state.value.current?.title)
        // Only the one playback request is in flight or done: filling the queue started no second load.
        waitUntil { c.state.value.phase == PlayerPhase.Error }
        assertTrue(requests >= before)
        assertEquals("seed0000001", c.state.value.current?.id)
    }

    @Test
    fun aMixWithoutAKnownFirstVideoWaitsInThePlayerThenStartsItsFirstEntry() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { mixProvider.gate = it }
        val c = controller()
        c.playMix(mix(id = "RDMMxyz"))
        assertTrue(c.state.value.visible && c.state.value.expanded)
        assertEquals("RDMMxyz", c.state.value.current?.id)
        assertTrue(c.state.value.phase.isBusy)
        waitUntil(200) { false }
        // Slow first page: still the Mix's own card in the player, still busy, nothing resolved for a playlist id.
        assertEquals("RDMMxyz", c.state.value.current?.id)
        assertEquals(0, requests)
        gate.complete(Unit)
        waitUntil { c.state.value.current?.id == "seed0000001" }
        assertEquals("seed0000001", c.state.value.current?.id)
    }

    @Test
    fun aMixThatCannotLoadShowsAnErrorInThePlayerAndRetryReopensIt() {
        mixProvider.fail = true
        val c = controller()
        c.playMix(mix(id = "RDMMxyz"))
        waitUntil { c.state.value.phase == PlayerPhase.Error }
        assertTrue(c.state.value.visible)
        assertEquals("RDMMxyz", c.state.value.current?.id)
        mixProvider.fail = false
        c.retry()
        waitUntil { c.state.value.current?.id == "seed0000001" }
        assertEquals("seed0000001", c.state.value.current?.id)
    }

    @Test
    fun tappingTheMixThatIsAlreadyPlayingOnlyOpensThePlayer() {
        val c = controller()
        c.playMix(mix(first = "seed0000001"))
        waitUntil { c.state.value.queue.size == 3 }
        val calls = mixProvider.calls
        c.collapse()
        c.playMix(mix(first = "seed0000001"))
        assertTrue(c.state.value.expanded)
        assertEquals(calls, mixProvider.calls)
        assertEquals(3, c.state.value.queue.size)
    }
}
