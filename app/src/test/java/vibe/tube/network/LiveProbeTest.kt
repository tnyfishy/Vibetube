package vibe.tube.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import vibe.tube.account.AccountSession
import vibe.tube.account.StoredCookie
import vibe.tube.core.AppError
import vibe.tube.core.PlaybackFailure
import vibe.tube.support.Fixtures
import vibe.tube.youtube.InnerTubeProvider
import vibe.tube.youtube.ProviderCall
import vibe.tube.youtube.VerifyResult
import vibe.tube.youtube.model.ContentLocale
import vibe.tube.youtube.model.SearchKind
import vibe.tube.youtube.model.SearchSort
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.player.ResolveResult
import vibe.tube.youtube.player.StreamResolver

class LiveProbeTest {

    private val enabled = System.getenv("VIBETUBE_LIVE") == "1"
    private val locale = ContentLocale("en", "US")

    private fun newClient() = InnerTubeClient(OkHttpClient(), Fixtures.json)

    @Test
    fun guestSearchWatchAndCommentsWorkAgainstTheLiveService() = runBlocking {
        assumeTrue(enabled)
        val provider = InnerTubeProvider(newClient())
        val call = ProviderCall(locale, null)
        val page = provider.search(call, "lofi hip hop", SearchKind.Video, SearchSort.Relevance, null)
        val videos = page.items.filterIsInstance<VideoItem>()
        assertTrue("videos=${videos.size}", videos.size >= 5)
        assertNotNull(page.continuation)
        val next = provider.search(call, "lofi hip hop", SearchKind.Video, SearchSort.Relevance, page.continuation)
        assertTrue(next.items.isNotEmpty())

        val info = provider.watch(call, "dQw4w9WgXcQ", null, null)
        assertTrue(info.title.isNotBlank())
        assertNotNull(info.channel?.id)
        assertTrue(info.related.isNotEmpty())
        val comments = provider.comments(call, info.commentsToken!!)
        assertTrue("comments=${comments.comments.size}", comments.comments.isNotEmpty())
        println("LIVE search=${videos.size} related=${info.related.size} comments=${comments.comments.size} commentsContinuation=${comments.continuation != null}")
    }

    @Test
    fun guestHomeIsRefusedLocallyAndGuestMenuIsLoggedOut() = runBlocking {
        assumeTrue(enabled)
        val provider = InnerTubeProvider(newClient())
        val failure = runCatching { provider.home(ProviderCall(locale, null), null) }.exceptionOrNull()
        assertTrue(failure is AppError.LoginRequired)
        val bogus = AccountSession(
            "x", "n", null, null,
            cookies = listOf(StoredCookie("SAPISID", "not-a-real-cookie", "youtube.com"), StoredCookie("__Secure-3PAPISID", "not-a-real-cookie", "youtube.com")),
            createdAt = 0,
        )
        val result = provider.verify(bogus, locale)
        println("LIVE verify(bogus cookies)=$result")
        assertFalse("bogus cookies must never verify", result is VerifyResult.Verified)
    }

    @Test
    fun publicPlaylistAndChannelPagesParseLive() = runBlocking {
        assumeTrue(enabled)
        val provider = InnerTubeProvider(newClient())
        val call = ProviderCall(locale, null)
        val search = provider.search(call, "music", SearchKind.Playlist, SearchSort.Relevance, null)
        val playlist = search.items.filterIsInstance<vibe.tube.youtube.model.PlaylistItem>().first()
        val detail = provider.playlist(call, playlist.id, null)
        println("LIVE playlist id=${playlist.id} items=${detail.items.size} title=${detail.title.take(40)} continuation=${detail.continuation != null}")
        assertTrue(detail.items.isNotEmpty())
        val more = provider.playlist(call, playlist.id, detail.continuation!!)
        println("LIVE playlist page2 videos=${more.items.size} (recommended-playlist shelves are not videos)")
        assertTrue(more.items.all { it is VideoItem })
        val channel = provider.channel(call, "UCuAXFkgsw1L7xaCfnd5JJOw")
        println("LIVE channel name=${channel.name} handle=${channel.handle} subs=${channel.subscriberText} avatar=${channel.avatar != null}")
        assertTrue(channel.name.isNotBlank())
        val videos = provider.channelVideos(call, channel.id, null)
        println("LIVE channelVideos=${videos.items.size} continuation=${videos.continuation != null}")
        assertTrue(videos.items.isNotEmpty())
    }

    @Test
    fun suggestionsEndpointAnswers() = runBlocking {
        assumeTrue(enabled)
        val provider = InnerTubeProvider(newClient())
        val suggestions = provider.suggestions(locale, "lofi")
        println("LIVE suggestions=$suggestions")
        assertTrue(suggestions.isNotEmpty())
    }

    @Test
    fun playerEndpointIsClassifiedHonestly() = runBlocking {
        assumeTrue(enabled)
        val resolver = StreamResolver(newClient())
        val result = resolver.resolve("dQw4w9WgXcQ", locale, null)
        println("LIVE player=$result".take(400))
        when (result) {
            is ResolveResult.Playable -> {
                val media = result.media
                assertTrue(media.muxed.isNotEmpty() || media.videoOnly.isNotEmpty() || media.hlsUrl != null)
                val plan = vibe.tube.youtube.player.MediaPlanner.plan(media, 720)
                println("LIVE plan=${plan?.kind} height=${plan?.height} heights=${vibe.tube.youtube.player.MediaPlanner.availableHeights(media)} client=${media.clientName} cipherSkipped=${media.skippedCipherFormats}")
                val http = OkHttpClient()
                listOfNotNull(plan?.muxed, plan?.video, plan?.audio).forEach { f ->
                    val request = okhttp3.Request.Builder().url(f.url).header("User-Agent", media.userAgent).header("Range", "bytes=0-2047").build()
                    http.newCall(request).execute().use { r ->
                        println("LIVE stream itag=${f.itag} ${f.mimeType} ${f.height ?: ""} -> HTTP ${r.code} bytes=${r.body.bytes().size}")
                    }
                }
            }
            is ResolveResult.Blocked -> assertTrue(result.failure in PlaybackFailure.entries)
        }
    }
}
