package vibe.tube.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import vibe.tube.BuildConfig
import vibe.tube.R
import vibe.tube.network.await

@Serializable
data class UpdateManifest(val packageName: String, val versionCode: Long, val versionName: String,
    val apkUrl: String, val sha256: String, val size: Long, val notes: String = "", val minSdk: Int = 26, val channel: String = "stable") {
    fun validate(installedPackage: String, installedVersion: Long, sdk: Int): Boolean =
        packageName == installedPackage && versionCode > installedVersion && minSdk <= sdk && channel == "stable" &&
            size in 1..134_217_728 && sha256.matches(Regex("[a-fA-F0-9]{64}")) && trustedUrl(apkUrl)
    companion object {
        fun trustedUrl(value: String): Boolean {
            val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
            return uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
                uri.host in setOf("github.com", "raw.githubusercontent.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
        }
    }
}

data class UpdateState(val busy: Boolean = false, val manifest: UpdateManifest? = null,
    val progress: Float? = null, val message: Int? = null, val ready: Boolean = false)

@HiltViewModel
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val source: UpdateSource,
) : ViewModel() {
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var task: Job? = null
    @Volatile private var activeCall: okhttp3.Call? = null
    private val apk get() = File(context.cacheDir, "updates/vibetube.apk")
    private val part get() = File(context.cacheDir, "updates/vibetube.part")

    fun check() {
        if (mutable.value.busy) return
        task = viewModelScope.launch {
            mutable.value = UpdateState(busy = true)
            try {
                val manifest = source.fetch { activeCall = it }
                if (manifest == null || manifest.versionCode <= BuildConfig.VERSION_CODE) {
                    mutable.value = UpdateState(message = R.string.update_current)
                } else {
                    check(manifest.validate(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT))
                    mutable.value = UpdateState(manifest = manifest)
                }
            } catch (e: CancellationException) { mutable.update { it.copy(busy = false, progress = null) }; throw e }
            catch (_: Exception) { mutable.value = UpdateState(message = R.string.update_check_failed) }
        }
    }

    fun download() {
        val manifest = mutable.value.manifest ?: return
        if (mutable.value.busy) return
        task = viewModelScope.launch {
            mutable.update { it.copy(busy = true, progress = 0f, message = null, ready = false) }
            try {
                withContext(Dispatchers.IO) {
                    check(manifest.validate(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT))
                    part.parentFile!!.mkdirs()
                    part.delete(); apk.delete()
                    val call = http.newCall(Request.Builder().url(manifest.apkUrl).build()).also { activeCall = it }
                    val cancellation = currentCoroutineContext().job.invokeOnCompletion { if (it != null) call.cancel() }
                    try {
                        call.await().use { response ->
                            check(response.isSuccessful && UpdateManifest.trustedUrl(response.request.url.toString()))
                            val body = response.body ?: error("empty apk")
                            var count = 0L
                            val digest = MessageDigest.getInstance("SHA-256")
                            body.byteStream().use { input -> part.outputStream().use { output ->
                                val buffer = ByteArray(32_768)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    count += read; check(count <= manifest.size)
                                    digest.update(buffer, 0, read); output.write(buffer, 0, read)
                                    mutable.update { it.copy(progress = (count.toFloat() / manifest.size).coerceIn(0f, 1f)) }
                                }
                            } }
                            check(count == manifest.size)
                            check(digest.digest().joinToString("") { "%02x".format(it) }.equals(manifest.sha256, ignoreCase = true))
                        }
                        verifyArchive(part, manifest)
                        check(part.renameTo(apk))
                    } finally { cancellation.dispose(); part.delete() }
                }
                mutable.update { it.copy(busy = false, progress = null, ready = true) }
            } catch (e: CancellationException) { mutable.update { it.copy(busy = false, progress = null) }; throw e }
            catch (_: Exception) { mutable.update { it.copy(busy = false, progress = null, message = R.string.update_download_failed) } }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File, manifest: UpdateManifest) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("invalid apk")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val version = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        check(candidate.packageName == context.packageName && version == manifest.versionCode)
        check(candidate.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true)
        check((candidate.applicationInfo?.flags ?: ApplicationInfo.FLAG_DEBUGGABLE) and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        fun signatures(info: android.content.pm.PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val incoming = signatures(candidate)?.map { it.toByteArray().contentHashCode() to it.toByteArray() } ?: error("unsigned")
        val current = signatures(installed)?.map { it.toByteArray() } ?: error("unsigned installed")
        check(incoming.isNotEmpty() && incoming.size == current.size && incoming.all { (_, bytes) -> current.any { it.contentEquals(bytes) } })
    }

    fun cancel() { activeCall?.cancel(); task?.cancel() }

    override fun onCleared() { activeCall?.cancel(); super.onCleared() }

    fun install() {
        if (!mutable.value.ready || !apk.isFile) return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            mutable.update { it.copy(message = R.string.update_allow_install) }
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { mutable.update { it.copy(message = R.string.update_install_failed) } }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { mutable.update { it.copy(message = R.string.update_install_failed) } }
    }
}
