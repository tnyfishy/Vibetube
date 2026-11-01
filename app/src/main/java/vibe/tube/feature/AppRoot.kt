package vibe.tube.feature

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import vibe.tube.ui.rememberDeviceCornerRadius
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import vibe.tube.ui.components.EmphasizedDecelerate
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavDestination
import vibe.tube.ui.components.LocalSharedTransitionScope
import vibe.tube.ui.theme.LocalReducedMotion
import vibe.tube.feature.welcome.appRevealRise
import kotlin.math.abs
import androidx.compose.foundation.layout.Box
import kotlin.math.max
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.NavigationBarItem
import vibe.tube.ui.components.glassSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import vibe.tube.feature.nav.DownloadsRoute
import vibe.tube.feature.nav.HomeRoute
import vibe.tube.feature.nav.LibraryRoute
import vibe.tube.feature.nav.ShortsRoute
import androidx.navigation.toRoute
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import vibe.tube.feature.nav.LicensesRoute
import vibe.tube.feature.nav.LocalHistoryRoute
import vibe.tube.feature.nav.LoginRoute
import vibe.tube.feature.nav.PlaylistRoute
import vibe.tube.feature.nav.PlaylistsRoute
import vibe.tube.feature.nav.SearchRoute
import vibe.tube.feature.nav.NotificationsRoute
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

private val allTabs = listOf(
    Tab(HomeRoute, R.string.nav_home, VIcon.Home, VIcon.HomeFilled),
    Tab(ShortsRoute, R.string.nav_shorts, VIcon.Shorts, VIcon.ShortsFilled),
    Tab(SubscriptionsRoute, R.string.nav_subscriptions, VIcon.Subscriptions, VIcon.SubscriptionsFilled),
    Tab(LibraryRoute, R.string.nav_library, VIcon.VideoLibrary, VIcon.VideoLibraryFilled),
    Tab(AccountRoute, R.string.nav_account, VIcon.AccountCircle, VIcon.AccountCircleFilled),
)

