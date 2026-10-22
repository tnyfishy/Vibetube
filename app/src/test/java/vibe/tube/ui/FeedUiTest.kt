package vibe.tube.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vibe.tube.data.repo.FeedFailure
import vibe.tube.data.repo.FeedPhase
import vibe.tube.data.repo.FeedSource
import vibe.tube.data.repo.FeedState
import vibe.tube.data.settings.ThemeMode
import vibe.tube.feature.home.FeedItemContent
import vibe.tube.support.Fixtures
import vibe.tube.ui.components.FeedContent
import vibe.tube.ui.components.LocalVideoActions
import vibe.tube.ui.components.NoOpVideoActions
import vibe.tube.ui.components.VideoActions
import vibe.tube.ui.theme.VibetubeTheme
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.parser.FeedParser
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class FeedUiTest {
    @get:Rule val rule = createComposeRule()

    private val items = FeedParser.parse(Fixtures.json("search_videos.json")).items

    private fun state(vararg mod: (FeedState) -> FeedState) =
        mod.fold(FeedState(items = items, phase = FeedPhase.Idle, source = FeedSource.Network, loadedFromNetwork = true, continuation = "t")) { s, f -> f(s) }

    private fun save(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun content(
        state: FeedState,
        mode: ThemeMode = ThemeMode.Light,
        fontScale: Float = 1f,
        actions: VideoActions = NoOpVideoActions,
        onLogin: () -> Unit = {},
    ) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalVideoActions provides actions,
            ) {
                VibetubeTheme(mode, dynamicColor = false) {
                    FeedContent(
                        state = state,
                        hideShorts = true,
                        onRefresh = {},
                        onLoadMore = {},
                        onLogin = onLogin,
                        guestBanner = state.guest,
                    ) { item, queue -> FeedItemContent(item, queue) }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun cardsRenderAndClickPlaysTheTappedVideo() {
        var played: VideoItem? = null
        val first = items.filterIsInstance<VideoItem>().first()
        content(state(), actions = object : VideoActions by NoOpVideoActions {
            override fun play(video: VideoItem, queue: List<VideoItem>) { played = video }
        })
        rule.onAllNodes(hasContentDescription(first.title, substring = true)).onFirst().assertIsDisplayed().performClick()
        assertEquals(first.id, played?.id)
        save("feed_phone_light")
    }

    @Test
    fun darkAndAmoledRender() {
        content(state(), ThemeMode.Dark); save("feed_phone_dark")
    }

    @Test
    fun largeFontScaleStillShowsContent() {
        val first = items.filterIsInstance<VideoItem>().first()
        content(state(), fontScale = 2f)
        rule.onAllNodes(hasContentDescription(first.title, substring = true)).onFirst().assertIsDisplayed()
        save("feed_phone_font2x")
    }

    @Test
    fun guestStateShowsLoginPromptNotFallbackFeed() {
        var login = 0
        content(FeedState(phase = FeedPhase.Idle, failure = FeedFailure.LoginRequired, guest = true), onLogin = { login++ })
        rule.onNodeWithText("Sign in to see your recommendations").assertIsDisplayed()
        rule.onAllNodes(hasText("Sign in")).onFirst().performClick()
        assertEquals(1, login)
        save("feed_guest")
    }

    @Test
    fun sessionExpiredShowsReloginAction() {
        content(FeedState(phase = FeedPhase.Idle, failure = FeedFailure.SessionExpired))
        rule.onNodeWithText("Your session has expired").assertIsDisplayed()
        rule.onNodeWithText("Sign in again").assertIsDisplayed()
    }

    @Test
    fun offlineCacheBannerIsShownWithItems() {
        content(state({ it.copy(source = FeedSource.Cache, cacheFetchedAt = 1_700_000_000_000, failure = FeedFailure.Network(null)) }))
        rule.onNodeWithText("Couldn't reach YouTube", substring = true).assertIsDisplayed()
    }
}
