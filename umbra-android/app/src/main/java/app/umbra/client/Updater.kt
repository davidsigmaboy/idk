package app.umbra.client

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates. Every CI build publishes Umbra.apk as a GitHub release "android-<build>". The app asks GitHub for
 * the newest one; when its build number is above ours the APK is downloaded here and handed to the system installer
 * (Android asks the person to confirm: an app can not install itself silently). All builds are signed with the
 * same key, so the update installs over the current app and keeps the data.
 */
object Updater {
    private const val REPO = "davidsigmaboy/idk"
    private const val PREFIX = "android-"

    class Release(val build: Int, val url: String, val size: Long)

    /** The newest release if it is newer than this build, else null. Network failures give null. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Umbra")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            val list = JSONArray(body)
            var best: Release? = null
            for (i in 0 until list.length()) {
                val r = list.getJSONObject(i)
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
                val tag = r.optString("tag_name")
                if (!tag.startsWith(PREFIX)) continue
                val build = tag.removePrefix(PREFIX).toIntOrNull() ?: continue
                val assets = r.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (a.optString("name") == "Umbra.apk" && (best == null || build > best.build)) {
                        best = Release(build, a.getString("browser_download_url"), a.optLong("size"))
                    }
                }
            }
            best?.takeIf { it.build > BuildConfig.VERSION_CODE }
        }.getOrNull()
    }

    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "Umbra-${release.build}.apk")
        val conn = URL(release.url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Umbra")
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        if (out.length() < 1_000_000) throw IllegalStateException("The downloaded file is too small, try again")
        out
    }

    /** False when the person first has to allow installs from Umbra (the settings page is opened). */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}

@androidx.compose.runtime.Composable
fun UpdateDialog() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var release by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Updater.Release?>(null) }
    var progress by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var downloaded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<File?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) { release = Updater.check() }

    val r = release ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (progress == null) release = null },
        title = { androidx.compose.material3.Text("Update available") },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text(
                    "A new Umbra (build ${r.build}) is ready. You have build ${BuildConfig.VERSION_CODE}. " +
                        "It downloads here and Android then asks you to confirm the install; your chats stay."
                )
                progress?.let {
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
                    androidx.compose.material3.LinearProgressIndicator(progress = { it }, modifier = androidx.compose.ui.Modifier.fillMaxWidth())
                }
                error?.let {
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
                    androidx.compose.material3.Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = progress == null || downloaded != null,
                onClick = {
                    val ready = downloaded
                    if (ready != null) {
                        Updater.install(context, ready)
                    } else {
                        error = null
                        progress = 0f
                        scope.launch {
                            try {
                                val file = Updater.download(context, r) { progress = it }
                                downloaded = file
                                progress = 1f
                                Updater.install(context, file)
                            } catch (e: Exception) {
                                error = "Download failed: ${e.message}"
                                progress = null
                            }
                        }
                    }
                }
            ) { androidx.compose.material3.Text(if (downloaded != null) "Install" else "Update") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { release = null }, enabled = progress == null || downloaded != null) {
                androidx.compose.material3.Text("Later")
            }
        }
    )
}
