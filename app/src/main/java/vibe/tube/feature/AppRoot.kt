package vibe.tube.feature

import android.content.Context
import android.content.Intent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavDestination
import vibe.tube.ui.components.LocalSharedTransitionScope
import vibe.tube.ui.theme.LocalReducedMotion
import kotlin.math.abs
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
    val onSearch = destination?.hasRoute(SearchRoute::class) == true
    // The bottom bar stays visible on the tabs and in search.
    val showNav = onTopLevel || onSearch
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

    val goToTab = remember(nav) {
        { tab: Tab ->
            nav.navigate(tab.route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    val currentTab = tabs.indexOfFirst { destination?.hasRoute(it.route::class) == true }

    val adaptive = currentWindowAdaptiveInfo()
    val suiteType = when {
        !showNav -> NavigationSuiteType.None
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
                            onClick = { goToTab(tab) },
                            icon = { VIconImage(if (selected) tab.iconSelected else tab.icon, null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                },
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .tabSwipe(enabled = currentTab >= 0 && !(playerState.visible && playerState.expanded)) { direction ->
                            tabs.getOrNull(currentTab + direction)?.let(goToTab)
                        },
                ) {
                    AppNavHost(nav, appViewModel, avatarUrl)
                    MiniPlayer(
                        controller,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .then(if (showNav) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars)),
                    )
                    SnackbarHost(
                        snackbar,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = miniPadding)
                            .then(if (showNav) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars)),
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
    val motion = MaterialTheme.motionScheme
    val offsetSpec = motion.defaultSpatialSpec<IntOffset>()
    val fadeSpec = motion.defaultEffectsSpec<Float>()
    val reduced = LocalReducedMotion.current

    fun tabIndex(d: NavDestination): Int = tabs.indexOfFirst { d.hasRoute(it.route::class) }
    fun isSearch(d: NavDestination) = d.hasRoute(SearchRoute::class)

    // Direction-aware shared-axis motion: tabs slide sideways in the swipe direction, detail screens
    // drift in from the right, and search only fades (its container transform does the rest).
    fun enter(initial: NavDestination, target: NavDestination, popping: Boolean): EnterTransition {
        if (reduced) return fadeIn(tween(0))
        val from = tabIndex(initial)
        val to = tabIndex(target)
        return when {
            isSearch(initial) || isSearch(target) -> fadeIn(fadeSpec)
            from >= 0 && to >= 0 -> {
                val dir = if (to > from) 1 else -1
                slideInHorizontally(offsetSpec) { dir * it / 3 } + fadeIn(fadeSpec)
            }
            popping -> slideInHorizontally(offsetSpec) { -it / 6 } + fadeIn(fadeSpec)
            else -> slideInHorizontally(offsetSpec) { it / 6 } + fadeIn(fadeSpec)
        }
    }

    fun exit(initial: NavDestination, target: NavDestination, popping: Boolean): ExitTransition {
        if (reduced) return fadeOut(tween(0))
        val from = tabIndex(initial)
        val to = tabIndex(target)
        return when {
            isSearch(initial) || isSearch(target) -> fadeOut(fadeSpec)
            from >= 0 && to >= 0 -> {
                val dir = if (to > from) 1 else -1
                slideOutHorizontally(offsetSpec) { -dir * it / 3 } + fadeOut(fadeSpec)
            }
            popping -> slideOutHorizontally(offsetSpec) { it / 6 } + fadeOut(fadeSpec)
            else -> slideOutHorizontally(offsetSpec) { -it / 6 } + fadeOut(fadeSpec)
        }
    }

    SharedTransitionLayout(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = nav,
                startDestination = HomeRoute,
                enterTransition = { enter(initialState.destination, targetState.destination, popping = false) },
                exitTransition = { exit(initialState.destination, targetState.destination, popping = false) },
                popEnterTransition = { enter(initialState.destination, targetState.destination, popping = true) },
                popExitTransition = { exit(initialState.destination, targetState.destination, popping = true) },
            ) {
                composable<HomeRoute> {
                    HomeScreen(
                        onSearch = { nav.navigate(SearchRoute()) },
                        onLogin = { nav.navigate(LoginRoute) },
                        onAccount = { nav.navigate(AccountRoute) { launchSingleTop = true } },
                        avatarUrl = avatarUrl,
                        contentPadding = noPadding,
                        visibilityScope = this,
                    )
                }
                composable<SubscriptionsRoute> {
                    SubscriptionsScreen(
                        onSearch = { nav.navigate(SearchRoute()) },
                        onLogin = { nav.navigate(LoginRoute) },
                        contentPadding = noPadding,
                        visibilityScope = this,
                    )
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
                composable<SearchRoute> {
                    SearchScreen(onBack = { nav.popBackStack() }, onLogin = { nav.navigate(LoginRoute) }, visibilityScope = this)
                }
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
    }
}

/** Swipe horizontally on a top-level screen to move to the neighbouring tab (+1 = next, -1 = previous). */
private fun Modifier.tabSwipe(enabled: Boolean, onSwipe: (direction: Int) -> Unit): Modifier = composed {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentSwipe by rememberUpdatedState(onSwipe)
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    pointerInput(Unit) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (currentEnabled && abs(total) > threshold) currentSwipe(if (total < 0f) 1 else -1)
                total = 0f
            },
            onDragCancel = { total = 0f },
            onHorizontalDrag = { _, amount -> total += amount },
        )
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
