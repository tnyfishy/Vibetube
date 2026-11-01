package vibe.tube.feature.common

import androidx.annotation.StringRes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import vibe.tube.R
import vibe.tube.core.AppError
import vibe.tube.youtube.model.VideoItem

data class UiMessage(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList(),
    @StringRes val actionResId: Int? = null,
    val icon: Int? = null,
    val onAction: (() -> Unit)? = null,
)

class UiEvents {
    private val messageFlow = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<UiMessage> = messageFlow.asSharedFlow()

    private val saveTargetState = MutableStateFlow<VideoItem?>(null)
    val saveTarget: StateFlow<VideoItem?> = saveTargetState.asStateFlow()

    private val downloadTargetState = MutableStateFlow<VideoItem?>(null)
    val downloadTarget: StateFlow<VideoItem?> = downloadTargetState.asStateFlow()

    private val downloadToastState = MutableStateFlow<Long?>(null)
    val downloadToast: StateFlow<Long?> = downloadToastState.asStateFlow()

    fun showDownloadToast(jobId: Long) {
        downloadToastState.value = jobId
    }

    fun hideDownloadToast(jobId: Long? = null) {
        if (jobId == null || downloadToastState.value == jobId) downloadToastState.value = null
    }

    private val openDownloadsChannel = Channel<Unit>(Channel.CONFLATED)
    val openDownloadsRequests = openDownloadsChannel.receiveAsFlow()

    fun requestOpenDownloads() {
        openDownloadsChannel.trySend(Unit)
    }

    private val openNotificationsChannel = Channel<Unit>(Channel.CONFLATED)
    val openNotificationsRequests = openNotificationsChannel.receiveAsFlow()

    fun requestOpenNotifications() {
        openNotificationsChannel.trySend(Unit)
    }

    private val openUpdateChannel = Channel<Unit>(Channel.CONFLATED)
    val openUpdateRequests = openUpdateChannel.receiveAsFlow()

    fun requestOpenUpdate() {
        openUpdateChannel.trySend(Unit)
    }

    data class OpenTarget(val target: vibe.tube.youtube.model.NotificationTarget, val title: String? = null, val thumbnail: String? = null)

    private val openTargetChannel = Channel<OpenTarget>(Channel.CONFLATED)
    val openTargetRequests = openTargetChannel.receiveAsFlow()

    fun requestOpenTarget(request: OpenTarget) {
        openTargetChannel.trySend(request)
    }

    private val downloadVideoOnlyState = MutableStateFlow(false)
    val downloadVideoOnly: StateFlow<Boolean> = downloadVideoOnlyState.asStateFlow()

    fun requestDownload(video: VideoItem, videoOnly: Boolean = false) {
        downloadVideoOnlyState.value = videoOnly
        downloadTargetState.value = video
    }

    fun dismissDownload() {
        downloadTargetState.value = null
    }

    private val homeReselectFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val homeReselect: SharedFlow<Unit> = homeReselectFlow.asSharedFlow()

    fun requestHomeReselect() {
        homeReselectFlow.tryEmit(Unit)
    }

    fun post(message: UiMessage) {
        messageFlow.tryEmit(message)
    }

    fun post(@StringRes resId: Int, vararg args: Any) {
        messageFlow.tryEmit(UiMessage(resId, args.toList()))
    }

    fun postError(error: Throwable) {
        messageFlow.tryEmit(error.toUiMessage())
    }

    fun requestSave(video: VideoItem) {
        saveTargetState.value = video
    }

    fun dismissSave() {
        saveTargetState.value = null
    }
}

fun Throwable.toUiMessage(): UiMessage = when (this) {
    is AppError.SessionExpired -> UiMessage(R.string.msg_session_expired)
    is AppError.LoginRequired -> UiMessage(R.string.msg_login_required)
    is AppError.Network -> UiMessage(R.string.msg_network_error)
    is AppError.Rejected -> UiMessage(R.string.msg_action_rejected)
    is AppError.Unsupported -> UiMessage(R.string.msg_unsupported)
    is AppError.Parse -> UiMessage(R.string.msg_parse_error)
    else -> UiMessage(R.string.msg_generic_error)
}
