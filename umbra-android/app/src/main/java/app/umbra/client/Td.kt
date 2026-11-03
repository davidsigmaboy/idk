package app.umbra.client

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thin wrapper over the official TDLib (Telegram API). */
object Td {
    private var client: Client? = null
    private lateinit var appContext: Context

    val authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 128)

    fun start(context: Context) {
        if (client != null) return
        appContext = context.applicationContext
        Client.execute(TdApi.SetLogVerbosityLevel().apply { newVerbosityLevel = 1 })
        client = Client.create({ obj -> onUpdate(obj) }, null, null)
    }

    private fun onUpdate(obj: TdApi.Object) {
        if (obj is TdApi.UpdateAuthorizationState) {
            val s = obj.authorizationState
            if (s is TdApi.AuthorizationStateWaitTdlibParameters) sendParameters()
            authState.value = s
        }
        updates.tryEmit(obj)
    }

    private fun sendParameters() {
        val p = TdApi.SetTdlibParameters()
        p.databaseDirectory = appContext.filesDir.absolutePath + "/td"
        p.useMessageDatabase = true
        p.useFileDatabase = true
        p.useChatInfoDatabase = true
        p.useSecretChats = false
        p.apiId = BuildConfig.TG_API_ID
        p.apiHash = BuildConfig.TG_API_HASH
        p.systemLanguageCode = java.util.Locale.getDefault().language
        p.deviceModel = android.os.Build.MODEL
        p.applicationVersion = BuildConfig.VERSION_NAME
        client?.send(p, null)
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> call(f: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            client!!.send(f) { r ->
                when (r) {
                    is TdApi.Error -> cont.resumeWithException(RuntimeException("${r.code}: ${r.message}"))
                    else -> cont.resume(r as T)
                }
            }
        }
}
