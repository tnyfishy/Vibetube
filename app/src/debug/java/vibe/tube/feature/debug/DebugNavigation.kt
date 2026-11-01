package vibe.tube.feature.debug

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import vibe.tube.feature.nav.DiagnosticsRoute
import vibe.tube.feature.nav.GalleryRoute

fun NavGraphBuilder.debugDestinations(nav: NavHostController) {
    composable<GalleryRoute> {
        ComponentGalleryScreen(onBack = { nav.popBackStack() }, onDiagnostics = { nav.navigate(DiagnosticsRoute) })
    }
    composable<DiagnosticsRoute> {
        DiagnosticsScreen(onBack = { nav.popBackStack() })
    }
}
