package app.umbra.client

import kotlinx.coroutines.delay
import org.drinkless.tdlib.TdApi

class ChatItem(val id: Long, val title: String, val last: String, val unread: Int)
class MsgItem(val id: Long, val text: String, val outgoing: Boolean, val deleted: Boolean)

/** Telegram operations used by the UI. All Umbra features are applied here. */
class Repo(private val prefs: Prefs) {
    /** Message ids that were deleted remotely while "keep deleted" is on. */
    val deletedIds = HashSet<Long>()

    private fun textOf(c: TdApi.MessageContent?): String = when (c) {
        is TdApi.MessageText -> c.text.text
        is TdApi.MessagePhoto -> "📷 " + c.caption.text
        is TdApi.MessageVideo -> "🎞 " + c.caption.text
        is TdApi.MessageVoiceNote -> "🎤 Voice message"
        is TdApi.MessageSticker -> "${c.sticker.emoji} Sticker"
        is TdApi.MessageDocument -> "📎 " + c.document.fileName
        null -> ""
        else -> "[unsupported message]"
    }

    suspend fun chats(): List<ChatItem> {
        val ids = Td.call(TdApi.GetChats().apply { chatList = TdApi.ChatListMain(); limit = 100 }).chatIds
        return ids.map { id ->
            val c = Td.call(TdApi.GetChat().apply { chatId = id })
            ChatItem(c.id, c.title, textOf(c.lastMessage?.content), c.unreadCount)
        }
    }

    suspend fun history(chatId: Long): List<MsgItem> {
        val msgs = Td.call(TdApi.GetChatHistory().apply {
            this.chatId = chatId; fromMessageId = 0; offset = 0; limit = 50; onlyLocal = false
        }).messages
        // Ghost mode: we never call ViewMessages, so the sender does not see "read".
        return msgs.reversed().map { MsgItem(it.id, textOf(it.content), it.isOutgoing, it.id in deletedIds) }
    }

    /** Marks the chat read - skipped completely in ghost mode. */
    suspend fun markRead(chatId: Long, messageIds: LongArray) {
        if (prefs.ghostMode || messageIds.isEmpty()) return
        Td.call(TdApi.ViewMessages().apply {
            this.chatId = chatId; this.messageIds = messageIds; forceRead = true
        })
    }

    /** Applies text features and the optional delay. [cancelled] is polled during the delay. */
    suspend fun send(chatId: Long, raw: String, cancelled: () -> Boolean) {
        val text = TextTools.apply(raw, prefs)
        if (text.isBlank()) return
        var left = prefs.sendDelaySeconds * 10
        while (left > 0) { delay(100); if (cancelled()) return; left-- }
        val content = TdApi.InputMessageText().apply {
            this.text = TdApi.FormattedText(text, arrayOf())
        }
        Td.call(TdApi.SendMessage().apply { this.chatId = chatId; inputMessageContent = content })
    }

    fun onDeleted(u: TdApi.UpdateDeleteMessages) {
        if (prefs.keepDeleted && u.isPermanent) u.messageIds.forEach { deletedIds.add(it) }
    }
}
