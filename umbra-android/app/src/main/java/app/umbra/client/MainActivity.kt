package app.umbra.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Td.start(this)
        val prefs = Prefs(this)
        val repo = Repo(prefs)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    App(prefs, repo, activity = this@MainActivity)
                }
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
private fun App(prefs: Prefs, repo: Repo, activity: ComponentActivity) {
    val auth by Td.authState.collectAsState()
    val fatal by Td.fatalError.collectAsState()

    // Always surface setup errors instead of an infinite spinner.
    if (fatal != null) {
        Center {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Umbra", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(16.dp))
                Text(
                    fatal!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }

    when (val s = auth) {
        null, is TdApi.AuthorizationStateWaitTdlibParameters ->
            Center {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Starting Telegram…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

        is TdApi.AuthorizationStateWaitPhoneNumber ->
            LoginStep("Phone number (+…)", KeyboardType.Phone, secret = false) {
                Td.call(TdApi.SetAuthenticationPhoneNumber().apply { phoneNumber = it })
            }

        is TdApi.AuthorizationStateWaitCode ->
            LoginStep("Code from Telegram", KeyboardType.Number, secret = false) {
                Td.call(TdApi.CheckAuthenticationCode().apply { code = it })
            }

        is TdApi.AuthorizationStateWaitPassword ->
            LoginStep("Two-step password", KeyboardType.Password, secret = true) {
                Td.call(TdApi.CheckAuthenticationPassword().apply { password = it })
            }

        is TdApi.AuthorizationStateWaitRegistration ->
            RegistrationScreen()

        is TdApi.AuthorizationStateWaitEmailAddress ->
            LoginStep("Email address", KeyboardType.Email, secret = false) {
                Td.call(TdApi.SetAuthenticationEmailAddress().apply { emailAddress = it })
            }

        is TdApi.AuthorizationStateWaitEmailCode ->
            LoginStep("Email code", KeyboardType.Number, secret = false) { codeStr ->
                val authCode = TdApi.EmailAddressAuthenticationCode()
                authCode.code = codeStr
                Td.call(TdApi.CheckAuthenticationEmailCode().apply { code = authCode })
            }

        is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
            Center {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("Confirm on another device", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text(s.link, style = MaterialTheme.typography.bodyMedium)
                }
            }

        is TdApi.AuthorizationStateReady ->
            Main(prefs, repo)

        is TdApi.AuthorizationStateLoggingOut,
        is TdApi.AuthorizationStateClosing ->
            Center {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Logging out…")
                }
            }

        is TdApi.AuthorizationStateClosed ->
            Center {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("Logged out", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        Td.start(activity)
                    }) {
                        Text("Start again")
                    }
                }
            }

        else ->
            Center {
                Text("Waiting… (${s!!::class.simpleName})", modifier = Modifier.padding(24.dp))
            }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

@Composable
private fun LoginStep(
    label: String,
    type: KeyboardType,
    secret: Boolean,
    submit: suspend (String) -> Unit
) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Umbra", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Unofficial Telegram client",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it; error = null },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type),
            visualTransformation = if (secret) PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true
                scope.launch {
                    runCatching { submit(value.trim()) }
                        .onFailure { error = it.message }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && value.isNotBlank()
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Continue")
        }
    }
}

@Composable
private fun RegistrationScreen() {
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Create account", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            first, { first = it }, label = { Text("First name") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            last, { last = it }, label = { Text("Last name (optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true
                scope.launch {
                    runCatching {
                        Td.call(TdApi.RegisterUser().apply {
                            firstName = first.trim()
                            lastName = last.trim()
                        })
                    }.onFailure { error = it.message }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && first.isNotBlank()
        ) { Text("Register") }
    }
}

