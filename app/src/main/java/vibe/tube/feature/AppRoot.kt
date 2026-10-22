package vibe.tube.feature

import android.content.Context
import android.content.Intent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch
import vibe.tube.BuildConfig
import vibe.tube.R
import vibe.tube.account.SessionManager
import vibe.tube.feature.account.AccountScreen
import vibe.tube.feature.channel.ChannelScreen
import vibe.tube.feature.debug.debugDestinations
import vibe.tube.feature.home.HomeScreen
import vibe.tube.feature.home.SubscriptionsScreen
import vibe.tube.feature.library.LibraryScreen
import vibe.tube.feature.library.LocalHistoryScreen
import vibe.tube.feature.library.PlaylistScreen
import vibe.tube.feature.library.PlaylistsScreen
import vibe.tube.feature.library.YoutubeHistoryScreen
import vibe.tube.feature.login.LoginScreen
import vibe.tube.feature.nav.AccountRoute
import vibe.tube.feature.nav.ChannelRoute
import vibe.tube.feature.nav.DiagnosticsRoute
import vibe.tube.feature.nav.GalleryRoute
import vibe.tube.feature.nav.HomeRoute
import vibe.tube.feature.nav.LibraryRoute
import vibe.tube.feature.nav.LicensesRoute
import vibe.tube.feature.nav.LocalHistoryRoute
import vibe.tube.feature.nav.LoginRoute
import vibe.tube.feature.nav.PlaylistRoute
import vibe.tube.feature.nav.PlaylistsRoute
import vibe.tube.feature.nav.SearchRoute
import vibe.tube.feature.nav.SettingsRoute
import vibe.tube.feature.nav.SubscriptionsRoute
import vibe.tube.feature.nav.YoutubeHistoryRoute
import vibe.tube.feature.search.SearchScreen
import vibe.tube.feature.settings.LicensesScreen
import vibe.tube.feature.settings.SettingsScreen
import vibe.tube.feature.watch.AddToPlaylistSheet
import vibe.tube.feature.watch.MiniPlayer
import vibe.tube.feature.watch.PlayerOverlay
import vibe.tube.feature.watch.WatchViewModel
import vibe.tube.ui.VIcon
import vibe.tube.ui.components.LocalMiniPlayerPadding
import vibe.tube.ui.components.LocalQueueMode
import vibe.tube.ui.components.LocalVideoActions
import vibe.tube.ui.components.VIconImage
import vibe.tube.ui.components.VideoActions
import vibe.tube.ui.theme.Sizing
import vibe.tube.youtube.model.VideoItem

private data class Tab(val route: Any, val label: Int, val icon: Int, val iconSelected: Int)

private val tabs = listOf(
    Tab(HomeRoute, R.string.nav_home, VIcon.Home, VIcon.HomeFilled),
    Tab(SubscriptionsRoute, R.string.nav_subscriptions, VIcon.Subscriptions, VIcon.SubscriptionsFilled),
    Tab(LibraryRoute, R.string.nav_library, VIcon.VideoLibrary, VIcon.VideoLibraryFilled),
    Tab(AccountRoute, R.string.nav_account, VIcon.AccountCircle, VIcon.AccountCircleFilled),
)

@Composable
fun AppRoot(onEnterPip: (() -> Unit)?, appViewModel: AppViewModel = hiltViewModel(), watchViewModel: WatchViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val controller = appViewModel.playerController
    val playerState by controller.state.collectAsStateWithLifecycle()
    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    val avatarUrl by appViewModel.avatarUrl.collectAsStateWithLifecycle()
    val saveTarget by appViewModel.events.saveTarget.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val onTopLevel = tabs.any { destination?.hasRoute(it.route::class) == true }
    val snackbar = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        appViewModel.events.messages.collect { message ->
            val text = context.getString(message.resId, *message.args.toTypedArray())
            val action = message.actionResId?.let(context::getString)
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(text, actionLabel = action, duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
            }
        }
    }

    val actions = remember(nav, controller) {
        object : VideoActions {
            override fun play(video: VideoItem, queue: List<VideoItem>) = controller.play(video, queue)
            override fun openChannel(channelId: String) {
                controller.collapse()
                nav.navigate(ChannelRoute(channelId))
            }
            override fun openPlaylist(playlistId: String, title: String?) {
                controller.collapse()
                nav.navigate(PlaylistRoute(playlistId, title))
            }
            override fun save(video: VideoItem) = appViewModel.events.requestSave(video)
            override fun watchLater(video: VideoItem) = appViewModel.watchLater(video)
            override fun share(video: VideoItem) = shareVideo(context, video)
            override fun login() {
                controller.collapse()
                nav.navigate(LoginRoute)
            }
            override fun playPlaylist(playlistId: String, shuffle: Boolean) = controller.playPlaylist(playlistId, shuffle)
        }
    }

    val adaptive = currentWindowAdaptiveInfo()
    val suiteType = when {
        !onTopLevel -> NavigationSuiteType.None
        adaptive.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXTRA_LARGE_LOWER_BOUND) -> NavigationSuiteType.NavigationDrawer
        else -> NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptive)
    }
    val miniVisible = playerState.visible && !playerState.expanded && !playerState.inPip
    val miniPadding = if (miniVisible) Sizing.miniPlayerHeight + 16.dp else 0.dp

    CompositionLocalProvider(LocalVideoActions provides actions, LocalMiniPlayerPadding provides miniPadding) {
        Box(Modifier.fillMaxSize()) {
            NavigationSuiteScaffold(
                layoutType = suiteType,
                navigationSuiteItems = {
                    tabs.forEach { tab ->
                        val selected = destination?.hasRoute(tab.route::class) == true
                        item(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { VIconImage(if (selected) tab.iconSelected else tab.icon, null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                },
            ) {
                Box(Modifier.fillMaxSize()) {
                    AppNavHost(nav, appViewModel, avatarUrl)
                    MiniPlayer(
                        controller,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .then(if (onTopLevel) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars)),
                    )
                    SnackbarHost(
                        snackbar,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = miniPadding)
                            .then(if (onTopLevel) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars)),
                    )
                }
            }
            PlayerOverlay(
                player = appViewModel.engine.player,
                controller = controller,
                watch = watchViewModel,
                onLogin = { controller.collapse(); nav.navigate(LoginRoute) },
                onEnterPip = if (settings.pictureInPicture) onEnterPip else null,
            )
            if (playerState.expanded && playerState.visible) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    SnackbarHost(snackbar, Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                }
            }
            saveTarget?.let { video ->
                AddToPlaylistSheet(
                    video = video,
                    onDismiss = appViewModel.events::dismissSave,
                    onLogin = { controller.collapse(); nav.navigate(LoginRoute) },
                )
            }
        }
    }
}

