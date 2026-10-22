package vibe.tube.feature.nav

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object SubscriptionsRoute
@Serializable data object LibraryRoute
@Serializable data object AccountRoute
@Serializable data class SearchRoute(val query: String = "")
@Serializable data class PlaylistRoute(val id: String, val title: String? = null)
@Serializable data class ChannelRoute(val id: String)
@Serializable data object YoutubeHistoryRoute
@Serializable data object LocalHistoryRoute
@Serializable data object DownloadsRoute
@Serializable data object PlaylistsRoute
@Serializable data object SettingsRoute
@Serializable data object LoginRoute
@Serializable data object LicensesRoute
@Serializable data object GalleryRoute
@Serializable data object DiagnosticsRoute