@Composable
fun AppRoot(onEnterPip: (() -> Unit)?, appViewModel: AppViewModel = hiltViewModel(), watchViewModel: WatchViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val controller = appViewModel.playerController
    val playerState by remember { controller.state.map { PlayerFlags(it.visible, it.expanded, it.fullscreen, it.inPip) }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = PlayerFlags())
    val playingVideo by remember { controller.state.map { it.isPlaying && it.visible }.distinctUntilChanged() }.collectAsStateWithLifecycle(false)
    val playingShort by remember { appViewModel.shortsPlayer.state.map { it.playing }.distinctUntilChanged() }.collectAsStateWithLifecycle(false)
    val playbackView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(playbackView, playingVideo || playingShort) {
        playbackView.keepScreenOn = playingVideo || playingShort
        onDispose { playbackView.keepScreenOn = false }
    }
    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    val watchProgress = appViewModel.watchProgress.collectAsStateWithLifecycle()
    val tabs = remember(settings.hideShorts) { if (settings.hideShorts) allTabs.filter { it.route != ShortsRoute } else allTabs }
    val avatarUrl by appViewModel.avatarUrl.collectAsStateWithLifecycle()
    val saveTarget by appViewModel.events.saveTarget.collectAsStateWithLifecycle()
    val downloadTarget by appViewModel.events.downloadTarget.collectAsStateWithLifecycle()
    val downloadVideoOnly by appViewModel.events.downloadVideoOnly.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val onTopLevel = tabs.any { destination?.hasRoute(it.route::class) == true }
    val onSearch = destination?.hasRoute(SearchRoute::class) == true
    
    val showNav = onTopLevel || onSearch
    val snackbar = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val downloadToastId by appViewModel.events.downloadToast.collectAsStateWithLifecycle()
    val downloadJobs by appViewModel.downloadJobs.collectAsStateWithLifecycle()
    val toastJob = downloadJobs.firstOrNull { it.id == downloadToastId }
    var toastTouch by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(downloadToastId, toastTouch) {
        val id = downloadToastId ?: return@LaunchedEffect
        kotlinx.coroutines.delay(9_000)
        appViewModel.events.hideDownloadToast(id)
    }
    val openDownloads = remember(nav, controller) {
        {
            controller.collapse()
            appViewModel.events.hideDownloadToast()
            nav.navigate(DownloadsRoute) { launchSingleTop = true }
        }
    }
    LaunchedEffect(Unit) { appViewModel.events.openDownloadsRequests.collect { openDownloads() } }
    LaunchedEffect(Unit) {
        appViewModel.events.openNotificationsRequests.collect { nav.navigate(NotificationsRoute) { launchSingleTop = true } }
    }
    LaunchedEffect(Unit) {
        appViewModel.events.openTargetRequests.collect { request -> openNotificationTarget(request, controller, nav) }
    }
    var updateSheet by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) { appViewModel.events.openUpdateRequests.collect { updateSheet = true } }
    if (updateSheet) vibe.tube.update.UpdateSheet(onDismiss = { updateSheet = false })

    LaunchedEffect(Unit) {
        appViewModel.events.messages.collect { message ->
            val text = context.getString(message.resId, *message.args.toTypedArray())
            val action = message.actionResId?.let(context::getString)
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = if (message.icon != null) snackbar.showSnackbar(vibe.tube.ui.components.IconSnackbarVisuals(text, message.icon, action))
                    else snackbar.showSnackbar(text, actionLabel = action, duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
            }
        }
    }

    val goToTab = remember(nav) {
        { tab: Tab ->
            if (tab.route != ShortsRoute) appViewModel.shortsPlayer.pause()
            val startRoute = nav.graph.findStartDestination()
            val onStart = tab.route::class.let { cls -> startRoute.hasRoute(cls) }
            if (onStart) {
                if (nav.currentBackStackEntry?.destination?.hasRoute(tab.route::class) != true) {
                    if (!nav.popBackStack(tab.route, inclusive = false)) {
                        nav.navigate(tab.route) { launchSingleTop = true }
                    }
                } else {
                    
                    appViewModel.events.requestHomeReselect()
                }
            } else {
                nav.navigate(tab.route) {
                    popUpTo(startRoute.id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }
    val actions = remember(nav, controller) {
        object : VideoActions {
            override fun play(video: VideoItem, queue: List<VideoItem>) {
                if (video.isShort) {
                    // A short opens in the Shorts tab, leading its feed with the rest of the shelf after it.
                    controller.collapse()
                    appViewModel.engine.player.pause()
                    appViewModel.shortsPlayer.pick((listOf(video) + queue.filter { it.isShort && it.id != video.id }).take(50))
                    goToTab(allTabs.first { it.route == ShortsRoute })
                } else controller.play(video, queue)
            }
            override fun openChannel(channelId: String) {
                controller.collapse()
                nav.navigate(ChannelRoute(channelId))
            }
            override fun openPlaylist(playlistId: String, title: String?) {
                
                if (playlistId.startsWith("RD")) {
                    controller.playMix(vibe.tube.youtube.model.PlaylistItem(playlistId, title.orEmpty(), null, null, null, emptyList()))
                    return
                }
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
            override fun playMix(mix: vibe.tube.youtube.model.PlaylistItem) = controller.playMix(mix)
            override fun hideChannel(video: VideoItem) = appViewModel.hideChannel(video)
            override fun download(video: VideoItem) = appViewModel.events.requestDownload(video)
        }
    }

    val currentTab = tabs.indexOfFirst { destination?.hasRoute(it.route::class) == true }

    val adaptive = currentWindowAdaptiveInfo()
    val suiteType = when {
        !showNav -> NavigationSuiteType.None
        adaptive.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXTRA_LARGE_LOWER_BOUND) -> NavigationSuiteType.NavigationDrawer
        else -> NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptive)
    }
    val windowConfig = androidx.compose.ui.platform.LocalConfiguration.current
    val classicPlayer = vibe.tube.feature.watch.usesClassicPlayer(playerState.fullscreen, playerState.inPip, windowConfig.screenWidthDp, windowConfig.screenHeightDp)
    var contentBottomPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val miniVisible = playerState.visible && !playerState.inPip
    val miniPadding = if (miniVisible) (windowConfig.screenWidthDp * vibe.tube.feature.watch.MINI_WIDTH_FRACTION * 9f / 16f).dp + 20.dp else 0.dp

    val hiddenIds = remember(settings.hiddenChannels)  { settings.hiddenChannelIds }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val narrow = windowConfig.screenWidthDp < 600
    val isBar = showNav && narrow
    val barOut = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (showNav) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "barOut",
    )
    val onSettings = destination?.hasRoute(SettingsRoute::class) == true
    val navAppearance = vibe.tube.ui.components.NavBarAppearance(
        showLabels = !settings.navHideLabels,
        height = settings.navBarHeight.dp,
        iconSize = settings.navIconSize.dp,
        floating = settings.navFloating,
        blur = settings.navBlur,
    )
    val navInset = WindowInsets.navigationBars.getBottom(density)
    val barFullPx = with(density) { (navAppearance.height + navAppearance.bottomSpace(navInset.toDp())).roundToPx() }
    val glassBackdrop = if (navAppearance.needsBackdrop) vibe.tube.ui.components.rememberGlassBackdrop() else null
    val barFullDp = with(density) { barFullPx.toDp() }
    // Feed tabs scroll under the bar and let it hide on scroll; other tabs reserve its space.
    val homeTab = currentTab == 0 || tabs.getOrNull(currentTab)?.route == SubscriptionsRoute
    var rootHeightPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var hide by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(currentTab, isBar) { hide = 0f }
    val reservedBottom = if (isBar && !homeTab) barFullDp else 0.dp
    val homeFeedPadding = if (isBar && homeTab) barFullDp else 0.dp
    val homeLift: () -> Int = { max(barFullPx * (1f - max(hide, barOut.value)), navInset.toFloat()).roundToInt() }
    val barLift: () -> Int = {
        val h = if (homeTab || !showNav) max(hide, barOut.value) else barOut.value
        max(barFullPx * (1f - h), navInset.toFloat()).roundToInt()
    }
    val miniAway = onSettings || destination?.hasRoute(ShortsRoute::class) == true || destination?.hasRoute(LoginRoute::class) == true
    val scrubThumb = remember { vibe.tube.ui.components.ScrubThumbState() }
    val welcomePrefs =androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("app_state", android.content.Context.MODE_PRIVATE)
    val welcomeSeen = remember {
        val seen = welcomePrefs.getBoolean("brand_welcome_seen_v4", false)
        seen
    }
    CompositionLocalProvider(vibe.tube.ui.components.LocalScrubThumb provides scrubThumb, vibe.tube.feature.watch.LocalCurrentUserAvatar provides avatarUrl, vibe.tube.feature.watch.LocalMediaSeek provides controller::seekTo, LocalVideoActions provides actions, vibe.tube.feature.watch.LocalCommentTranslator provides appViewModel.commentTranslator, vibe.tube.feature.watch.LocalSeekSeconds provides settings.seekSeconds, vibe.tube.feature.watch.LocalSubtitleStyle provides vibe.tube.feature.watch.SubtitleStyle(settings.subtitleSize, settings.subtitleColor, settings.subtitleBackground), vibe.tube.feature.watch.LocalFullscreenGestures provides settings.fullscreenGestures, vibe.tube.feature.watch.LocalFullscreenSwipeToggle provides settings.fullscreenSwipeToggle, LocalMiniPlayerPadding provides (miniPadding + homeFeedPadding), vibe.tube.ui.components.LocalHiddenChannels provides hiddenIds, vibe.tube.ui.components.LocalWatchProgress provides watchProgress) {
      vibe.tube.feature.welcome.StartRevealHost(
        showStart = !welcomeSeen,
        startScreen = { onStart, buttonModifier, enabled -> vibe.tube.feature.welcome.WelcomeScreen(onStart, buttonModifier, enabled) },
        onRevealStart = {},
        onFinished = {
            welcomePrefs.edit().putBoolean("brand_welcome_seen_v4", true).putBoolean("welcome_seen", true).apply()
        },
      ) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).onSizeChanged { rootHeightPx = it.height }) {
            NavigationSuiteScaffold(
                layoutType = if (isBar) NavigationSuiteType.None else suiteType,
                navigationSuiteItems = {
                    tabs.forEach { tab ->
                        val selected = destination?.hasRoute(tab.route::class) == true
                        item(
                            selected = selected,
                            onClick = { goToTab(tab) },
                            icon = { vibe.tube.ui.components.SelectableSymbol(tab.icon, tab.iconSelected, selected, null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                },
            ) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(bottom = reservedBottom)
                            .glassSource(if (isBar) glassBackdrop else null)
                            .onGloballyPositioned { contentBottomPx = (it.positionInRoot().y + it.size.height).roundToInt() }
                            .tabSwipe(enabled = currentTab >= 0 && !(playerState.visible && playerState.expanded)) { direction ->
                                tabs.getOrNull(currentTab + direction)?.let(goToTab)
                            },
                    ) {
                        AppNavHost(nav, appViewModel, avatarUrl, onTab = { route -> tabs.firstOrNull { it.route == route }?.let(goToTab) })
                        val lift: Modifier = when {
                            !showNav -> Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                            isBar && homeTab -> Modifier.offset { IntOffset(0, -homeLift()) }
                            else -> Modifier
                        }
                        MiniPlayer(
                            controller,
                            enabled = classicPlayer,
                            modifier = Modifier.align(Alignment.BottomCenter).then(lift),
                        )
                        vibe.tube.ui.components.ToastHost(
                            snackbar,
                            download = toastJob,
                            onTogglePause = { toastTouch++; toastJob?.let { appViewModel.toggleDownloadPause(it.id) } },
                            onCancel = { toastJob?.let { appViewModel.cancelDownload(it.id) } },
                            onOpenDownloads = openDownloads,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = miniPadding).then(lift),
                        )
                    }
                    if (narrow) {
                        val entries = tabs.map { vibe.tube.ui.components.NavBarEntry(stringResource(it.label), it.icon, it.iconSelected, if (it.icon == VIcon.Shorts) vibe.tube.ui.components.SHORTS_ICON_SCALE else 1f) }
                        vibe.tube.ui.components.NavBar(
                            entries,
                            selectedIndex = tabs.indexOfFirst { destination?.hasRoute(it.route::class) == true },
                            onSelect = { goToTab(tabs[it]) },
                            appearance = navAppearance,
                            bottomInset = with(density) { navInset.toDp() },
                            backdrop = glassBackdrop,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .graphicsLayer { translationY = max(hide, barOut.value) * barFullPx }
                                .appRevealRise(24.dp, lag = 0.25f),
                        )
                    }
                    vibe.tube.ui.components.ScrubThumbOverlay(scrubThumb)
                }
            }
            PlayerOverlayHost(
                player = appViewModel.engine.player,
                controller = controller,
                watch = watchViewModel,
                onLogin = { controller.collapse(); nav.navigate(LoginRoute) },
                onEnterPip = if (settings.pictureInPicture) onEnterPip else null,
                contentBottom = {
                    val floor = if (narrow) rootHeightPx - barLift() else contentBottomPx
                    floor.coerceAtLeast(0)
                },
                miniAway = miniAway,
            )
            if (playerState.expanded && playerState.visible) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    vibe.tube.ui.components.ToastHost(
                        snackbar,
                        download = toastJob,
                        onTogglePause = { toastTouch++; toastJob?.let { appViewModel.toggleDownloadPause(it.id) } },
                        onCancel = { toastJob?.let { appViewModel.cancelDownload(it.id) } },
                        onOpenDownloads = openDownloads,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    )
                }
            }
            downloadTarget?.let { video ->
                vibe.tube.feature.watch.DownloadDialog(
                    video = video,
                    onDismiss = appViewModel.events::dismissDownload,
                    loadOptions = { appViewModel.downloadOptions(video.id) },
                    onChoose = { choice -> appViewModel.events.dismissDownload(); appViewModel.download(video, choice, short = downloadVideoOnly || video.isShort) },
                    videoOnly = downloadVideoOnly,
                )
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
}

