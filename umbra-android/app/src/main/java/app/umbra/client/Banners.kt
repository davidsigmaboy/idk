package app.umbra.client

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Profile banners, visible only in Umbra.
 *
 * A person's banner is the newest photo with the caption "#banner" that THE PERSON HIMSELF sent to the public
 * group @umbra_banners. The sender of a Telegram message can not be faked, so nobody can put a banner on
 * another person's profile. Photos are loaded like any other message media (TDLib file cache).
 */
object Banners {
    const val GROUP = "umbra_banners"
    const val TAG = "#banner"
    private const val TTL_MS = 24L * 60 * 60 * 1000

    private class Entry(val path: String?, val at: Long)

    private val cache = ConcurrentHashMap<Long, Entry>()
    private val searchLock = Mutex()   // one search at a time: Telegram limits message search
    @Volatile private var groupId: Long? = null

    private suspend fun group(): Long {
        groupId?.let { return it }
        val chat = Td.call(TdApi.SearchPublicChat().apply { username = GROUP })
        groupId = chat.id
        return chat.id
    }

    /** Local file of the person's banner, or null if there is none. Cached for 24 hours; failures are not cached. */
    suspend fun load(userId: Long, force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        val cached = cache[userId]
        if (!force && cached != null && now - cached.at < TTL_MS) {
            val p = cached.path
            if (p == null || File(p).exists()) return p
        }
        val path = searchLock.withLock { fetch(userId) }
        cache[userId] = Entry(path, now)
        return path
    }

    private suspend fun fetch(userId: Long): String? {
        val chatId = group()
        val found = Td.call(TdApi.SearchChatMessages().apply {
            this.chatId = chatId
            query = TAG
            senderId = TdApi.MessageSenderUser(userId)
            fromMessageId = 0
            offset = 0
            limit = 10
            filter = TdApi.SearchMessagesFilterPhoto()
        })
        for (m in found.messages) {              // newest first
            val sender = m.senderId
            if (sender !is TdApi.MessageSenderUser || sender.userId != userId) continue   // only the person's own photos count
            val content = m.content as? TdApi.MessagePhoto ?: continue
            if (!content.caption.text.contains(TAG, ignoreCase = true)) continue
            val size = content.photo.sizes.maxByOrNull { it.width.toLong() * it.height } ?: continue
            val file = Td.call(TdApi.DownloadFile().apply {
                fileId = size.photo.id
                priority = 16
                offset = 0
                limit = 0
                synchronous = true
            })
            return if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) file.local.path else null
        }
        return null
    }

    /** Sends the photo to the banner group as the current user. The new banner appears when the upload is done. */
    suspend fun setMine(localPath: String, myId: Long) {
        val chatId = group()
        try {
            Td.call(TdApi.JoinChat().apply { this.chatId = chatId })   // posting needs membership; fails harmlessly if already a member
        } catch (_: Exception) {
        }
        // The send below reports the real reason if joining did not work.
        val input = TdApi.InputPhoto().apply {
            photo = TdApi.InputFileLocal(localPath)
            addedStickerFileIds = intArrayOf()
        }
        val content = TdApi.InputMessagePhoto().apply {
            this.photo = input
            caption = TdApi.FormattedText(TAG, arrayOf())
            showCaptionAboveMedia = false
            hasSpoiler = false
        }
        // SendMessage answers at once with a local message; a refusal (no right to post photos, slow mode,
        // not a member…) arrives later as an update. Wait for the real result so errors are not lost.
        val events = Channel<TdApi.Object>(Channel.UNLIMITED)
        coroutineScope {
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                Td.updates.collect {
                    if (it is TdApi.UpdateMessageSendSucceeded || it is TdApi.UpdateMessageSendFailed) events.trySend(it)
                }
            }
            try {
                val sent = Td.call(TdApi.SendMessage().apply {
                    this.chatId = chatId
                    inputMessageContent = content
                })
                withTimeout(120_000) {
                    for (e in events) {
                        if (e is TdApi.UpdateMessageSendFailed && e.oldMessageId == sent.id) {
                            throw IllegalStateException("Telegram refused the photo: ${e.error.message}")
                        }
                        if (e is TdApi.UpdateMessageSendSucceeded && e.oldMessageId == sent.id) break
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException("Telegram did not confirm the upload in 2 minutes (no network?)")
            } finally {
                watcher.cancel()
            }
        }
        cache.remove(myId)
    }

    /** Copies the picked image into the app cache, scaled to at most 1600 px, as JPEG. */
    suspend fun prepare(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1600 * 2 || bounds.outHeight / sample > 1600 * 2) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("Can not read the image")
        val scale = minOf(1f, 1600f / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val out = File(context.cacheDir, "banner-${System.currentTimeMillis()}.jpg")
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        out.absolutePath
    }
}
