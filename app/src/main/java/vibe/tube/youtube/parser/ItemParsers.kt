package vibe.tube.youtube.parser

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import vibe.tube.youtube.model.ChannelItem
import vibe.tube.youtube.model.FeedItem
import vibe.tube.youtube.model.PlaylistItem
import vibe.tube.youtube.model.Thumb
import vibe.tube.youtube.model.VideoAvailability
import vibe.tube.youtube.model.VideoItem

object ItemParsers {

    val itemKeys = setOf(
        "videoRenderer",
        "compactVideoRenderer",
        "gridVideoRenderer",
        "playlistVideoRenderer",
        "lockupViewModel",
        "shortsLockupViewModel",
        "reelItemRenderer",
        "channelRenderer",
        "gridChannelRenderer",
        "playlistRenderer",
        "gridPlaylistRenderer",
        "compactPlaylistRenderer",
    )

    private val adKeys = setOf(
        "adSlotRenderer",
        "promotedSparklesWebRenderer",
        "promotedSparklesTextSearchRenderer",
        "promotedVideoRenderer",
        "compactPromotedVideoRenderer",
        "displayAdRenderer",
        "videoMastheadAdV3Renderer",
        "inFeedAdLayoutRenderer",
        "searchPyvRenderer",
        "carouselAdRenderer",
        "actionCompanionAdRenderer",
        "brandVideoShelfRenderer",
        "brandVideoSingletonRenderer",
        "primetimePromoRenderer",
        "statementBannerRenderer",
        "adPlacements",
        "adPlacementRenderer",
        "playerAds",
    )

    fun isAdKey(key: String): Boolean =
        key in adKeys ||
            key.contains("Promoted") ||
            key.endsWith("AdRenderer") ||
            key.contains("AdSlot") ||
            key.contains("AdLayout") ||
            (key.length > 2 && key.startsWith("ad") && key[2].isUpperCase())

    fun parse(key: String, node: JsonElement): FeedItem? = when (key) {
        "videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "playlistVideoRenderer" -> parseVideoRenderer(node)
        "lockupViewModel" -> parseLockup(node)
        "shortsLockupViewModel" -> parseShortsLockup(node)
        "reelItemRenderer" -> parseReelItem(node)
        "channelRenderer", "gridChannelRenderer" -> parseChannelRenderer(node)
        "playlistRenderer", "gridPlaylistRenderer", "compactPlaylistRenderer" -> parsePlaylistRenderer(node)
        else -> null
    }

    fun thumbs(node: JsonElement?): List<Thumb> = node.at("thumbnails").list().mapNotNull {
        val url = normalizeUrl(it.at("url").str) ?: return@mapNotNull null
        Thumb(url, it.at("width").int ?: 0, it.at("height").int ?: 0)
    }

    fun sources(node: JsonElement?): List<Thumb> = node.at("sources").list().mapNotNull {
        val url = normalizeUrl(it.at("url").str) ?: return@mapNotNull null
        Thumb(url, it.at("width").int ?: 0, it.at("height").int ?: 0)
    }