@Composable
private fun AppNavHost(nav: androidx.navigation.NavHostController, appViewModel: AppViewModel, avatarUrl: String?, onTab: (Any) -> Unit) {
    val noPadding = PaddingValues(0.dp)
    val authState by appViewModel.authState.collectAsStateWithLifecycle()
    val isGuest = authState is vibe.tube.account.AuthState.Guest
    val reduced = LocalReducedMotion.current
    val enterSpec = tween<IntOffset>(300, easing = EmphasizedDecelerate)
    val exitSpec = tween<IntOffset>(220, easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f))
    val fadeInSpec = tween<Float>(180, easing = LinearOutSlowInEasing)
    val fadeOutSpec = tween<Float>(120, easing = LinearEasing)

    fun tabIndex(d: NavDestination): Int = allTabs.indexOfFirst { d.hasRoute(it.route::class) }
    fun isSearch(d: NavDestination) = d.hasRoute(SearchRoute::class)

    fun enter(initial: NavDestination, target: NavDestination, popping: Boolean): EnterTransition {
        if (reduced) return fadeIn(tween(0))
        val from = tabIndex(initial)
        val to = tabIndex(target)
        return when {
            isSearch(target) || isSearch(initial) -> EnterTransition.None
            from >= 0 && to >= 0 -> {
                val dir = if (to > from) 1 else -1
                slideInHorizontally(enterSpec) { dir * it / 4 } + fadeIn(fadeInSpec)
            }
            popping -> slideInHorizontally(enterSpec) { -it / 8 } + fadeIn(fadeInSpec)
            else -> slideInHorizontally(enterSpec) { it / 5 } + fadeIn(fadeInSpec)
        }
    }

    fun exit(initial: NavDestination, target: NavDestination, popping: Boolean): ExitTransition {
        if (reduced) return fadeOut(tween(0))
        val from = tabIndex(initial)
        val to = tabIndex(target)
        return when {
            isSearch(initial) || isSearch(target) -> ExitTransition.None
            from >= 0 && to >= 0 -> {
                val dir = if (to > from) 1 else -1
                slideOutHorizontally(exitSpec) { -dir * it / 4 } + fadeOut(fadeOutSpec)
            }
            popping -> slideOutHorizontally(exitSpec) { it / 5 } + fadeOut(fadeOutSpec)
            else -> slideOutHorizontally(exitSpec) { -it / 8 } + fadeOut(fadeOutSpec)
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
                predictivePopEnterTransition = {
                    if (isSearch(initialState.destination) || isSearch(targetState.destination)) EnterTransition.None
                    else slideInHorizontally(tween(300, easing = LinearEasing)) { -it / 8 }
                },
                predictivePopExitTransition = { _ ->
                    if (isSearch(initialState.destination)) ExitTransition.None
                    else slideOutHorizontally(tween(300, easing = LinearEasing)) { it }
                },
            ) {
                page<HomeRoute> {
                    HomeScreen(
                        onSearch = { nav.navigate(SearchRoute()) },
                        onLogin = { nav.navigate(LoginRoute) },
                        onAccount = { onTab(AccountRoute) },
                        onNotifications = { nav.navigate(NotificationsRoute) },
                        onHistory = { nav.navigate(LocalHistoryRoute) },
                        avatarUrl = avatarUrl,
                        contentPadding = noPadding,
                        visibilityScope = this,
                    )
                }
                page<ShortsRoute> {
                    vibe.tube.feature.shorts.ShortsScreen(onLogin = { nav.navigate(LoginRoute) })
                }
                page<SubscriptionsRoute> {
                    SubscriptionsScreen(
                        onSearch = { nav.navigate(SearchRoute()) },
                        onLogin = { nav.navigate(LoginRoute) },
                        contentPadding = noPadding,
                        visibilityScope = this,
                    )
                }
                page<LibraryRoute> {
                    LibraryScreen(
                        onOpenHistory = { nav.navigate(YoutubeHistoryRoute) },
                        onOpenLocalHistory = { nav.navigate(LocalHistoryRoute) },
                        onOpenDownloads = { nav.navigate(DownloadsRoute) },
                        onOpenLiked = { nav.navigate(PlaylistRoute("LL")) },
                        onOpenWatchLater = { nav.navigate(PlaylistRoute("WL")) },
                        onOpenPlaylists = { nav.navigate(PlaylistsRoute) },
                        onLogin = { nav.navigate(LoginRoute) },
                        isGuest = isGuest,
                        contentPadding = noPadding,
                    )
                }
                page<AccountRoute> {
                    AccountScreen(onLogin = { nav.navigate(LoginRoute) }, onSettings = { nav.navigate(SettingsRoute) }, contentPadding = noPadding)
                }
                page<SearchRoute> {
                    SearchScreen(onBack = { nav.popBackStack() }, onLogin = { nav.navigate(LoginRoute) }, visibilityScope = this)
                }
                page<PlaylistRoute> {
                    CompositionLocalProvider(LocalQueueMode provides true) {
                        PlaylistScreen(onBack = { nav.popBackStack() }, onLogin = { nav.navigate(LoginRoute) }, session = appViewModel.activeSession)
                    }
                }
                page<ChannelRoute> { ChannelScreen(onBack = { nav.popBackStack() }, onLogin = { nav.navigate(LoginRoute) }) }
                page<YoutubeHistoryRoute> {
                    CompositionLocalProvider(LocalQueueMode provides true) {
                        YoutubeHistoryScreen(onBack = { nav.popBackStack() }, onLogin = { nav.navigate(LoginRoute) })
                    }
                }
                page<DownloadsRoute> { vibe.tube.feature.library.DownloadsScreen(onBack = { nav.popBackStack() }) }
                page<LocalHistoryRoute> { LocalHistoryScreen(onBack = { nav.popBackStack() }) }
                page<PlaylistsRoute> { PlaylistsScreen(onBack = { nav.popBackStack() }, onLogin = { nav.navigate(LoginRoute) }) }
                page<SettingsRoute> {
                    SettingsScreen(
                        onBack = { nav.popBackStack() },
                        onLicenses = { nav.navigate(LicensesRoute) },
                        onDebug = null,
                    )
                }
                page<NotificationsRoute> {
                    vibe.tube.feature.notifications.NotificationsScreen(
                        onBack = { nav.popBackStack() },
                        onLogin = { nav.navigate(LoginRoute) },
                        onOpen = { item ->
                            val target = item.target
                            if (target != null) {
                                appViewModel.events.requestOpenTarget(vibe.tube.feature.common.UiEvents.OpenTarget(target, item.title, item.thumbnail))
                            } else {
                                appViewModel.events.post(R.string.notify_target_unavailable)
                            }
                        },
                    )
                }
                page<LicensesRoute> { LicensesScreen(onBack = { nav.popBackStack() }) }
                page<LoginRoute> { LoginScreen(onClose = { nav.popBackStack() }) }
                debugDestinations(nav)
            }
        }
    }
}

