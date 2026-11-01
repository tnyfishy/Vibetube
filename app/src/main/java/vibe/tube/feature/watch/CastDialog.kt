package vibe.tube.feature.watch

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import vibe.tube.player.dlna.DlnaCastManager

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CastDependencies {
    fun extractor(): vibe.tube.youtube.extractor.NewPipeAdapter
}

@Composable
internal fun CastDialog(videoId: String?, title: String, pauseLocal: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val devices by DlnaCastManager.devices.collectAsStateWithLifecycle()
    val scanning by DlnaCastManager.isDiscovering.collectAsStateWithLifecycle()
    val connected by DlnaCastManager.currentDevice.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(connected) { if (connected != null) pauseLocal() }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        DlnaCastManager.startDiscovery(context.applicationContext)
        onDispose { DlnaCastManager.stopDiscovery(); if (!DlnaCastManager.isCasting) DlnaCastManager.disconnect() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Cast · DLNA") }, text = {
        androidx.compose.foundation.layout.Column {
            if (scanning || loading) LinearProgressIndicator()
            if (!scanning && devices.isEmpty()) Text("Không tìm thấy TV. Hãy kết nối cùng mạng Wi-Fi.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            devices.forEach { device -> TextButton(enabled = !loading && videoId != null, onClick = {
                scope.launch {
                    loading = true
                    try {
                        val deps = dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, CastDependencies::class.java)
                        val info = deps.extractor().stream(videoId!!, vibe.tube.youtube.model.ContentLocale.Default)
                        DlnaCastManager.castStreamInfo(device, title, info, null)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { error = "Không thể gửi video tới TV. Hãy thử lại." }
                    finally { loading = false }
                }
            }) { Text(device.friendlyName) } }
            connected?.let { Text(it.friendlyName); TextButton(onClick = DlnaCastManager::pause) { Text("Tạm dừng") }
                TextButton(onClick = DlnaCastManager::disconnect) { Text("Ngắt kết nối") } }
        }
    }, confirmButton = { TextButton(onClick = { DlnaCastManager.startDiscovery(context.applicationContext) }) { Text("Tìm lại") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } })
}
