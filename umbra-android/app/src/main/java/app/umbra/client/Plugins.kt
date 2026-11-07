package app.umbra.client

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * exteraGram-style plugins (".plugin" Python files) through the shim in `plugins-shim`.
 *
 * Python comes from Chaquopy. It is reached by reflection, so the app also compiles and runs in a build
 * without it (the CI falls back to such a build if Chaquopy can not be fetched): [available] is then false.
 * The shim speaks JSON: handle(json) -> json.
 */
object Plugins {
    private val lock = Any()
    private var module: Any? = null
    private var failure: String? = null

    fun dir(context: Context): File = File(context.filesDir, "plugins").apply { mkdirs() }

    /** Null if Python started, else the reason. */
    fun problem(context: Context): String? {
        ensure(context)
        return if (module != null) null else (failure ?: "Python is not available")
    }

    private fun ensure(context: Context): Boolean = synchronized(lock) {
        if (module != null) return true
        if (failure != null) return false
        try {
            val pythonClass = Class.forName("com.chaquo.python.Python")
            val platformBase = Class.forName("com.chaquo.python.Python\$Platform")
            val androidPlatform = Class.forName("com.chaquo.python.android.AndroidPlatform")
            if (!(pythonClass.getMethod("isStarted").invoke(null) as Boolean)) {
                val platform = androidPlatform.getConstructor(Context::class.java).newInstance(context.applicationContext)
                pythonClass.getMethod("start", platformBase).invoke(null, platform)
            }
            val data = File(context.filesDir, "plugin-data").apply { mkdirs() }
            android.system.Os.setenv("UMBRA_PLUGIN_DATA", data.absolutePath, true)
            val python = pythonClass.getMethod("getInstance").invoke(null)
            module = pythonClass.getMethod("getModule", String::class.java).invoke(python, "umbra_bootstrap")
            true
        } catch (t: Throwable) {
            failure = if (t is ClassNotFoundException) "This build has no Python (plugins are unavailable)" else "${t.javaClass.simpleName}: ${t.message}"
            false
        }
    }

    private fun request(context: Context, body: JSONObject): JSONObject? {
        if (!ensure(context)) return null
        return try {
            val m = module!!
            val call = m.javaClass.getMethod("callAttr", String::class.java, Array<Any?>::class.java)
            val text = call.invoke(m, "handle", arrayOf<Any?>(body.toString())).toString()
            JSONObject(text).takeIf { it.optBoolean("ok") }
        } catch (t: Throwable) {
            failure = "${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }

    fun installedFiles(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".plugin") }?.sortedBy { it.name } ?: emptyList()

    /** Copies a picked ".plugin" file into the plugins folder. */
    suspend fun install(context: Context, uri: Uri, name: String): Boolean = withContext(Dispatchers.IO) {
        val safe = name.substringAfterLast('/').let { if (it.endsWith(".plugin")) it else "$it.plugin" }
        context.contentResolver.openInputStream(uri)?.use { input ->
            File(dir(context), safe).outputStream().use { input.copyTo(it) }
            true
        } ?: false
    }

    fun remove(context: Context, file: File) {
        file.delete()
    }

    /** Loaded plugin count and the load errors (file name to message); null if Python is not available. */
    suspend fun status(context: Context): Pair<Int, Map<String, String>>? = withContext(Dispatchers.IO) {
        val r = request(context, JSONObject().put("op", "sync").put("dir", dir(context).absolutePath))?.optJSONObject("result")
            ?: return@withContext null
        val errors = mutableMapOf<String, String>()
        r.optJSONObject("errors")?.let { e -> e.keys().forEach { errors[it] = e.optString(it) } }
        Pair(r.optInt("count"), errors)
    }

    /**
     * The outgoing text after the plugins. Null means "a plugin cancelled the message". Without plugins or
     * without Python the text comes back unchanged.
     */
    suspend fun transformOutgoing(context: Context, text: String): String? = withContext(Dispatchers.IO) {
        if (installedFiles(context).isEmpty() || !ensure(context)) return@withContext text
        request(context, JSONObject().put("op", "sync").put("dir", dir(context).absolutePath))
        val r = request(
            context,
            JSONObject().put("op", "send").put("account", 0).put("params", JSONObject().put("message", text))
        )?.optJSONObject("result") ?: return@withContext text
        if (r.optBoolean("cancel")) return@withContext null
        r.optJSONObject("params")?.optString("message", text) ?: text
    }
}
