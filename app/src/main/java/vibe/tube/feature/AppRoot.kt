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
import kotlin.math.abs
import androidx.compose.foundation.layout.Box
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlin.math.max
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
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
    // Only these flags matter here. The raw state also changes with every playback-position tick, which would
    // recompose this whole screen several times a second (and hitch the player card while it morphs).
    val playerState by remember { controller.state.map { PlayerFlags(it.visible, it.expanded, it.fullscreen, it.inPip) }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = PlayerFlags())
    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    val avatarUrl by appViewModel.avatarUrl.collectAsStateWithLifecycle()
    val saveTarget by appViewModel.events.saveTarget.collectAsStateWithLifecycle()
    val downloadTarget by appViewModel.events.downloadTarget.collectAsStateWithLifecycle()
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
            override fun hideChannel(video: VideoItem) = appViewModel.hideChannel(video)
            override fun download(video: VideoItem) = appViewModel.events.requestDownload(video)
        }
    }

    val goToTab = remember(nav) {
        { tab: Tab ->
            val startRoute = nav.graph.findStartDestination()
            val onStart = tab.route::class.let { cls -> startRoute.hasRoute(cls) }
            if (onStart) {
                // Home is the bottom of the stack: pop straight back to it instead of re-navigating.
                if (nav.currentBackStackEntry?.destination?.hasRoute(tab.route::class) != true) {
                    if (!nav.popBackStack(tab.route, inclusive = false)) {
                        nav.navigate(tab.route) { launchSingleTop = true }
                    }
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
    // Reserved for as long as the player exists (expanded or not), so expanding/collapsing never changes the padding of
    // every list underneath in the middle of the morph animation.
    val miniVisible = playerState.visible && !playerState.inPip
    // The minimized player is a 16:9 card (MINI_WIDTH_FRACTION of the width) wide (see MorphPlayer); lists keep that much room below.
    val miniPadding = if (miniVisible) (windowConfig.screenWidthDp * vibe.tube.feature.watch.MINI_WIDTH_FRACTION * 9f / 16f).dp + 20.dp else 0.dp

    val hiddenIds = remember(settings.hiddenChannels)  { settings.hiddenChannelIds }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val narrow = windowConfig.screenWidthDp < 600
    val isBar = showNav && narrow
    // Leaving the tabs slides the bar down (and bringing it back slides it up) instead of cutting it in or out.
    val barOut = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (showNav) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "barOut",
    )
    val barOn by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { barOut.value < 0.999f } }
    val onSettings = destination?.hasRoute(SettingsRoute::class) == true
    val barBody = 60.dp
    val navInset = WindowInsets.navigationBars.getBottom(density)
    val barFullPx = with(density) { barBody.roundToPx() } + navInset
    val barFullDp = with(density) { barFullPx.toDp() }
    val homeTab = currentTab == 0
    var rootHeightPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var hide by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(currentTab, isBar) { hide = 0f }
    val barScroll = remember(barFullPx) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (barFullPx > 0 && available.y != 0f) hide = (hide - available.y / barFullPx).coerceIn(0f, 1f)
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                val target = if (hide > 0.5f) 1f else 0f
                androidx.compose.animation.core.animate(hide, target, animationSpec = androidx.compose.animation.core.tween(180)) { v, _ -> hide = v }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    // On Home the content runs underneath the bar (the bar only slides, it never resizes the page), so scrolling
    // never re-measures the feed. Other tabs reserve the bar's height.
    val reservedBottom = if (isBar && !homeTab) barFullDp else 0.dp
    val homeFeedPadding = if (isBar && homeTab) barFullDp else 0.dp
    // Distance (px) the mini player / snackbar sit above the bottom edge on Home.
    val homeLift: () -> Int = { max(barFullPx * (1f - max(hide, barOut.value)), navInset.toFloat()).roundToInt() }
    val barLift: () -> Int = {
        val h = if (homeTab || !showNav) max(hide, barOut.value) else barOut.value
        max(barFullPx * (1f - h), navInset.toFloat()).roundToInt()
    }
    CompositionLocalProvider(LocalVideoActions provides actions, vibe.tube.feature.watch.LocalSeekSeconds provides settings.seekSeconds, LocalMiniPlayerPadding provides (miniPadding + homeFeedPadding), vibe.tube.ui.components.LocalHiddenChannels provides hiddenIds) {
        val hazeState = dev.chrisbanes.haze.rememberHazeState()
        Box(Modifier.fillMaxSize().onSizeChanged { rootHeightPx = it.height }) {
            NavigationSuiteScaffold(
                layoutType = if (isBar) NavigationSuiteType.None else suiteType,
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
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(bottom = reservedBottom)
                            .onGloballyPositioned { contentBottomPx = (it.positionInRoot().y + it.size.height).roundToInt() }
                            .hazeSource(hazeState)
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
                        SnackbarHost(
                            snackbar,
                            Modifier.align(Alignment.BottomCenter).padding(bottom = miniPadding).then(lift),
                        )
                    }
                    if (narrow && barOn) {
                        // Frosted glass: the feed that scrolls underneath is blurred behind the bar.
                        val glassTint = MaterialTheme.colorScheme.surfaceContainer
                        Surface(
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(barFullDp)
                                .graphicsLayer { translationY = max(hide, barOut.value) * barFullPx }
                                .hazeEffect(hazeState) {
                                    blurRadius = 24.dp
                                    noiseFactor = 0f
                                    tints = listOf(dev.chrisbanes.haze.HazeTint(glassTint.copy(alpha = 0.72f)))
                                },
                        ) {
                            Row(Modifier.fillMaxWidth().height(barBody)) {
                                tabs.forEach { tab ->
                                    val selected = destination?.hasRoute(tab.route::class) == true
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, role = androidx.compose.ui.semantics.Role.Tab) { goToTab(tab) },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        val pillAlpha by androidx.compose.animation.core.animateFloatAsState(
                                            if (selected) 1f else 0f,
                                            androidx.compose.animation.core.tween(200),
                                            label = "navPill",
                                        )
                                        val pillColor = MaterialTheme.colorScheme.secondaryContainer
                                        Box(
                                            Modifier.size(width = 56.dp, height = 30.dp)
                                                .drawBehind { drawRoundRect(pillColor.copy(alpha = pillColor.alpha * pillAlpha), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)) },
                                            contentAlignment = Alignment.Center,
                                        ) { VIconImage(if (selected) tab.iconSelected else tab.icon, null, Modifier.size(22.dp)) }
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            stringResource(tab.label),
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            PlayerOverlayHost(
                player = appViewModel.engine.player,
                controller = controller,
                watch = watchViewModel,
                onLogin = { controller.collapse(); nav.navigate(LoginRoute) },
                onEnterPip = if (settings.pictureInPicture) onEnterPip else null,
                contentBottom = {
                    if (narrow && barOn) (rootHeightPx - barLift()).coerceAtLeast(0) else contentBottomPx
                },
                miniAway = onSettings,
            )
            val welcomePrefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("app_state", android.content.Context.MODE_PRIVATE)
            var welcomeSeen by androidx.compose.runtime.remember {
                val seen = welcomePrefs.getBoolean("welcome_seen", false)
                // First launch: keep the home heading off screen under the welcome page until it is time to charge in.
                // Every cold start plays the intro: straight away when the welcome page was already seen.
                if (!vibe.tube.ui.components.HomeIntro.played) {
                    vibe.tube.ui.components.HomeIntro.phase = if (seen) vibe.tube.ui.components.HomeIntro.RUN else vibe.tube.ui.components.HomeIntro.HOLD
                }
                androidx.compose.runtime.mutableStateOf(seen)
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = !welcomeSeen,
                enter = androidx.compose.animation.EnterTransition.None,
                exit = androidx.compose.animation.fadeOut(tween(260)),
            ) {
                vibe.tube.feature.welcome.WelcomeScreen(onDone = {
                    welcomePrefs.edit().putBoolean("welcome_seen", true).apply()
                    welcomeSeen = true
                    vibe.tube.ui.components.HomeIntro.phase = vibe.tube.ui.components.HomeIntro.RUN
                })
            }
            if (playerState.expanded && playerState.visible) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    SnackbarHost(snackbar, Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                }
            }
            downloadTarget?.let { video ->
                vibe.tube.feature.watch.DownloadDialog(
                    video = video,
                    onDismiss = appViewModel.events::dismissDownload,
                    loadOptions = { appViewModel.downloadOptions(video.id) },
                    onChoose = { choice -> appViewModel.events.dismissDownload(); appViewModel.download(video, choice) },
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

@Composable
private fun AppNavHost(nav: androidx.navigation.NavHostController, appViewModel: AppViewModel, avatarUrl: String?, onTab: (Any) -> Unit) {
    val noPadding = PaddingValues(0.dp)
    val authState by appViewModel.authState.collectAsStateWithLifecycle()
    val isGuest = authState is vibe.tube.account.AuthState.Guest
    val reduced = LocalReducedMotion.current
    // Plain tweens with M3 emphasized easing: no springs, so nothing overshoots or bounces.
    val enterSpec = tween<IntOffset>(300, easing = EmphasizedDecelerate)
    val exitSpec = tween<IntOffset>(220, easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f))
    val fadeInSpec = tween<Float>(220, delayMillis = 40, easing = LinearOutSlowInEasing)
    val fadeOutSpec = tween<Float>(120, easing = LinearEasing)

    fun tabIndex(d: NavDestination): Int = tabs.indexOfFirst { d.hasRoute(it.route::class) }
    fun isSearch(d: NavDestination) = d.hasRoute(SearchRoute::class)

    // Direction-aware shared-axis motion: tabs slide sideways in the swipe direction, detail screens
    // drift in from the right, and search only fades (its container transform does the rest).
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
                // The back-gesture preview: the leaving page just follows the finger to the right (clipped to the
                // device's corner radius) while the page below is already in place. No default 0.9 scale-down.
                predictivePopEnterTransition = { EnterTransition.None },
                // Search never slides away (its body just fades, the header hands over to the home pill); sliding a half-faded
                // page across the screen is what left a ghost streak on the back gesture.
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
                        avatarUrl = avatarUrl,
                        contentPadding = noPadding,
                        visibilityScope = this,
                    )
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
                page<LicensesRoute> { LicensesScreen(onBack = { nav.popBackStack() }) }
                page<LoginRoute> { LoginScreen(onClose = { nav.popBackStack() }) }
                debugDestinations(nav)
            }
        }
    }
}

/**
 * Wraps a destination so that, while it enters or leaves (including the back-gesture preview), it is opaque and
 * clipped to the device's own corner radius; at rest it adds nothing.
 */
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
        val flat = tabs.any { d.hasRoute(it.route::class) } || d.hasRoute(SearchRoute::class)
        PageFrame(this, clip = !flat) { content(entry) }
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
    // The bottom edge is passed as a lambda and only read in the layout phase, so the bar sliding does not recompose the player.
    PlayerOverlay(player = player, controller = controller, watch = watch, onLogin = onLogin, onEnterPip = onEnterPip, contentBottom = contentBottom, miniAway = miniAway)
}

/** The few player facts the app shell reacts to; unlike the full state it does not change while playing. */
private data class PlayerFlags(
    val visible: Boolean = false,
    val expanded: Boolean = false,
    val fullscreen: Boolean = false,
    val inPip: Boolean = false,
)
