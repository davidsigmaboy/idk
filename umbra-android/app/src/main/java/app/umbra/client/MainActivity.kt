package app.umbra.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Td.start(this)
        val prefs = Prefs(this)
        val repo = Repo(prefs)
        setContent {
            MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(prefs, repo) }
            }
        }
    }
}

private sealed interface Screen {
    data object Chats : Screen
    data object Settings : Screen
    class Chat(val id: Long, val title: String) : Screen
}

@Composable
private fun App(prefs: Prefs, repo: Repo) {
    val auth by Td.authState.collectAsState()
    when (auth) {
        null, is TdApi.AuthorizationStateWaitTdlibParameters -> Center { CircularProgressIndicator() }
        is TdApi.AuthorizationStateWaitPhoneNumber -> LoginStep("Phone number (+…)", KeyboardType.Phone, false) {
            Td.call(TdApi.SetAuthenticationPhoneNumber().apply { phoneNumber = it })
        }
        is TdApi.AuthorizationStateWaitCode -> LoginStep("Code from Telegram", KeyboardType.Number, false) {
            Td.call(TdApi.CheckAuthenticationCode().apply { code = it })
        }
        is TdApi.AuthorizationStateWaitPassword -> LoginStep("Two-step password", KeyboardType.Password, true) {
            Td.call(TdApi.CheckAuthenticationPassword().apply { password = it })
        }
        is TdApi.AuthorizationStateReady -> Main(prefs, repo)
        else -> Center { Text("Waiting… (${auth!!::class.simpleName})") }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

@Composable
private fun LoginStep(label: String, type: KeyboardType, secret: Boolean, submit: suspend (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Umbra", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            scope.launch { runCatching { submit(value.trim()) }.onFailure { error = it.message } }
        }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}

@Composable
private fun Main(prefs: Prefs, repo: Repo) {
    var screen by remember { mutableStateOf<Screen>(Screen.Chats) }
    when (val s = screen) {
        Screen.Chats -> ChatList(repo, onOpen = { screen = Screen.Chat(it.id, it.title) }, onSettings = { screen = Screen.Settings })
        Screen.Settings -> { BackHandler { screen = Screen.Chats }; SettingsScreen(prefs) { screen = Screen.Chats } }
        is Screen.Chat -> { BackHandler { screen = Screen.Chats }; ChatScreen(repo, prefs, s.id, s.title) { screen = Screen.Chats } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatList(repo: Repo, onOpen: (ChatItem) -> Unit, onSettings: () -> Unit) {
    var chats by remember { mutableStateOf<List<ChatItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        suspend fun reload() { runCatching { repo.chats() }.onSuccess { chats = it; error = null }.onFailure { error = it.message } }
        reload()
        Td.updates.collect { u ->
            if (u is TdApi.UpdateNewMessage || u is TdApi.UpdateChatLastMessage || u is TdApi.UpdateChatReadInbox) reload()
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Umbra") }, actions = {
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            error?.let { item { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
            items(chats, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.title, maxLines = 1) },
                    supportingContent = { Text(c.last, maxLines = 1) },
                    trailingContent = { if (c.unread > 0) Badge { Text("${c.unread}") } },
                    modifier = Modifier.clickable { onOpen(c) }
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(repo: Repo, prefs: Prefs, chatId: Long, title: String, onBack: () -> Unit) {
    var msgs by remember { mutableStateOf<List<MsgItem>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var cancelFlag by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(chatId) {
        // Opening the chat is required by TDLib to receive its updates; it does not mark anything read.
        runCatching { Td.call(TdApi.OpenChat().apply { this.chatId = chatId }) }
        suspend fun reload() {
            runCatching { repo.history(chatId) }.onSuccess { msgs = it; repo.markRead(chatId, it.filter { m -> !m.outgoing }.map { m -> m.id }.toLongArray()) }
        }
        reload()
        Td.updates.collect { u ->
            when (u) {
                is TdApi.UpdateNewMessage -> if (u.message.chatId == chatId) reload()
                is TdApi.UpdateDeleteMessages -> if (u.chatId == chatId) { repo.onDeleted(u); reload() }
                else -> {}
            }
        }
    }
    DisposableEffect(chatId) { onDispose { scope.launch { runCatching { Td.call(TdApi.CloseChat().apply { this.chatId = chatId }) } } } }

    Scaffold(topBar = {
        TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }, bottomBar = {
        Column {
            if (pending) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sending in ${prefs.sendDelaySeconds}s…", Modifier.weight(1f))
                    TextButton(onClick = { cancelFlag = true }) { Text("Cancel") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp).imePadding(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message") })
                IconButton(onClick = {
                    val text = input; input = ""; cancelFlag = false; pending = prefs.sendDelaySeconds > 0
                    scope.launch {
                        runCatching { repo.send(chatId, text) { cancelFlag } }
                        pending = false
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp), reverseLayout = true) {
            items(msgs.reversed(), key = { it.id }) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (m.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text((if (m.deleted) "🗑 " else "") + m.text, Modifier.padding(10.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(prefs: Prefs, onBack: () -> Unit) {
    var ghost by remember { mutableStateOf(prefs.ghostMode) }
    var keep by remember { mutableStateOf(prefs.keepDeleted) }
    var tidy by remember { mutableStateOf(prefs.tidySpaces) }
    var smart by remember { mutableStateOf(prefs.smartPunctuation) }
    var caps by remember { mutableStateOf(prefs.antiCaps) }
    var delay by remember { mutableStateOf(prefs.sendDelaySeconds.toFloat()) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Umbra settings") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            Toggle("Ghost mode", "Do not mark messages as read", ghost) { ghost = it; prefs.ghostMode = it }
            Toggle("Keep deleted messages", "Deleted messages stay in the chat with a mark", keep) { keep = it; prefs.keepDeleted = it }
            Toggle("Tidy spaces", "Double spaces and extra blank lines are removed", tidy) { tidy = it; prefs.tidySpaces = it }
            Toggle("Smart punctuation", "... → …, a - b → a — b", smart) { smart = it; prefs.smartPunctuation = it }
            Toggle("Anti-caps", "HELLO ALL → Hello all", caps) { caps = it; prefs.antiCaps = it }
            Column(Modifier.padding(16.dp)) {
                Text("Delayed sending: ${delay.toInt()} s")
                Slider(delay, { delay = it }, valueRange = 0f..10f, steps = 9, onValueChangeFinished = { prefs.sendDelaySeconds = delay.toInt() })
            }
        }
    }
}

@Composable
private fun Toggle(title: String, info: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) }, supportingContent = { Text(info) },
        trailingContent = { Switch(value, onChange) }
    )
}