@Composable
private fun PageFrame(scope: AnimatedContentScope, clip: Boolean, content: @Composable () -> Unit) {
    val transition = scope.transition
    val moving = transition.currentState != EnterExitState.Visible || transition.targetState != EnterExitState.Visible
    val radius = rememberDeviceCornerRadius()
    val background = MaterialTheme.colorScheme.background
    Box(
        if (moving && clip) Modifier.clip(RoundedCornerShape(radius)).background(background) else Modifier,
    ) { content() }
}

private inline fun <reified T : Any> NavGraphBuilder.page(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        val d = entry.destination
        val flat = allTabs.any { d.hasRoute(it.route::class) } || d.hasRoute(SearchRoute::class)
        PageFrame(this, clip = !flat) { content(entry) }
    }
}

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

@Composable
private fun PlayerOverlayHost(
    player: androidx.media3.common.Player,
    controller: vibe.tube.player.PlayerController,
    watch: vibe.tube.feature.watch.WatchViewModel,
    onLogin: () -> Unit,
    onEnterPip: (() -> Unit)?,
    contentBottom: () -> Int,
    miniAway: Boolean,
) {
    vibe.tube.feature.watch.PlayerVideoHost {
    PlayerOverlay(player = player, controller = controller, watch = watch, onLogin = onLogin, onEnterPip = onEnterPip, contentBottom = contentBottom, miniAway = miniAway)
    }
}

