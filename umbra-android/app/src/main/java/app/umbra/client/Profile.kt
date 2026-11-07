package app.umbra.client

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

/** Profile with the Umbra banner. [userId] == 0 means "my own profile". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(userId: Long, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<Long?>(null) }
    var banner by remember { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    suspend fun decode(path: String): ImageBitmap? =
        withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }

    val target: Long? = if (userId == 0L) me else userId

    LaunchedEffect(userId) {
        me = runCatching { Td.call(TdApi.GetMe()).id }.getOrNull()
    }
    LaunchedEffect(target) {
        val id = target ?: return@LaunchedEffect
        loading = true
        val path = runCatching { Banners.load(id) }.getOrNull()
        banner = path?.let { decode(it) }
        loading = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val myId = me
        if (uri != null && myId != null) {
            scope.launch {
                busy = true
                note = null
                try {
                    val local = Banners.prepare(context, uri)
                    banner = decode(local)            // shown at once; others see it when the upload is finished
                    Banners.setMine(local, myId)
                    note = "Banner sent. Other Umbra users will see it in a minute."
                } catch (e: Exception) {
                    note = "Could not set the banner: ${e.message}"
                } finally {
                    busy = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (userId == 0L) "My profile" else title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                val image = banner
                if (image != null) {
                    Image(image, contentDescription = "Banner", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Box(contentAlignment = Alignment.Center) {
                            if (loading) CircularProgressIndicator() else Text("No banner")
                        }
                    }
                }
            }
            Text(
                if (userId == 0L) "My profile" else title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(16.dp)
            )
            if (me != null && me == target) {
                Button(
                    onClick = { picker.launch("image/*") },
                    enabled = !busy,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) { Text(if (busy) "Sending…" else "Change my banner") }
                Text(
                    "The banner is a photo that you post in the public group @${Banners.GROUP}. Only Umbra shows it.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
            note?.let { Text(it, Modifier.padding(horizontal = 16.dp)) }
        }
    }
}
