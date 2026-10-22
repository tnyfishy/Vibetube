package vibe.tube.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vibe.tube.support.Fixtures
import vibe.tube.youtube.model.ChannelItem
import vibe.tube.youtube.model.LikeStatus
import vibe.tube.youtube.model.PlaylistItem
import vibe.tube.youtube.model.VideoItem
import vibe.tube.youtube.parser.AccountParser
import vibe.tube.youtube.parser.CommentsParser
import vibe.tube.youtube.parser.FeedParser
import vibe.tube.youtube.parser.ItemParsers
import vibe.tube.youtube.parser.SuggestParser
import vibe.tube.youtube.parser.WatchParser

class ParserTest {

    @Test
    fun searchResultsParseVideosWithMetadataAndContinuation() {
        val page = FeedParser.parse(Fixtures.json("search_videos.json"))
        val videos = page.items.filterIsInstance<VideoItem>()
        assertTrue(videos.size >= 8)
        videos.forEach {
            assertTrue(it.id.length == 11)
            assertTrue(it.title.isNotBlank())
            assertTrue(it.thumbnails.isNotEmpty())
            assertTrue(it.thumbnails.all { t -> t.url.startsWith("https://") })
        }
        val first = videos.first()
        assertNotNull(first.channelName)
        assertNotNull(first.channelId)
        assertNotNull(first.durationSeconds)
        assertNotNull(page.continuation)
        assertEquals(videos.map { it.id }.distinct().size, videos.size)
    }

    @Test
    fun lockupVideosAreParsedFromSearchResults() {
        val page = FeedParser.parse(Fixtures.json("search_videos.json"))
        val lockups = page.items.filterIsInstance<VideoItem>().filter { it.channelAvatar != null }
        assertTrue(lockups.isNotEmpty())
    }

    @Test
    fun playlistSearchProducesPlaylistItems() {
        val items = FeedParser.parse(Fixtures.json("search_playlists.json")).items
        assertTrue(items.isNotEmpty())
        items.forEach {
            it as PlaylistItem
            assertTrue(it.id.startsWith("PL") || it.id.startsWith("RD") || it.id.startsWith("OL") || it.id.length > 10)
            assertTrue(it.title.isNotBlank())
            assertNotNull(it.videoCountText)
            assertTrue(it.thumbnails.isNotEmpty())
        }
    }

    @Test
    fun channelSearchProducesChannelItems() {
        val items = FeedParser.parse(Fixtures.json("search_channels.json")).items
        assertTrue(items.isNotEmpty())
        items.forEach {
            it as ChannelItem
            assertTrue(it.id.startsWith("UC"))
            assertTrue(it.name.isNotBlank())
            assertTrue(it.avatars.all { t -> t.url.startsWith("https://") })
        }
    }

    @Test
    fun playlistPageParsesVideosAndContinuationViewModel() {
        val page = FeedParser.parse(Fixtures.json("playlist_page.json"))
        val videos = page.items.filterIsInstance<VideoItem>()
        assertEquals(8, videos.size)
        assertNotNull(page.continuation)
        assertTrue(page.continuation!!.length > 20)
    }

    @Test
    fun guestHomeHasNoItemsAndNoFallback() {
        val page = FeedParser.parse(Fixtures.json("home_guest.json"))
        assertTrue(page.items.isEmpty())
        assertNull(page.continuation)
    }

    @Test
    fun homeFeedStructureSkipsAdsAndKeepsOrder() {
        val page = FeedParser.parse(Fixtures.json("home_logged_in_structure.json"))
        assertFalse(page.items.any { it.id.startsWith("AD_SYNTH") })
        assertEquals(1, page.adsSkipped)
        assertEquals("HOME_PAGE_2_TOKEN", page.continuation)
        assertEquals(4, page.items.size)
    }

    @Test
    fun homeContinuationResponseYieldsNextToken() {
        val page = FeedParser.parse(Fixtures.json("home_logged_in_page2_structure.json"))
        assertEquals("HOME_PAGE_3_TOKEN", page.continuation)
        assertEquals(2, page.items.size)
        assertEquals(1, page.adsSkipped)
    }

