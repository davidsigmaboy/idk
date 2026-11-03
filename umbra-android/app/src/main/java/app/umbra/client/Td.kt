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

    /** Human-readable last fatal / setup error (shown on the spinner screen). */
    private val _fatalError = MutableStateFlow<String?>(null)
    val fatalError = _fatalError.asStateFlow()

    val updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 256)

    @Synchronized
    fun start(context: Context) {
        if (client != null) return
        appContext = context.applicationContext
        _fatalError.value = null

        // Catch missing credentials early — otherwise TDLib stays on WaitTdlibParameters forever.
        if (BuildConfig.TG_API_ID == 0 || BuildConfig.TG_API_HASH.isBlank() || BuildConfig.TG_API_HASH == "none") {
            _fatalError.value =
                "API keys missing.\n\n" +
                "Set GitHub Secrets TG_API_ID and TG_API_HASH\n" +
                "(from my.telegram.org), then rebuild the APK.\n\n" +
                "Current api_id=${BuildConfig.TG_API_ID}"
            Log.e(TAG, _fatalError.value!!)
            return
        }

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
                "Failed to start TDLib: ${t.message}\n\n" +
                "Native libraries may be missing from the APK."
            client = null
        }
    }

    private fun onUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> {
                val s = obj.authorizationState
                Log.d(TAG, "auth → ${s.javaClass.simpleName}")
                if (s is TdApi.AuthorizationStateWaitTdlibParameters) {
                    sendParameters()
                }
                _authState.value = s
            }
            is TdApi.UpdateConnectionState -> {
                _connectionState.value = obj.state
            }
            is TdApi.Error -> {
                Log.e(TAG, "TDLib error update ${obj.code}: ${obj.message}")
                _fatalError.value = "${obj.code}: ${obj.message}"
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

        Log.d(TAG, "SetTdlibParameters api_id=${p.apiId} db=${p.databaseDirectory}")

        client?.send(p) { result ->
            when (result) {
                is TdApi.Ok -> {
                    Log.d(TAG, "SetTdlibParameters OK")
                    // Tell TDLib the network is available so it proceeds.
                    client?.send(TdApi.SetOption().apply {
                        name = "online"
                        value = TdApi.OptionValueBoolean(true)
                    }, null)
                    client?.send(TdApi.SetNetworkType(TdApi.NetworkTypeWiFi()), null)
                }
                is TdApi.Error -> {
                    Log.e(TAG, "SetTdlibParameters failed ${result.code}: ${result.message}")
                    _fatalError.value = "SetTdlibParameters failed:\n${result.code}: ${result.message}"
                }
                else -> Log.w(TAG, "SetTdlibParameters unexpected: $result")
            }
        }
    }

    /** Logs out and clears local TDLib databases. */
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
