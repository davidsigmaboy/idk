package app.umbra.client

import kotlinx.coroutines.delay
import org.drinkless.tdlib.TdApi

data class ChatItem(val id: Long, val title: String, val last: String, val unread: Int)
data class MsgItem(val id: Long, val text: String, val outgoing: Boolean, val deleted: Boolean)

/** Telegram operations used by the UI. All Umbra features are applied here. */
class Repo(private val prefs: Prefs) {
    /** Message ids that were deleted remotely while "keep deleted" is on. */
    val deletedIds = HashSet<Long>()

    /** Local cache of messages we have already seen (for keep-deleted). */
    private val msgCache = HashMap<Long, MsgItem>()

    private fun textOf(c: TdApi.MessageContent?): String = when (c) {
        is TdApi.MessageText -> c.text.text
        is TdApi.MessagePhoto -> "📷 " + (c.caption?.text ?: "")
        is TdApi.MessageVideo -> "🎞 " + (c.caption?.text ?: "")
        is TdApi.MessageAnimation -> "GIF " + (c.caption?.text ?: "")
        is TdApi.MessageVoiceNote -> "🎤 Voice message"
        is TdApi.MessageVideoNote -> "📹 Video message"
        is TdApi.MessageSticker -> "${c.sticker.emoji} Sticker"
        is TdApi.MessageDocument -> "📎 " + (c.document.fileName ?: "file")
        is TdApi.MessageAudio -> "🎵 " + (c.audio.title ?: c.audio.fileName ?: "Audio")
        is TdApi.MessageLocation -> "📍 Location"
        is TdApi.MessageVenue -> "📍 " + (c.venue.title ?: "Venue")
        is TdApi.MessageContact -> "👤 Contact"
        is TdApi.MessagePoll -> "📊 " + (c.poll.question?.text ?: "Poll")
        is TdApi.MessageCall -> "📞 Call"
        null -> ""
        else -> "[unsupported]"
    }

    suspend fun chats(): List<ChatItem> {
        val ids = Td.call(TdApi.GetChats().apply {
            chatList = TdApi.ChatListMain()
            limit = 200
        }).chatIds
        return ids.map { id ->
            val c = Td.call(TdApi.GetChat().apply { chatId = id })
            ChatItem(c.id, c.title, textOf(c.lastMessage?.content), c.unreadCount)
        }
    }

    /**
     * Returns chat history (newest first in the list we build).
     * @param fromMessageId 0 = latest, otherwise load older than this id
     */
    suspend fun history(chatId: Long, fromMessageId: Long = 0, limit: Int = 40): List<MsgItem> {
        val result = Td.call(TdApi.GetChatHistory().apply {
            this.chatId = chatId
            this.fromMessageId = fromMessageId
            this.offset = 0
            this.limit = limit
            this.onlyLocal = false
        })
        val items = result.messages.map { m ->
            val item = MsgItem(m.id, textOf(m.content), m.isOutgoing, m.id in deletedIds)
            msgCache[m.id] = item
            item
        }
        // Ghost mode: we never call ViewMessages here.
        return items.reversed()
    }

    /** Marks the chat read — skipped completely in ghost mode. */
    suspend fun markRead(chatId: Long, messageIds: LongArray) {
        if (prefs.ghostMode || messageIds.isEmpty()) return
        Td.call(TdApi.ViewMessages().apply {
            this.chatId = chatId
            this.messageIds = messageIds
            forceRead = true
        })
    }

    /** Applies text features and the optional delay. [cancelled] is polled during the delay. */
    suspend fun send(chatId: Long, raw: String, cancelled: () -> Boolean) {
        var text = TextTools.apply(raw, prefs)
        if (prefs.pluginsEnabled) {
            text = Plugins.transformOutgoing(Td.context(), text) ?: return   // null: a plugin cancelled the message
        }
        if (text.isBlank()) return
        var left = prefs.sendDelaySeconds * 10
        while (left > 0) {
            delay(100)
            if (cancelled()) return
            left--
        }
        val content = TdApi.InputMessageText().apply {
            this.text = TdApi.FormattedText(text, emptyArray())
            clearDraft = true
        }
        Td.call(TdApi.SendMessage().apply {
            this.chatId = chatId
            inputMessageContent = content
        })
    }

    fun onDeleted(u: TdApi.UpdateDeleteMessages) {
        if (!prefs.keepDeleted || !u.isPermanent) return
        u.messageIds.forEach { id ->
            deletedIds.add(id)
            msgCache[id]?.let { cached ->
                msgCache[id] = cached.copy(deleted = true)
            }
        }
    }

    /** Returns cached message if we still have it (for keep-deleted display). */
    fun cached(id: Long): MsgItem? = msgCache[id]
}
