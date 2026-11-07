package app.umbra.client

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thin wrapper over the official TDLib (Telegram API). */
object Td {
    private const val TAG = "UmbraTd"

    private var client: Client? = null
    private lateinit var appContext: Context

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState = _authState.asStateFlow()

    private val _connectionState = MutableStateFlow<TdApi.ConnectionState?>(null)
    val connectionState = _connectionState.asStateFlow()

    private val _fatalError = MutableStateFlow<String?>(null)
    val fatalError = _fatalError.asStateFlow()

    val updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 256)

    // TDLib can deliver its first update before Client.create() has returned, when `client` is still
    // null. The parameters used to be dropped then and the app waited forever. The flag remembers it.
    @Volatile private var paramsPending = false

    @Synchronized
    fun start(context: Context) {
        if (client != null) return
        appContext = context.applicationContext
        _fatalError.value = null

        try {
            Client.execute(TdApi.SetLogVerbosityLevel().apply { newVerbosityLevel = 1 })
            client = Client.create(
                { obj -> onUpdate(obj) },
                { e ->
                    Log.e(TAG, "TDLib exception", e)
                    _fatalError.value = "TDLib exception: ${e.message}"
                },
                { e ->
                    Log.e(TAG, "TDLib fatal", e)
                    _fatalError.value = "TDLib fatal: ${e.message}"
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create TDLib client", t)
            _fatalError.value =
                "Failed to start TDLib: ${t.message}\n\nNative libraries may be missing from the APK."
            client = null
        }
        if (paramsPending && client != null) {
            paramsPending = false
            sendParameters()
        }
    }

    private fun onUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> {
                val s = obj.authorizationState
                Log.d(TAG, "auth → ${s.javaClass.simpleName}")
                if (s is TdApi.AuthorizationStateWaitTdlibParameters) {
                    synchronized(this) { if (client == null) paramsPending = true else sendParameters() }
                }
                _authState.value = s
            }
            is TdApi.UpdateConnectionState -> {
                _connectionState.value = obj.state
            }
            is TdApi.Error -> {
                Log.e(TAG, "TDLib error update ${obj.code}: ${obj.message}")
                // Don't freeze the whole UI on transient errors
            }
        }
        updates.tryEmit(obj)
    }

    private fun sendParameters() {
        val db = File(appContext.filesDir, "td").apply { mkdirs() }
        val files = File(appContext.filesDir, "td-files").apply { mkdirs() }

        val p = TdApi.SetTdlibParameters()
        p.databaseDirectory = db.absolutePath
        p.filesDirectory = files.absolutePath
        p.useMessageDatabase = true
        p.useFileDatabase = true
        p.useChatInfoDatabase = true
        p.useSecretChats = false
        p.apiId = BuildConfig.TG_API_ID
        p.apiHash = BuildConfig.TG_API_HASH
        p.systemLanguageCode = java.util.Locale.getDefault().language.ifBlank { "en" }
        p.deviceModel = android.os.Build.MODEL.ifBlank { "Android" }
        p.systemVersion = "Android ${android.os.Build.VERSION.RELEASE}"
        p.applicationVersion = BuildConfig.VERSION_NAME

        Log.d(TAG, "SetTdlibParameters api_id=${p.apiId}")

        client?.send(p) { result ->
            when (result) {
                is TdApi.Ok -> {
                    Log.d(TAG, "SetTdlibParameters OK")
                    client?.send(TdApi.SetOption().apply {
                        name = "online"
                        value = TdApi.OptionValueBoolean(true)
                    }, null)
                    client?.send(TdApi.SetNetworkType(TdApi.NetworkTypeOther()), null)
                }
                is TdApi.Error -> {
                    Log.e(TAG, "SetTdlibParameters failed ${result.code}: ${result.message}")
                    _fatalError.value = "${result.code}: ${result.message}"
                }
                else -> Log.w(TAG, "SetTdlibParameters unexpected: $result")
            }
        }
    }

    suspend fun logout() {
        try {
            call(TdApi.LogOut())
        } catch (_: Exception) {
        }
        client?.send(TdApi.Close(), null)
        client = null
        _authState.value = null
        _connectionState.value = null
        _fatalError.value = null
        paramsPending = false
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> call(f: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            val c = client ?: run {
                cont.resumeWithException(IllegalStateException("TDLib not started"))
                return@suspendCancellableCoroutine
            }
            c.send(f) { r ->
                when (r) {
                    is TdApi.Error -> cont.resumeWithException(RuntimeException("${r.code}: ${r.message}"))
                    else -> cont.resume(r as T)
                }
            }
        }
}