@Composable
private fun Main(prefs: Prefs, repo: Repo) {
    var screen by remember { mutableStateOf<Screen>(Screen.Chats) }
    when (val s = screen) {
        Screen.Chats -> ChatList(
            repo,
            onOpen = { screen = Screen.Chat(it.id, it.title) },
            onSettings = { screen = Screen.Settings }
        )
        Screen.Settings -> {
            BackHandler { screen = Screen.Chats }
            SettingsScreen(prefs) { screen = Screen.Chats }
        }
        is Screen.Chat -> {
            BackHandler { screen = Screen.Chats }
            ChatScreen(repo, prefs, s.id, s.title) { screen = Screen.Chats }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatList(repo: Repo, onOpen: (ChatItem) -> Unit, onSettings: () -> Unit) {
    var chats by remember { mutableStateOf<List<ChatItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val connection by Td.connectionState.collectAsState()

    LaunchedEffect(Unit) {
        suspend fun reload() {
            runCatching { repo.chats() }
                .onSuccess { chats = it; error = null }
                .onFailure { error = it.message }
            loading = false
        }
        reload()
        Td.updates.collect { u ->
            when (u) {
                is TdApi.UpdateNewMessage,
                is TdApi.UpdateChatLastMessage,
                is TdApi.UpdateChatReadInbox,
                is TdApi.UpdateChatTitle,
                is TdApi.UpdateUnreadMessageCount -> reload()
                else -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Umbra")
                        val status = when (connection) {
                            is TdApi.ConnectionStateReady -> null
                            is TdApi.ConnectionStateConnecting,
                            is TdApi.ConnectionStateConnectingToProxy -> "Connecting…"
                            is TdApi.ConnectionStateUpdating -> "Updating…"
                            is TdApi.ConnectionStateWaitingForNetwork -> "Waiting for network"
                            else -> null
                        }
                        if (status != null) {
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { pad ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && chats.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.Center
            ) {
                Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            chats.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No chats yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.padding(pad)) {
                error?.let {
                    item { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                }
                items(chats, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = {
                            Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(c.last, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            if (c.unread > 0) Badge { Text("${c.unread}") }
                        },
                        modifier = Modifier.clickable { onOpen(c) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    repo: Repo,
    prefs: Prefs,
    chatId: Long,
    title: String,
    onBack: () -> Unit
) {
    var msgs by remember { mutableStateOf<List<MsgItem>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var cancelFlag by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var canLoadMore by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(chatId) {
        runCatching { Td.call(TdApi.OpenChat().apply { this.chatId = chatId }) }
        suspend fun reload() {
            runCatching { repo.history(chatId) }
                .onSuccess { batch ->
                    msgs = batch
                    canLoadMore = batch.size >= 30
                    val unread = batch.filter { !it.outgoing }.map { it.id }.toLongArray()
                    if (unread.isNotEmpty()) repo.markRead(chatId, unread)
                }
        }
        reload()
        Td.updates.collect { u ->
            when (u) {
                is TdApi.UpdateNewMessage -> if (u.message.chatId == chatId) reload()
                is TdApi.UpdateDeleteMessages -> if (u.chatId == chatId) {
                    repo.onDeleted(u)
                    if (prefs.keepDeleted && u.isPermanent) {
                        msgs = msgs.map { m ->
                            if (m.id in u.messageIds) m.copy(deleted = true) else m
                        }
                    } else if (u.isPermanent) {
                        msgs = msgs.filter { it.id !in u.messageIds }
                    }
                }
                else -> {}
            }
        }
    }
    DisposableEffect(chatId) {
        onDispose {
            scope.launch {
                runCatching { Td.call(TdApi.CloseChat().apply { this.chatId = chatId }) }
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= (info.totalItemsCount - 3).coerceAtLeast(0)
        }.collect { nearEnd ->
            if (nearEnd && canLoadMore && !loadingMore && msgs.isNotEmpty()) {
                loadingMore = true
                val oldest = msgs.firstOrNull()?.id ?: 0L
                runCatching { repo.history(chatId, fromMessageId = oldest) }
                    .onSuccess { batch ->
                        if (batch.isEmpty()) canLoadMore = false
                        else {
                            val existing = msgs.map { it.id }.toSet()
                            val older = batch.filter { it.id !in existing }
                            if (older.isEmpty()) canLoadMore = false
                            else msgs = older + msgs
                        }
                    }
                loadingMore = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Column {
                if (pending) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sending in ${prefs.sendDelaySeconds}s…", Modifier.weight(1f))
                        TextButton(onClick = { cancelFlag = true }) { Text("Cancel") }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(8.dp).imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        maxLines = 4
                    )
                    IconButton(
                        onClick = {
                            val text = input
                            if (text.isBlank()) return@IconButton
                            input = ""
                            cancelFlag = false
                            pending = prefs.sendDelaySeconds > 0
                            scope.launch {
                                runCatching { repo.send(chatId, text) { cancelFlag } }
                                pending = false
                            }
                        },
                        enabled = input.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp),
            reverseLayout = true
        ) {
            if (loadingMore) {
                item {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
            items(msgs.reversed(), key = { it.id }) { m ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (m.outgoing) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            (if (m.deleted) "🗑 " else "") + m.text,
                            Modifier.padding(10.dp),
                            style = if (m.deleted) MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ) else MaterialTheme.typography.bodyMedium
                        )
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
    var loggingOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Umbra settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Toggle("Ghost mode", "Do not mark messages as read", ghost) {
                ghost = it; prefs.ghostMode = it
            }
            Toggle("Keep deleted messages", "Deleted messages stay in the chat with a mark", keep) {
                keep = it; prefs.keepDeleted = it
            }
            Toggle("Tidy spaces", "Double spaces and extra blank lines are removed", tidy) {
                tidy = it; prefs.tidySpaces = it
            }
            Toggle("Smart punctuation", "... → …, a - b → a — b", smart) {
                smart = it; prefs.smartPunctuation = it
            }
            Toggle("Anti-caps", "HELLO ALL → Hello all", caps) {
                caps = it; prefs.antiCaps = it
            }
            Column(Modifier.padding(16.dp)) {
                Text("Delayed sending: ${delay.toInt()} s")
                Slider(
                    value = delay,
                    onValueChange = { delay = it },
                    valueRange = 0f..10f,
                    steps = 9,
                    onValueChangeFinished = { prefs.sendDelaySeconds = delay.toInt() }
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text("Version") },
                supportingContent = { Text(BuildConfig.VERSION_NAME) }
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    loggingOut = true
                    scope.launch {
                        runCatching { Td.logout() }
                        loggingOut = false
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                enabled = !loggingOut,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                if (loggingOut) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                } else Text("Log out")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Not affiliated with Telegram. Uses official TDLib.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun Toggle(title: String, info: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(info) },
        trailingContent = { Switch(checked = value, onCheckedChange = onChange) }
    )
}