private data class PlayerFlags(
    val visible: Boolean = false,
    val expanded: Boolean = false,
    val fullscreen: Boolean = false,
    val inPip: Boolean = false,
)

internal fun openNotificationTarget(
    request: vibe.tube.feature.common.UiEvents.OpenTarget,
    controller: vibe.tube.player.PlayerController,
    nav: androidx.navigation.NavHostController,
) {
    when (val target = request.target) {
        is vibe.tube.youtube.model.NotificationTarget.Video -> {
            val list = target.playlistId
            if (list != null && list.startsWith("RD")) {
                controller.playMix(vibe.tube.youtube.model.PlaylistItem(list, request.title.orEmpty(), null, null, null, emptyList(), firstVideoId = target.videoId))
            } else {
                val video = VideoItem(
                    id = target.videoId,
                    title = request.title.orEmpty(),
                    channelName = null,
                    channelId = null,
                    channelAvatar = null,
                    thumbnails = listOfNotNull(request.thumbnail?.let { vibe.tube.youtube.model.Thumb(it, 480, 270) }),
                    durationText = null,
                    durationSeconds = null,
                    viewCountText = null,
                    publishedText = null,
                )
                controller.play(video, startAtMs = target.startSeconds?.let { it * 1000L })
            }
            if (target.openComments) controller.requestComments(target.videoId, target.commentId)
        }
        is vibe.tube.youtube.model.NotificationTarget.Channel -> {
            controller.collapse()
            nav.navigate(ChannelRoute(target.channelId)) { launchSingleTop = true }
        }
        is vibe.tube.youtube.model.NotificationTarget.Playlist -> {
            if (target.playlistId.startsWith("RD")) {
                controller.playMix(vibe.tube.youtube.model.PlaylistItem(target.playlistId, request.title.orEmpty(), null, null, null, emptyList()))
            } else {
                controller.collapse()
                nav.navigate(PlaylistRoute(target.playlistId, request.title)) { launchSingleTop = true }
            }
        }
    }
}