    @Test
    fun adKeysAreRecognized() {
        assertTrue(ItemParsers.isAdKey("adSlotRenderer"))
        assertTrue(ItemParsers.isAdKey("promotedSparklesWebRenderer"))
        assertTrue(ItemParsers.isAdKey("inFeedAdLayoutRenderer"))
        assertFalse(ItemParsers.isAdKey("videoRenderer"))
        assertFalse(ItemParsers.isAdKey("addToPlaylistRenderer"))
        assertFalse(ItemParsers.isAdKey("loadMoreRenderer"))
    }

    @Test
    fun watchPageParsesMetadataChannelRelatedAndComments() {
        val info = WatchParser.parse(Fixtures.json("watch_next.json"), "dQw4w9WgXcQ")
        assertTrue(info.title.contains("Rick Astley"))
        assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", info.channel?.id)
        assertEquals(false, info.channel?.subscribed)
        assertEquals(LikeStatus.Indifferent, info.likeStatus)
        assertTrue(info.related.size >= 4)
        assertNotNull(info.relatedContinuation)
        assertNotNull(info.commentsToken)
        assertEquals(2, info.commentSorts.size)
        assertTrue(info.commentSorts.first().selected)
        assertNotNull(info.viewCountText)
        assertNotNull(info.autoplayVideoId)
    }

    @Test
    fun commentsPageParsesThreadsFromEntityMutations() {
        val page = CommentsParser.parse(Fixtures.json("comments_page.json"))
        assertEquals(4, page.comments.size)
        page.comments.forEach {
            assertTrue(it.text.isNotBlank())
            assertTrue(it.authorName.isNotBlank())
            assertFalse(it.isReply)
        }
        assertNotNull(page.comments.first().repliesToken)
        assertNotNull(page.continuation)
    }

    @Test
    fun commentRepliesAreMarkedAsReplies() {
        val page = CommentsParser.parse(Fixtures.json("comment_replies.json"))
        assertTrue(page.comments.isNotEmpty())
        assertTrue(page.comments.all { it.isReply })
        assertNotNull(page.continuation)
    }

    @Test
    fun guestAccountMenuIsReportedAsLoggedOut() {
        val root = Fixtures.json("account_menu_guest.json")
        assertEquals(true, AccountParser.isLoggedOut(root))
        assertFalse(AccountParser.parseMenu(root).loggedIn)
    }

    @Test
    fun loggedInMenuRequiresActiveAccountHeader() {
        val ok = AccountParser.parseMenu(Fixtures.json("account_menu_logged_in_structure.json"))
        assertTrue(ok.loggedIn)
        assertEquals("Test User", ok.name)
        assertEquals("@testuser", ok.handle)
        val noHeader = AccountParser.parseMenu(Fixtures.json("home_logged_in_structure.json"))
        assertFalse(noHeader.loggedIn)
    }

    @Test
    fun accountsListYieldsPrimaryAndBrandIdentity() {
        val ids = AccountParser.parseIdentities(Fixtures.json("accounts_list_structure.json"))
        assertEquals(2, ids.size)
        assertNull(ids[0].pageId)
        assertEquals("1234567890", ids[1].pageId)
        assertEquals("1234567890", ids[1].key)
    }

    @Test
    fun suggestionsParseJsonpPayload() {
        val body = """window.google.ac.h(["lofi",[["lofi hip hop",0],["lofi girl",0]],{"k":1}])"""
        assertEquals(listOf("lofi hip hop", "lofi girl"), SuggestParser.parse(body))
        assertTrue(SuggestParser.parse("garbage").isEmpty())
    }

    @Test
    fun durationParsing() {
        assertEquals(5415, ItemParsers.durationSeconds("1:30:15"))
        assertEquals(125, ItemParsers.durationSeconds("2:05"))
        assertNull(ItemParsers.durationSeconds("LIVE"))
        assertNull(ItemParsers.durationSeconds(null))
    }
}