@Composable
private fun AppNavHost(nav: androidx.navigation.NavHostController, appViewModel: AppViewModel, avatarUrl: String?) {
    val noPadding = PaddingValues(0.dp)
    val authState by appViewModel.authState.collectAsStateWithLifecycle()
    val isGuest = authState is vibe.tube.account.AuthState.Guest
    val enter = { slideInHorizontally(initialOffsetX = { it / 8 }) + fadeIn() }
    NavHost(
        navController = nav,
        startDestination = HomeRoute,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        composable<HomeRoute> {
            HomeScreen(
                onSearch = { nav.navigate(SearchRoute()) },
                onLogin = { nav.navigate(LoginRoute) },
                onAccount = { nav.navigate(AccountRoute) { launchSingleTop = true } },
                avatarUrl = avatarUrl,
                contentPadding = noPadding,
            )
        }
        composable<SubscriptionsRoute> {
            SubscriptionsScreen(onSearch = { nav.navigate(SearchRoute()) }, onLogin = { nav.navigate(LoginRoute) }, contentPadding = noPadding)
        }
        composable<LibraryRoute> {
            LibraryScreen(
                onOpenHistory = { nav.navigate(YoutubeHistoryRoute) },
                onOpenLocalHistory = { nav.navigate(LocalHistoryRoute) },
                onOpenLiked = { nav.navigate(PlaylistRoute("LL")) },
                onOpenWatchLater = { nav.navigate(PlaylistRoute("WL")) },
                onOpenPlaylists = { nav.navigate(PlaylistsRoute) },
                onLogin = { nav.navigate(LoginRoute) },
                isGuest = isGuest,
                contentPadding = noPadding,
            )
        }
        composable<AccountRoute> {
            AccountScreen(onLogin = { nav.navigate(LoginRoute) }, onSettings = { nav.navigate(SettingsRoute) }, contentPadding = noPadding)
        }
        composable<SearchRoute> { SearchScreen(onBack = { nav.popBackStack() }, onLogin = { nav.navigate(LoginRoute) }) }
        composable<PlaylistRoute> {
            CompositionLocalProvider(LocalQueueMode provides true) {
                PlaylistScreen(onBack = { nav.popBackStack() }, onLogin = { nav.navigate(LoginRoute) }, session = appViewModel.activeSession)
            }
        }
        composable<ChannelRoute> { ChannelScreen(onBack = { nav.popBackStack() }, onLogin = { nav.navigate(LoginRoute) }) }
        composable<YoutubeHistoryRoute> {
            CompositionLocalProvider(LocalQueueMode provides true) {
                YoutubeHistoryScreen(onBack = { nav.popBackStack() }, onLogin = { nav.navigate(LoginRoute) })
            }
        }
        composable<LocalHistoryRoute> { LocalHistoryScreen(onBack = { nav.popBackStack() }) }
        composable<PlaylistsRoute> { PlaylistsScreen(onBack = { nav.popBackStack() }, onLogin = { nav.navigate(LoginRoute) }) }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onLicenses = { nav.navigate(LicensesRoute) },
                onDebug = if (BuildConfig.DEBUG) ({ nav.navigate(GalleryRoute) }) else null,
            )
        }
        composable<LicensesRoute> { LicensesScreen(onBack = { nav.popBackStack() }) }
        composable<LoginRoute> { LoginScreen(onClose = { nav.popBackStack() }) }
        debugDestinations(nav)
    }
}

private fun shareVideo(context: Context, video: VideoItem) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, video.title)
        putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=${video.id}")
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
