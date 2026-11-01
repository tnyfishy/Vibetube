package vibe.tube.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vibe.tube.R
import vibe.tube.ui.VIcon

data class StateIconSpec(@DrawableRes val normal: Int, @DrawableRes val selected: Int,
    @StringRes val normalLabel: Int, @StringRes val selectedLabel: Int, val sizeDp: Int = 24)

object StateIcons {
    val Fullscreen = StateIconSpec(VIcon.Fullscreen, VIcon.FullscreenExit, R.string.action_fullscreen, R.string.action_exit_fullscreen)
    val Subtitles = StateIconSpec(VIcon.SubtitlesOff, VIcon.Subtitles, R.string.player_caption_on, R.string.player_caption_off)
    val Play = StateIconSpec(VIcon.PlayArrowFilled, VIcon.Pause, R.string.action_play, R.string.action_pause)
    val Like = StateIconSpec(VIcon.ThumbUp, VIcon.ThumbUpFilled, R.string.action_like, R.string.action_like, 18)
    val Dislike = StateIconSpec(VIcon.ThumbDown, VIcon.ThumbDownFilled, R.string.action_dislike, R.string.action_dislike, 18)
    val Home = StateIconSpec(VIcon.Home, VIcon.HomeFilled, R.string.nav_home, R.string.nav_home)
    val Search = StateIconSpec(VIcon.Search, VIcon.Search, R.string.action_search, R.string.action_search)
    val Settings = StateIconSpec(VIcon.Settings, VIcon.Settings, R.string.player_options, R.string.player_options)
}

@Composable
fun StateIconButton(spec: StateIconSpec, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, tint: Color = Color.Unspecified) {
    TooltipIconButton(if (selected) spec.selected else spec.normal, stringResource(if (selected) spec.selectedLabel else spec.normalLabel),
        onClick, modifier, enabled, tint, animateIcon = true, iconSize = spec.sizeDp.dp)
}
