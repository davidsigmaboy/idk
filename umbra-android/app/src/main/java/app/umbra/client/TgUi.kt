package app.umbra.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Telegram-like look: blue header, round avatars, wallpaper and bubbles like the official app. */
object Tg {
    val blue = Color(0xFF3390EC)
    val headerLight = Color(0xFF527DA3)
    val headerDark = Color(0xFF242F3D)

    fun light() = lightColorScheme(
        primary = blue,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEFFDDE),      // outgoing bubble
        onPrimaryContainer = Color(0xFF111111),
        secondary = Color(0xFF4FAE4E),
        background = Color.White,
        surface = Color.White,
        onSurface = Color(0xFF111111),
        surfaceVariant = Color(0xFFFFFFFF),        // incoming bubble
        onSurfaceVariant = Color(0xFF707579),
        surfaceContainer = Color(0xFFF1F3F5),
        outline = Color(0xFFDADCE0)
    )

    fun dark() = darkColorScheme(
        primary = Color(0xFF6AB2F2),
        onPrimary = Color(0xFF0E1621),
        primaryContainer = Color(0xFF2B5278),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFF6AB2F2),
        background = Color(0xFF17212B),
        surface = Color(0xFF17212B),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF182533),
        onSurfaceVariant = Color(0xFF7F91A4),
        surfaceContainer = Color(0xFF242F3D),
        outline = Color(0xFF0E1621)
    )

    fun wallpaper(dark: Boolean): Brush = if (dark) {
        Brush.verticalGradient(listOf(Color(0xFF0E1621), Color(0xFF1B2735)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFDCE8C8), Color(0xFFB5D2A6)))
    }

    private val avatarColors = listOf(
        Color(0xFFE17076), Color(0xFFFAA774), Color(0xFFA695E7), Color(0xFF7BC862),
        Color(0xFF6EC9CB), Color(0xFF65AADD), Color(0xFFEE7AAE)
    )

    fun colorFor(title: String): Color = avatarColors[(title.hashCode() and 0x7fffffff) % avatarColors.size]

    fun initials(title: String): String {
        val parts = title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(1).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    /** "14:05" today, weekday this week, "12.10" before. */
    fun time(unix: Int): String {
        if (unix <= 0) return ""
        val d = Date(unix * 1000L)
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { time = d }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
        val days = (now.timeInMillis - d.time) / 86_400_000L
        return if (days < 7) SimpleDateFormat("EEE", Locale.getDefault()).format(d)
        else SimpleDateFormat("dd.MM", Locale.getDefault()).format(d)
    }

    private val bitmaps = HashMap<Int, Bitmap>()

    suspend fun loadAvatar(fileId: Int): Bitmap? {
        if (fileId == 0) return null
        bitmaps[fileId]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = Td.call(TdApi.DownloadFile().apply {
                    this.fileId = fileId
                    priority = 16
                    offset = 0
                    limit = 0
                    synchronous = true
                })
                val path = file.local?.path
                if (!path.isNullOrEmpty()) BitmapFactory.decodeFile(path)?.also { bitmaps[fileId] = it } else null
            }.getOrNull()
        }
    }
}

@Composable
fun Avatar(title: String, fileId: Int, size: androidx.compose.ui.unit.Dp) {
    var bitmap by remember(fileId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(fileId) { bitmap = Tg.loadAvatar(fileId) }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape)
        )
    } else {
        Box(
            Modifier.size(size).clip(CircleShape).background(Tg.colorFor(title)),
            contentAlignment = Alignment.Center
        ) {
            Text(Tg.initials(title), color = Color.White, fontWeight = FontWeight.Medium, fontSize = (size.value * 0.38f).sp)
        }
    }
}

@Composable
fun ChatRow(chat: ChatItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(chat.title, chat.photoFileId, 54.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium, fontSize = 16.sp
                )
                Spacer(Modifier.width(6.dp))
                Text(Tg.time(chat.date), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.last, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (chat.unread > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp).clip(CircleShape)
                            .background(if (chat.muted) Color(0xFFB0B7BE) else MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (chat.unread > 99) "99+" else "${chat.unread}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
fun Bubble(m: MsgItem) {
    val out = m.outgoing
    val shape = RoundedCornerShape(
        topStart = 14.dp, topEnd = 14.dp,
        bottomStart = if (out) 14.dp else 4.dp,
        bottomEnd = if (out) 4.dp else 14.dp
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (out) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (out) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = shape,
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp)) {
                Text(
                    (if (m.deleted) "🗑 " else "") + m.text,
                    fontSize = 16.sp,
                    color = if (m.deleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    Tg.time(m.date).let { if (out) "$it ✓" else it },
                    Modifier.align(Alignment.End),
                    fontSize = 11.sp,
                    color = if (out) Color(0xFF4FAE4E) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