    fun durationSeconds(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3 || parts.any { it.toIntOrNull() == null }) return null
        return parts.map { it.toInt() }.fold(0) { acc, p -> acc * 60 + p }
    }

    private fun browseId(node: JsonElement?): String? = node.firstString("browseId")

    private fun parseVideoRenderer(node: JsonElement): VideoItem? {
        val id = node.at("videoId").str ?: return null
        val rawTitle = node.at("title").textOf().orEmpty()
        val byline = node.at("longBylineText") ?: node.at("ownerText") ?: node.at("shortBylineText")
        val channelName = byline.at("runs").at(0).at("text").str?.trim()
        val channelId = browseId(byline)
        val lengthText = node.at("lengthText").textOf()
            ?: node.at("thumbnailOverlays").list().firstNotNullOfOrNull {
                it.at("thumbnailOverlayTimeStatusRenderer").at("text").textOf()
            }
        var live = false
        node.at("thumbnailOverlays").list().forEach {
            if (it.at("thumbnailOverlayTimeStatusRenderer").at("style").str == "LIVE") live = true
        }
        node.at("badges").list().forEach {
            if (it.at("metadataBadgeRenderer").at("style").str == "BADGE_STYLE_TYPE_LIVE_NOW") live = true
        }
        val progress = node.at("thumbnailOverlays").list().firstNotNullOfOrNull {
            it.at("thumbnailOverlayResumePlaybackRenderer").at("percentDurationWatched").int
        }
        val avatar = thumbs(
            node.at("channelThumbnailSupportedRenderers").at("channelThumbnailWithLinkRenderer").at("thumbnail"),
        ).firstOrNull()?.url
        val playable = node.at("isPlayable").bool != false
        val availability = when {
            playable -> VideoAvailability.Available
            rawTitle.contains("private", ignoreCase = true) -> VideoAvailability.Private
            rawTitle.contains("deleted", ignoreCase = true) -> VideoAvailability.Deleted
            else -> VideoAvailability.Unplayable
        }
        return VideoItem(
            id = id,
            title = rawTitle,
            channelName = channelName,
            channelId = channelId,
            channelAvatar = avatar,
            thumbnails = thumbs(node.at("thumbnail")),
            durationText = lengthText,
            durationSeconds = durationSeconds(lengthText),
            viewCountText = node.at("viewCountText").textOf() ?: node.at("shortViewCountText").textOf(),
            publishedText = node.at("publishedTimeText").textOf(),
            isLive = live,
            isShort = false,
            watchedPercent = progress,
            setVideoId = node.at("setVideoId").str,
            availability = availability,
            playlistIndex = node.at("index").textOf()?.toIntOrNull(),
        )
    }

    private fun parseLockup(node: JsonElement): FeedItem? {
        val id = node.at("contentId").str ?: return null
        val type = node.at("contentType").str.orEmpty()
        val meta = node.at("metadata").at("lockupMetadataViewModel")
        val title = meta.at("title").textOf().orEmpty()
        val rows = meta.at("metadata").at("contentMetadataViewModel").at("metadataRows").list()
        val rowTexts = rows.map { row -> row.at("metadataParts").list().mapNotNull { it.at("text").textOf() } }
        val image = node.at("contentImage")
        return when {
            type.endsWith("_VIDEO") || type.endsWith("_PODCAST_EPISODE") -> {
                val thumb = sources(image.at("thumbnailViewModel").at("image"))
                var duration: String? = null
                var progress: Int? = null
                var live = false
                image.at("thumbnailViewModel").at("overlays").list().forEach { overlay ->
                    overlay.at("thumbnailBottomOverlayViewModel").at("badges").list().forEach { b ->
                        val badge = b.at("thumbnailBadgeViewModel")
                        val text = badge.at("text").str
                        if (text != null) {
                            if (badge.at("badgeStyle").str.orEmpty().contains("LIVE") || text.equals("LIVE", true)) live = true
                            else if (duration == null) duration = text
                        }
                    }
                    overlay.at("thumbnailBottomOverlayViewModel").at("progressBar")
                        .at("thumbnailOverlayProgressBarViewModel").at("startPercent").int?.let { progress = it }
                }
                val avatar = sources(
                    meta.at("image").at("decoratedAvatarViewModel").at("avatar").at("avatarViewModel").at("image"),
                ).firstOrNull()?.url
                val channelName = rowTexts.getOrNull(0)?.getOrNull(0)
                val channelId = rows.getOrNull(0)?.let(::browseId) ?: browseId(meta.at("image"))
                val secondRow = rowTexts.getOrNull(1).orEmpty()
                VideoItem(
                    id = id,
                    title = title,
                    channelName = channelName,
                    channelId = channelId,
                    channelAvatar = avatar,
                    thumbnails = thumb,
                    durationText = duration,
                    durationSeconds = durationSeconds(duration),
                    viewCountText = secondRow.getOrNull(0),
                    publishedText = secondRow.getOrNull(1),
                    isLive = live,
                    watchedPercent = progress,
                    setVideoId = node.firstString("setVideoId"),
                )
            }
            type.endsWith("_PLAYLIST") || type.endsWith("_PODCAST") || type.endsWith("_MIX") -> {
                val collection = image.at("collectionThumbnailViewModel").at("primaryThumbnail").at("thumbnailViewModel")
                var count: String? = null
                collection.at("overlays").list().forEach { overlay ->
                    overlay.at("thumbnailOverlayBadgeViewModel").at("thumbnailBadges").list().forEach { b ->
                        b.at("thumbnailBadgeViewModel").at("text").str?.let { if (count == null) count = it }
                    }
                }
                val firstVideo = node.at("rendererContext").firstObject("watchEndpoint").at("videoId").str
                PlaylistItem(
                    id = id,
                    title = title,
                    ownerName = rowTexts.getOrNull(0)?.getOrNull(0),
                    ownerId = rows.getOrNull(0)?.let(::browseId),
                    videoCountText = count,
                    thumbnails = sources(collection.at("image")),
                    firstVideoId = firstVideo,
                    updatedText = rowTexts.getOrNull(1)?.firstOrNull(),
                )
            }
            else -> null
        }
    }

    private fun parseShortsLockup(node: JsonElement): VideoItem? {
        val id = node.at("onTap").at("innertubeCommand").at("reelWatchEndpoint").at("videoId").str
            ?: node.firstString("videoId")
            ?: return null
        val title = node.at("overlayMetadata").at("primaryText").textOf().orEmpty()
        return VideoItem(
            id = id,
            title = title,
            channelName = null,
            channelId = null,
            channelAvatar = null,
            thumbnails = sources(node.at("thumbnail")).ifEmpty { sources(node.at("thumbnailViewModel").at("image")) },
            durationText = null,
            durationSeconds = null,
            viewCountText = node.at("overlayMetadata").at("secondaryText").textOf(),
            publishedText = null,
            isShort = true,
        )
    }

    private fun parseReelItem(node: JsonElement): VideoItem? {
        val id = node.at("videoId").str ?: return null
        return VideoItem(
            id = id,
            title = node.at("headline").textOf().orEmpty(),
            channelName = null,
            channelId = null,
            channelAvatar = null,
            thumbnails = thumbs(node.at("thumbnail")),
            durationText = null,
            durationSeconds = null,
            viewCountText = node.at("viewCountText").textOf(),
            publishedText = null,
            isShort = true,
        )
    }

    private fun parseChannelRenderer(node: JsonElement): ChannelItem? {
        val id = node.at("channelId").str ?: return null
        val handleFromSubs = node.at("subscriberCountText").textOf()?.takeIf { it.startsWith("@") }
        val subscribers = node.at("videoCountText").textOf()?.takeIf { it.contains("subscriber", true) || it.contains("người đăng ký") }
            ?: node.at("subscriberCountText").textOf()?.takeIf { !it.startsWith("@") }
        return ChannelItem(
            id = id,
            name = node.at("title").textOf().orEmpty(),
            handle = handleFromSubs ?: node.at("navigationEndpoint").at("browseEndpoint").at("canonicalBaseUrl").str
                ?.takeIf { it.startsWith("/@") }?.removePrefix("/"),
            avatars = thumbs(node.at("thumbnail")),
            subscriberText = subscribers,
            description = node.at("descriptionSnippet").textOf(),
            subscribed = node.at("subscriptionButton").at("subscribed").bool,
        )
    }

    private fun parsePlaylistRenderer(node: JsonElement): PlaylistItem? {
        val id = node.at("playlistId").str ?: return null
        val byline = node.at("longBylineText") ?: node.at("shortBylineText")
        return PlaylistItem(
            id = id,
            title = node.at("title").textOf().orEmpty(),
            ownerName = byline.at("runs").at(0).at("text").str,
            ownerId = browseId(byline),
            videoCountText = node.at("videoCountText").textOf() ?: node.at("videoCountShortText").textOf(),
            thumbnails = node.at("thumbnails").at(0).let(::thumbs).ifEmpty { thumbs(node.at("thumbnail")) },
            firstVideoId = node.at("navigationEndpoint").at("watchEndpoint").at("videoId").str,
            updatedText = null,
        )
    }

    fun isObject(e: JsonElement?): Boolean = e is JsonObject
}
