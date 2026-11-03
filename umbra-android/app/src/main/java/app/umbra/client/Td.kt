package app.umbra.client

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thin wrapper over the official TDLib (Telegram API). */
object Td {
    private var client: Client? = null
    private lateinit var appContext: Context

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState = _authState.asStateFlow()

    private val _connectionState = MutableStateFlow<TdApi.ConnectionState?>(null)
    val connectionState = _connectionState.asStateFlow()

    val updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 256)

    @Synchronized
    fun start(context: Context) {
        if (client != null) return
        appContext = context.applicationContext
        Client.execute(TdApi.SetLogVerbosityLevel().apply { newVerbosityLevel = 1 })
        client = Client.create({ obj -> onUpdate(obj) }, null, null)
    }

    private fun onUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> {
                val s = obj.authorizationState
                if (s is TdApi.AuthorizationStateWaitTdlibParameters) sendParameters()
                _authState.value = s
            }
            is TdApi.UpdateConnectionState -> {
                _connectionState.value = obj.state
            }
        }
        updates.tryEmit(obj)
    }

    private fun sendParameters() {
        val p = TdApi.SetTdlibParameters()
        p.databaseDirectory = appContext.filesDir.absolutePath + "/td"
        p.filesDirectory = appContext.filesDir.absolutePath + "/td-files"
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
        client?.send(p, null)
    }

    /** Logs out and clears local TDLib databases. */
    suspend fun logout() {
        try {
            call(TdApi.LogOut())
        } catch (_: Exception) {
            // already logged out or client dead
        }
        // Force close so next start is clean
        client?.send(TdApi.Close(), null)
        client = null
        _authState.value = null
        _connectionState.value = null
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
