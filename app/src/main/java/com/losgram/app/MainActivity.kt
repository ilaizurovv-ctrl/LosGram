package com.losgram.app

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val API_BASE_URL = "https://losgram-api.onrender.com"
private const val TELEGRAM_BOT_URL = "https://t.me/LosGram_Bot"
private const val PREFS = "losgram_session"
private const val TOKEN_KEY = "access_token"

private val Navy = Color(0xFF171717)
private val Panel = Color(0xFF222222)
private val Blue = Color(0xFF6A994E)
private val Muted = Color(0xFF858585)
private val White = Color(0xFFF2F2F2)

data class LoginSession(val token: String, val userId: Int, val displayName: String, val virtualNumber: String)
data class ChatItem(val id: Int, val name: String, val message: String, val isGlobal: Boolean = false, val isSaved: Boolean = false)
data class MessageItem(val id: Int, val senderId: Int, val body: String, val mine: Boolean, val replyToId: Int? = null, val replyToBody: String? = null, val edited: Boolean = false, val deleted: Boolean = false)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(23, 23, 23)
        window.navigationBarColor = android.graphics.Color.rgb(23, 23, 23)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Blue, background = Navy, surface = Panel,
                onBackground = White, onSurface = White
            )) { LosGramApp() }
        }
    }
}

private fun saveToken(context: Context, token: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(TOKEN_KEY, token).apply()
}

private fun readToken(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN_KEY, null)

private fun clearToken(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(TOKEN_KEY).apply()
}

private suspend fun api(path: String, token: String? = null, method: String = "GET",
                        body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
    val connection = (URL(API_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 20000
        readTimeout = 20000
        setRequestProperty("Accept", "application/json")
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
    }
    try {
        if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = try {
            if (raw.isBlank()) JSONObject()
            else {
                val parsed = org.json.JSONTokener(raw).nextValue()
                parsed as? JSONObject ?: JSONObject().put("_response", parsed.toString())
            }
        } catch (_: Exception) {
            JSONObject().put("_response", raw.take(500))
        }
        if (status !in 200..299) {
            val detail = json.optString("detail").ifBlank { json.optString("_response") }
            throw IllegalStateException(detail.ifBlank { "Ошибка сервера ($status)" })
        }
        if (json.has("_response")) {
            throw IllegalStateException("Сервер вернул неожиданный ответ: " + json.optString("_response").take(250))
        }
        json
    } finally {
        connection.disconnect()
    }
}

private suspend fun verifyLosGramLogin(virtualNumber: String, code: String): LoginSession {
    val response = api("/auth/telegram/verify", method = "POST",
        body = JSONObject().put("virtual_number", virtualNumber).put("code", code))
    val user = response.optJSONObject("user") ?: JSONObject()
    val token = response.optString("access_token")
    if (token.isBlank()) throw IllegalStateException("Сервер не вернул токен входа.")
    return LoginSession(token, user.optInt("id"), user.optString("display_name", "Пользователь"), user.optString("virtual_number"))
}

@Composable
fun LosGramApp() {
    val context = LocalContext.current
    var session by remember { mutableStateOf<LoginSession?>(null) }
    var checkingSession by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val saved = readToken(context)
        if (!saved.isNullOrBlank()) {
            try {
                val me = api("/me", saved)
                session = LoginSession(saved, me.optInt("id"), me.optString("display_name", "Пользователь"), me.optString("virtual_number"))
            } catch (_: Exception) {
                clearToken(context)
            }
        }
        checkingSession = false
    }

    when {
        checkingSession -> Box(Modifier.fillMaxSize().background(Navy), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Blue)
        }
        session == null -> TelegramLoginScreen(onLogin = { id, code ->
            val result = verifyLosGramLogin(id, code)
            saveToken(context, result.token)
            session = result
        })
        else -> MessengerHome(
            session = session!!,
            onLogout = {
                clearToken(context)
                session = null
            }
        )
    }
}

@Composable
fun TelegramLoginScreen(onLogin: suspend (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var virtualNumber by remember { mutableStateOf("+44") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = Navy) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("LosGram", color = Blue, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Вход в мессенджер", color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text("Получите виртуальный номер и одноразовый код в Telegram-боте",
                color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(22.dp))
            Button(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_BOT_URL)))
            }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue),
                shape = RoundedCornerShape(12.dp)) { Text("Открыть @LosGram_Bot", color = Color.White) }
            Spacer(Modifier.height(22.dp))
            OutlinedTextField(value = virtualNumber,
                onValueChange = { virtualNumber = it.filter { ch -> ch.isDigit() || ch == '+' }.take(13); error = "" },
                modifier = Modifier.fillMaxWidth(), label = { Text("Виртуальный номер") },
                placeholder = { Text("+447123456789") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                shape = RoundedCornerShape(12.dp), colors = loginFieldColors())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6); error = "" },
                modifier = Modifier.fillMaxWidth(), label = { Text("Код из Telegram") },
                placeholder = { Text("6 цифр") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                shape = RoundedCornerShape(12.dp), colors = loginFieldColors())
            if (error.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = Color(0xFFFF7777), fontSize = 13.sp)
            }
            Spacer(Modifier.height(18.dp))
            Button(onClick = {
                if (!virtualNumber.matches(Regex("^\\+447[0-9]{9}$")) || code.length != 6) {
                    error = "Введите номер профиля +44 и шестизначный код из бота."
                } else {
                    loading = true
                    error = ""
                    scope.launch {
                        try { onLogin(virtualNumber, code) }
                        catch (e: Exception) { error = e.message ?: "Ошибка подключения к серверу." }
                        finally { loading = false }
                    }
                }
            }, enabled = !loading, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                shape = RoundedCornerShape(12.dp)) {
                Text(if (loading) "Проверяем..." else "Войти", color = Color.White)
            }
            Spacer(Modifier.height(16.dp))
            Text("Без номера телефона. Код действует 5 минут и используется один раз.",
                color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun loginFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Blue, unfocusedBorderColor = Panel,
    focusedTextColor = White, unfocusedTextColor = White,
    focusedLabelColor = Blue, unfocusedLabelColor = Muted,
    cursorColor = Blue, focusedContainerColor = Panel, unfocusedContainerColor = Panel
)

@Composable
fun MessengerHome(session: LoginSession, onLogout: () -> Unit) {
    var chats by remember { mutableStateOf<List<ChatItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var selectedChat by remember { mutableStateOf<ChatItem?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session.token, refreshKey) {
        loading = true
        error = ""
        try {
            val response = api("/conversations", session.token)
            val array = response.optJSONArray("items") ?: JSONArray()
            chats = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val members = item.optJSONArray("members") ?: JSONArray()
                val other = if (members.length() > 0) members.optJSONObject(0) else null
                val last = item.optJSONObject("last_message")
                ChatItem(
                    item.optInt("id"),
                    item.optString("title").takeIf { it.isNotBlank() && it != "null" }
                        ?: other?.optString("display_name", "Чат").orEmpty().ifBlank { "Чат" },
                    last?.optString("body") ?: "Нет сообщений",
                    item.optString("kind") == "global",
                    item.optString("kind") == "saved"
                )
            }.sortedWith(compareByDescending<ChatItem> { it.isSaved }.thenByDescending { it.isGlobal })
        } catch (e: Exception) {
            error = e.message ?: "Не удалось загрузить чаты"
        } finally {
            loading = false
        }
    }

    if (selectedChat != null) {
        ConversationScreen(session, selectedChat!!, onBack = {
            selectedChat = null
            refreshKey++
        })
        return
    }

    fun searchUsers() {
        if (search.trim().length < 2) return
        scope.launch {
            searching = true
            error = ""
            try {
                val result = api("/users/search?q=" + Uri.encode(search.trim()), session.token)
                val arr = result.optJSONArray("items") ?: JSONArray()
                searchResults = (if (search.trim().removePrefix("@").equals("LosGramBotFather", true)) listOf(-1 to "LosGram BotFather") else emptyList()) + (0 until arr.length()).map {
                    val user = arr.getJSONObject(it)
                    user.optInt("id") to user.optString("display_name", "Пользователь")
                }
            } catch (e: Exception) {
                error = e.message ?: "Ошибка поиска"
            } finally {
                searching = false
            }
        }
    }

    if (selectedTab == 2) {
        LosGramMarketScreen(onBack = { selectedTab = 0 })
        return
    }
    if (selectedTab == 1) {
        ContactsScreen(
            session = session,
            onBack = { selectedTab = 0 },
            onOpenChat = { chat ->
                selectedChat = chat
                selectedTab = 0
            }
        )
        return
    }

    if (selectedTab == 3 || selectedTab == 4) {
        ProfileSettingsScreen(
            session = session,
            showProfile = selectedTab == 4,
            onBack = { selectedTab = 0 },
            onOpenSettings = { selectedTab = 3 },
            onLogout = onLogout
        )
        return
    }

    Surface(Modifier.fillMaxSize(), color = Navy) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().height(88.dp).padding(start = 22.dp, end = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("LosGram", color = White, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Text("▣", color = Blue, fontSize = 23.sp, modifier = Modifier.padding(end = 22.dp))
                    Text("⋮", color = White, fontSize = 28.sp,
                        modifier = Modifier.clickable { onLogout() }.padding(horizontal = 4.dp))
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(72.dp)
                        .clip(CircleShape).background(Panel).clickable { searchOpen = true }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⌕", color = Muted, fontSize = 29.sp, modifier = Modifier.padding(end = 16.dp))
                    if (!searchOpen) {
                        Text("Поиск чатов", color = Muted, fontSize = 20.sp)
                    } else {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it; searchResults = emptyList() },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Имя или @username", color = Muted) },
                            trailingIcon = {
                                Text("Найти", color = Blue, modifier = Modifier.clickable { searchUsers() }.padding(6.dp))
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = White, unfocusedTextColor = White,
                                cursorColor = Blue, focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                    }
                }

                if (searchOpen && (searching || searchResults.isNotEmpty())) {
                    if (searching) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue)
                    searchResults.forEach { (userId, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (userId == -1) {
                                    selectedChat = ChatItem(-1, "LosGram BotFather", "Помощник по созданию ботов")
                                    searchOpen = false; searchResults = emptyList(); search = ""
                                } else scope.launch {
                                    try {
                                        val result = api("/conversations/direct", session.token, "POST",
                                            JSONObject().put("user_id", userId))
                                        selectedChat = ChatItem(result.optInt("id"), name, "Нет сообщений")
                                        searchOpen = false
                                        searchResults = emptyList()
                                        search = ""
                                    } catch (e: Exception) {
                                        error = e.message ?: "Не удалось создать чат"
                                    }
                                }
                            }.padding(horizontal = 22.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(48.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                                Text(name.take(1).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(name, color = White, fontSize = 16.sp)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (error.isNotBlank()) {
                    Text(error, color = Color(0xFFFF7777), modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
                }

                when {
                    loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Blue)
                    }
                    chats.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Общий чат LosGram появится здесь", color = Muted, fontSize = 15.sp)
                    }
                    else -> LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 110.dp)
                    ) {
                        items(chats, key = { it.id }) { chat ->
                            Row(
                                Modifier.fillMaxWidth().height(142.dp).clickable { selectedChat = chat }
                                    .padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(92.dp).clip(CircleShape)
                                        .background(if (chat.isSaved) Color(0xFF8D68F5) else if (chat.isGlobal) Blue else Color(0xFF527DA3)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(if (chat.isSaved) "★" else if (chat.isGlobal) "L" else chat.name.take(1).uppercase(),
                                        color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Medium)
                                }
                                Spacer(Modifier.width(18.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(chat.name, color = White, fontSize = 21.sp,
                                        fontWeight = FontWeight.Medium, maxLines = 1)
                                    Spacer(Modifier.height(5.dp))
                                    Text(chat.message, color = Muted, fontSize = 18.sp, maxLines = 1)
                                }
                            }
                            HorizontalDivider(color = Color(0xFF202020), thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 94.dp))
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = {
                    searchOpen = true
                    search = ""
                    searchResults = emptyList()
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 126.dp).size(96.dp),
                containerColor = Blue, contentColor = Color.White, shape = CircleShape
            ) { Text("✚", fontSize = 38.sp) }

            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 30.dp, end = 30.dp, bottom = 18.dp)
                    .height(104.dp).clip(RoundedCornerShape(42.dp)).background(Color(0xFF242424))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val labels = listOf("Чаты", "Контакты", "Маркет", "Настройки", "Профиль")
                val icons = listOf("●", "☻", "◆", "⚙", "◉")
                labels.forEachIndexed { index, label ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(38.dp))
                            .background(if (selectedTab == index) Color(0xFF30352D) else Color.Transparent)
                            .clickable {
                                selectedTab = index
                                if (index == 1) { searchOpen = true; search = ""; searchResults = emptyList() }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(icons[index], color = if (selectedTab == index) Blue else White, fontSize = 32.sp)
                        Text(label, color = if (selectedTab == index) Blue else White, fontSize = 12.sp,
                            fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun StarsTopUpNotice(event: JSONObject) {
    val amount = event.optInt("amount")
    val balance = event.optInt("balance")
    val time = event.optString("time")
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF171717))
            .padding(horizontal = 18.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("⭐", fontSize = 58.sp)
        Spacer(Modifier.height(8.dp))
        Text("Звёзды успешно зачислены!", color = White, fontSize = 18.sp,
            fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text("+$amount ⭐", color = Color(0xFFFFD76A), fontSize = 34.sp,
            fontWeight = FontWeight.ExtraBold)
        Text("Пополнение баланса LosGram", color = White, fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Color(0xFF383838))
        Spacer(Modifier.height(12.dp))
        Text("Текущий баланс", color = Muted, fontSize = 13.sp)
        Text("$balance ⭐", color = Color(0xFFFFD76A), fontSize = 22.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(time, color = Muted, fontSize = 11.sp)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConversationScreen(session: LoginSession, chat: ChatItem, onBack: () -> Unit) {
    if (chat.id == -1) {
        Column(Modifier.fillMaxSize().background(Navy)) {
            Row(Modifier.fillMaxWidth().height(64.dp).background(Panel).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Blue, fontSize = 30.sp, modifier = Modifier.clickable { onBack() }.padding(end = 18.dp))
                Column {
                    Text("LosGram BotFather", color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("помощник по созданию ботов", color = Muted, fontSize = 12.sp)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("Привет! Я помогу создавать ботов LosGram. Доступные команды:", color = White)
                listOf("/start", "/help", "/newbot", "/mybots", "/token", "/revoke").forEach {
                    Text(it, color = Blue, modifier = Modifier.padding(vertical = 5.dp))
                }
                BotFatherPanel(session)
            }
        }
        return
    }
    var messages by remember { mutableStateOf<List<MessageItem>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var selectedMessage by remember { mutableStateOf<MessageItem?>(null) }
    var replyTo by remember { mutableStateOf<MessageItem?>(null) }
    var editing by remember { mutableStateOf<MessageItem?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    suspend fun reload() {
        val response = api("/conversations/\${chat.id}/messages", session.token)
        val arr = response.optJSONArray("items") ?: JSONArray()
        messages = (0 until arr.length()).map {
            val item = arr.getJSONObject(it)
            val sender = item.optInt("sender_id")
            val parentId = item.optInt("reply_to_id").takeIf { id -> id > 0 }
            val parent = parentId?.let { id -> (0 until arr.length()).firstNotNullOfOrNull { ix ->
                arr.optJSONObject(ix)?.takeIf { it.optInt("id") == id }
            } }
            MessageItem(item.optInt("id"), sender, item.optString("body"), sender == session.userId,
                parentId, parent?.optString("body"), item.optBoolean("edited"), item.optBoolean("deleted"))
        }
    }

    LaunchedEffect(chat.id) {
        try {
            while (true) {
                try { reload(); error = "" }
                catch (e: Exception) { error = e.message ?: "Не удалось загрузить сообщения" }
                finally { loading = false }
                delay(3000)
            }
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    fun sendDraft() {
        val text = draft.trim()
        if (text.isBlank() || busy) return
        scope.launch {
            busy = true
            try {
                val target = editing
                if (target != null) {
                    api("/conversations/\${chat.id}/messages/\${target.id}", session.token, "PATCH",
                        JSONObject().put("body", text))
                } else {
                    val body = JSONObject().put("body", text)
                    replyTo?.let { body.put("reply_to_id", it.id) }
                    api("/conversations/\${chat.id}/messages", session.token, "POST", body)
                }
                draft = ""
                replyTo = null
                editing = null
                reload()
                error = ""
            } catch (e: Exception) { error = e.message ?: "Не удалось сохранить сообщение" }
            finally { busy = false }
        }
    }

    Surface(Modifier.fillMaxSize(), color = Navy) {
        Column {
            Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Blue, fontSize = 30.sp, modifier = Modifier.clickable { if (showSearch) { showSearch = false; searchQuery = "" } else onBack() }.padding(end = 14.dp))
                if (showSearch) {
                    OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.weight(1f),
                        singleLine = true, placeholder = { Text("Поиск в чате") },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue, unfocusedBorderColor = Navy,
                            focusedTextColor = White, unfocusedTextColor = White, focusedContainerColor = Navy, unfocusedContainerColor = Navy))
                } else {
                    Text(chat.name, color = White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                Text(if (showSearch) "✕" else "⌕", color = White, fontSize = 24.sp,
                    modifier = Modifier.clickable { showSearch = !showSearch; searchQuery = "" }.padding(start = 12.dp))
            }
            if (error.isNotBlank()) Text(error, color = Color(0xFFFF7777), modifier = Modifier.padding(12.dp))
            if (loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Blue)
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                items(messages.filter { searchQuery.isBlank() || it.body.contains(searchQuery, ignoreCase = true) }, key = { it.id }) { message ->
                    val event = remember(message.body) {
                        if (message.body.startsWith("LG_EVENT:")) {
                            try { JSONObject(message.body.removePrefix("LG_EVENT:")) } catch (_: Exception) { null }
                        } else null
                    }
                    if (event?.optString("type") == "stars_topup") StarsTopUpNotice(event)
                    else Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
                        Column(Modifier.widthIn(max = 300.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (message.mine) Color(0xFF245B87) else Panel)
                            .combinedClickable(onClick = {}, onLongClick = {
                                if (!message.deleted && !message.body.startsWith("LG_EVENT:")) selectedMessage = message
                            }).padding(12.dp)) {
                            message.replyToBody?.takeIf { it.isNotBlank() }?.let {
                                Text("↪ $it", color = Color(0xFFB8D7F0), fontSize = 11.sp, maxLines = 2,
                                    modifier = Modifier.padding(bottom = 5.dp))
                            }
                            Text(message.body, color = if (message.deleted) Muted else White)
                            if (message.edited && !message.deleted) Text("изменено", color = Color(0xFFB8D7F0),
                                fontSize = 10.sp, modifier = Modifier.align(Alignment.End).padding(top = 3.dp))
                        }
                    }
                }
            }
            if (replyTo != null || editing != null) {
                Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (editing != null) "Редактирование сообщения" else "Ответ на сообщение",
                            color = Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text((editing ?: replyTo)?.body.orEmpty(), color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Text("✕", color = White, modifier = Modifier.clickable { replyTo = null; editing = null; draft = "" }.padding(8.dp))
                }
            }
            Row(Modifier.fillMaxWidth().background(Panel).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text(if (editing != null) "Изменить сообщение" else "Сообщение") }, enabled = !busy,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue, unfocusedBorderColor = Navy,
                        focusedTextColor = White, unfocusedTextColor = White, focusedContainerColor = Navy, unfocusedContainerColor = Navy))
                Spacer(Modifier.width(8.dp))
                Button(enabled = draft.isNotBlank() && !busy, onClick = { sendDraft() },
                    colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text(if (editing != null) "✓" else "➤") }
            }
        }
    }

    selectedMessage?.let { message ->
        AlertDialog(onDismissRequest = { selectedMessage = null }, title = { Text("Сообщение") },
            text = { Text(message.body, maxLines = 3) },
            confirmButton = {
                Row {
                    TextButton(onClick = { replyTo = message; editing = null; draft = ""; selectedMessage = null }) { Text("Ответить") }
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("LosGram", message.body))
                        selectedMessage = null
                    }) { Text("Копировать") }
                    TextButton(onClick = {
                        selectedMessage = null
                        scope.launch {
                            try {
                                api("/conversations/\${chat.id}/messages/\${message.id}/forward-to-saved", session.token, "POST", JSONObject())
                                error = "Переслано в Избранное"
                                reload()
                            } catch (e: Exception) { error = e.message ?: "Не удалось переслать сообщение" }
                        }
                    }) { Text("В Избранное") }
                    if (message.mine) {
                        TextButton(onClick = { editing = message; replyTo = null; draft = message.body; selectedMessage = null }) { Text("Изменить") }
                        TextButton(onClick = {
                            selectedMessage = null
                            scope.launch {
                                try {
                                    api("/conversations/\${chat.id}/messages/\${message.id}", session.token, "DELETE")
                                    reload()
                                } catch (e: Exception) { error = e.message ?: "Не удалось удалить сообщение" }
                            }
                        }) { Text("Удалить", color = Color(0xFFFF7777)) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { selectedMessage = null }) { Text("Закрыть") } })
    }
}

@Composable
private fun ProfileSettingsScreen(
    session: LoginSession,
    showProfile: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit
) {
    var displayName by remember { mutableStateOf(session.displayName) }
    var username by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var avatarUrl by remember { mutableStateOf("") }
    var businessName by remember { mutableStateOf("") }
    var businessDescription by remember { mutableStateOf("") }
    var premiumActive by remember { mutableStateOf(false) }
    var premiumUntil by remember { mutableStateOf("") }
    var starsBalance by remember { mutableIntStateOf(0) }
    var notifications by remember { mutableStateOf(true) }
    var showPhone by remember { mutableStateOf(false) }
    var showLastSeen by remember { mutableStateOf(true) }
    var theme by remember { mutableStateOf("dark") }
    var expandedSetting by remember { mutableStateOf("") }
    var showStarsPage by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session.token) {
        try {
            val me = api("/me", session.token)
            displayName = me.optString("display_name", session.displayName)
            username = me.optString("username")
            bio = me.optString("bio")
            avatarUrl = me.optString("avatar_url")
            businessName = me.optString("business_name")
            businessDescription = me.optString("business_description")
            premiumActive = me.optBoolean("premium_active", false)
            premiumUntil = me.optString("premium_until")
            starsBalance = me.optInt("stars_balance", 0)
            val prefs = api("/me/settings", session.token)
            notifications = prefs.optBoolean("notifications_enabled", true)
            showPhone = prefs.optBoolean("show_phone", false)
            showLastSeen = prefs.optBoolean("show_last_seen", true)
            theme = prefs.optString("theme", "dark")
        } catch (e: Exception) { message = e.message ?: "Не удалось загрузить профиль" }
        loading = false
    }

    fun saveProfile() {
        saving = true
        message = ""
        scope.launch {
            try {
                val body = JSONObject()
                    .put("display_name", displayName.trim())
                    .put("username", username.trim().ifBlank { JSONObject.NULL })
                    .put("bio", bio.trim())
                    .put("avatar_url", avatarUrl.trim().ifBlank { JSONObject.NULL })
                api("/me", session.token, "PATCH", body)
                message = "Изменения сохранены"
            } catch (e: Exception) { message = e.message ?: "Не удалось сохранить профиль" }
            finally { saving = false }
        }
    }

    val link = if (username.isNotBlank()) "@$username" else "Сначала задайте username"
    if (showStarsPage) { StarsWalletScreen(starsBalance) { showStarsPage = false }; return }
    Surface(Modifier.fillMaxSize(), color = Navy) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Blue, fontSize = 32.sp,
                    modifier = Modifier.clickable { onBack() }.padding(end = 18.dp))
                Text(if (showProfile) "Мой профиль" else "Настройки", color = White,
                    fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Blue)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp)) {
                    if (showProfile) {
                        item {
                            val context = LocalContext.current
                            var showChannel by remember {
                                mutableStateOf(context.getSharedPreferences("losgram_profile", Context.MODE_PRIVATE)
                                    .getBoolean("show_channel", false))
                            }
                            val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                                if (uri != null) {
                                    context.getSharedPreferences("losgram_profile", Context.MODE_PRIVATE).edit().putString("avatar_uri", uri.toString()).apply()
                                    message = "Фото выбрано и сохранено на этом устройстве"
                                }
                            }
                            Column(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(54.dp).clip(CircleShape).background(Panel),
                                        contentAlignment = Alignment.Center) { Text("▦", color = White, fontSize = 26.sp) }
                                    Box(Modifier.size(54.dp).clip(CircleShape).background(Panel),
                                        contentAlignment = Alignment.Center) { Text("⋮", color = White, fontSize = 28.sp) }
                                }
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    val savedAvatar = remember { context.getSharedPreferences("losgram_profile", Context.MODE_PRIVATE).getString("avatar_uri", null) }
                                    val avatarBitmap = remember(savedAvatar) {
                                        try { savedAvatar?.let { context.contentResolver.openInputStream(Uri.parse(it))?.use { stream -> BitmapFactory.decodeStream(stream)?.asImageBitmap() } } }
                                        catch (_: Exception) { null }
                                    }
                                    Box(Modifier.size(124.dp).clip(CircleShape).background(Color(0xFF354B5D)), contentAlignment = Alignment.Center) {
                                        if (avatarBitmap != null) Image(avatarBitmap, null, Modifier.fillMaxSize())
                                        else Text(displayName.trim().take(1).uppercase().ifBlank { "L" }, color = White, fontSize = 50.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                Text(displayName.ifBlank { "Ваше имя" } +
                                    if (username.isNotBlank()) " #$username" else "",
                                    color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                Spacer(Modifier.height(8.dp))
                                Text("LosGram", color = Muted, fontSize = 11.sp,
                                    modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                Spacer(Modifier.height(10.dp))
                                Spacer(Modifier.height(14.dp))
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("0", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                        Text("Публикации", color = Muted, fontSize = 11.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("—", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                        Text("Рейтинг", color = Muted, fontSize = 11.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("LosGram", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Text("Аккаунт", color = Muted, fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.height(20.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    listOf("▣\nВыбрать фото", "✎\nИзменить", "⚙\nНастройки").forEachIndexed { index, label ->
                                        Column(Modifier.weight(1f).height(70.dp).clip(RoundedCornerShape(18.dp))
                                            .background(Panel).clickable {
                                                when(index) {
                                                    0 -> photoPicker.launch("image/*")
                                                    1 -> expandedSetting = if (expandedSetting == "Редактирование профиля") "" else "Редактирование профиля"
                                                    2 -> onOpenSettings()
                                                }
                                            }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center) {
                                            Text(label.substringBefore("\n"), color = White, fontSize = 22.sp)
                                            Text(label.substringAfter("\n"), color = White, fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel)
                                    .clickable { message = "Музыкальный статус появится в следующем обновлении" }
                                    .padding(10.dp), contentAlignment = Alignment.Center) {
                                    Text("♫  Добавить музыкальный статус  ›", color = White, fontSize = 14.sp)
                                }
                                if (showChannel) {
                                    Spacer(Modifier.height(22.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Канал", color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Мой канал", color = Muted, fontSize = 12.sp)
                                    }
                                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp)
                                        .clip(RoundedCornerShape(16.dp)).background(Panel).padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(52.dp).clip(CircleShape).background(Blue),
                                            contentAlignment = Alignment.Center) {
                                            Text(displayName.take(1).uppercase().ifBlank { "L" }, color = White, fontSize = 22.sp)
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Мой канал", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                            Text("Канал ещё не создан", color = Muted, fontSize = 12.sp)
                                        }
                                    }
                                }
                                Text(session.virtualNumber, color = White, fontSize = 20.sp)
                                Text("Телефон", color = Muted, fontSize = 13.sp)
                                Spacer(Modifier.height(22.dp))
                                Text(if (bio.isNotBlank()) bio else "Добавьте информацию о себе",
                                    color = White, fontSize = 17.sp)
                                Text("О себе", color = Muted, fontSize = 13.sp)
                                Spacer(Modifier.height(22.dp))
                                Text(if (username.isNotBlank()) "@$username" else "Не задано",
                                    color = White, fontSize = 20.sp)
                                Text("Имя пользователя", color = Muted, fontSize = 13.sp)
                                Spacer(Modifier.height(22.dp))
                                Text("Не указана", color = White, fontSize = 20.sp)
                                Text("Дата рождения", color = Muted, fontSize = 13.sp)
                                Text("Настройки профиля", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 18.dp).clickable { onOpenSettings() })
                                Spacer(Modifier.height(22.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    listOf("Публикации", "Архив").forEach { tab ->
                                        Box(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Panel)
                                            .clickable { message = if(tab == "Архив") "Архив пока пуст" else "Публикаций пока нет" }
                                            .padding(13.dp), contentAlignment = Alignment.Center) {
                                            Text(tab, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                                if (expandedSetting == "Редактирование профиля") {
                                    Spacer(Modifier.height(18.dp))
                                    Text("РЕДАКТИРОВАТЬ ПРОФИЛЬ", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    OutlinedTextField(displayName, { displayName = it.take(80) }, Modifier.fillMaxWidth(),
                                        singleLine = true, label = { Text("Имя и фамилия") })
                                    OutlinedTextField(username, { username = it.removePrefix("@").filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(32) },
                                        Modifier.fillMaxWidth(), singleLine = true, label = { Text("Имя пользователя") })
                                    OutlinedTextField(bio, { bio = it.take(160) }, Modifier.fillMaxWidth(),
                                        minLines = 3, label = { Text("О себе") })
                                    OutlinedTextField(avatarUrl, { avatarUrl = it.take(500) }, Modifier.fillMaxWidth(),
                                        singleLine = true, label = { Text("Ссылка на фото HTTPS") })
                                    Spacer(Modifier.height(8.dp))
                                    PreferenceSwitch("Показывать канал в профиле",
                                        "Добавить раздел канала на страницу профиля", showChannel) { enabled ->
                                        showChannel = enabled
                                        context.getSharedPreferences("losgram_profile", Context.MODE_PRIVATE)
                                            .edit().putBoolean("show_channel", enabled).apply()
                                    }
                                    Button(onClick = { saveProfile(); expandedSetting = "" }, enabled = !saving && displayName.isNotBlank(),
                                        modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                                        Text(if (saving) "Сохраняем..." else "Сохранить изменения")
                                    }
                                }
                                Spacer(Modifier.height(22.dp))
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(14.dp)) {
                                    Text("КОНФИДЕНЦИАЛЬНОСТЬ", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    PreferenceSwitch("Показывать номер", "Разрешить другим видеть номер LosGram", showPhone) { value ->
                                        showPhone = value
                                        scope.launch { try { api("/me/settings", session.token, "PATCH", JSONObject().put("show_phone", value)) }
                                            catch (e: Exception) { message = e.message ?: "Ошибка сохранения" } }
                                    }
                                    Divider(color = Navy)
                                    PreferenceSwitch("Последняя активность", "Показывать время посещения", showLastSeen) { value ->
                                        showLastSeen = value
                                        scope.launch { try { api("/me/settings", session.token, "PATCH", JSONObject().put("show_last_seen", value)) }
                                            catch (e: Exception) { message = e.message ?: "Ошибка сохранения" } }
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(68.dp).clip(CircleShape).background(Blue),
                                        contentAlignment = Alignment.Center) {
                                        Text(displayName.trim().take(1).uppercase().ifBlank { "L" },
                                            color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(displayName.ifBlank { "Пользователь LosGram" },
                                            color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                        Text("${session.virtualNumber} • ${if (username.isNotBlank()) "@$username" else "username не задан"}",
                                            color = Muted, fontSize = 13.sp)
                                    }
                                    Text("›", color = Muted, fontSize = 26.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp))
                                }
                                Text("Аккаунты", color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp))
                                SettingsEntry("👤", Color(0xFF229ED9), "Аккаунт",
                                    "Номер, имя пользователя, «О себе»", "Аккаунт", expandedSetting) { expandedSetting = it }
                                SettingsEntry("💬", Color(0xFFFF9F0A), "Настройки чатов",
                                    "Обои, ночной режим, анимации", "Настройки чатов", expandedSetting) { expandedSetting = it }
                                SettingsEntry("🔑", Color(0xFF35C759), "Конфиденциальность",
                                    "Время захода, устройства, ключи доступа", "Конфиденциальность", expandedSetting) { expandedSetting = it }
                                SettingsEntry("🔔", Color(0xFFFF4058), "Уведомления",
                                    "Звуки, звонки, счётчик сообщений", "Уведомления", expandedSetting) { expandedSetting = it }
                                SettingsEntry("◒", Color(0xFF477BEE), "Данные и память",
                                    "Настройки загрузки медиафайлов", "Данные и память", expandedSetting) { expandedSetting = it }
                                SettingsEntry("📁", Color(0xFF229ED9), "Папки с чатами",
                                    "Создание и сортировка папок", "Папки с чатами", expandedSetting) { expandedSetting = it }
                                SettingsEntry("💻", Color(0xFF229ED9), "Устройства",
                                    "Управление активными сеансами", "Устройства", expandedSetting) { expandedSetting = it }
                                SettingsEntry("🔋", Color(0xFFFF8617), "Энергосбережение",
                                    "Экономия энергии при низком заряде", "Энергосбережение", expandedSetting) { expandedSetting = it }
                                SettingsEntry("🌐", Color(0xFFB45BE5), "Язык",
                                    "Русский", "Язык", expandedSetting) { expandedSetting = it }

                                Spacer(Modifier.height(22.dp))
                                Text("LosGram", color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                OwnerPanel(session)
                                Spacer(Modifier.height(12.dp))
                                SettingsEntry("⭐", Color(0xFF8D68F5), "LosGram Premium",
                                    if (premiumActive) "Подписка активна" else "Подключить Premium и управлять подпиской", "LosGram Premium", expandedSetting) { expandedSetting = it }
                                SettingsEntry("★", Color(0xFFFF9F0A), "Звёзды LosGram",
                                    "Покупки, переводы и история операций", "Звёзды LosGram", expandedSetting) { showStarsPage = true }
                                SettingsEntry("🏬", Color(0xFFFF4058), "LosGram для бизнеса",
                                    "Инструменты бизнес-профиля", "LosGram для бизнеса", expandedSetting) { expandedSetting = it }
                                SettingsEntry("🎁", Color(0xFFFF8617), "Отправить подарок",
                                    "Подарки пользователям LosGram", "Отправить подарок", expandedSetting) { expandedSetting = it }

                                Spacer(Modifier.height(22.dp))
                                Text("Помощь", color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                SettingsEntry("•••", Color(0xFFFF9F0A), "Задать вопрос",
                                    "Связаться с командой LosGram", "Задать вопрос", expandedSetting) { expandedSetting = it }
                                SettingsEntry("?", Color(0xFF229ED9), "Вопросы о LosGram",
                                    "Ответы на частые вопросы", "Вопросы о LosGram", expandedSetting) { expandedSetting = it }
                                SettingsEntry("✦", Color(0xFFB45BE5), "Возможности LosGram",
                                    "Что доступно в мессенджере", "Возможности LosGram", expandedSetting) { expandedSetting = it }
                                SettingsEntry("✓", Color(0xFF35C759), "Политика конфиденциальности",
                                    "Как защищаются ваши данные", "Политика конфиденциальности", expandedSetting) { expandedSetting = it }

                                Spacer(Modifier.height(18.dp))
                                Text("LosGram для Android • Настройки", color = Muted, fontSize = 12.sp,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                                    Text("Выйти из аккаунта", color = Color(0xFFFF7777))
                                }
                            }
                        }
                    }
                    if (message.isNotBlank()) item {
                        Text(message, color = if (message.contains("сохран")) Blue else Color(0xFFFF7777),
                            modifier = Modifier.padding(vertical = 10.dp))
                    }
                }
            }
        }
    }
}


@Composable
private fun LosGramMarketScreen(onBack: () -> Unit, balance: Int = 0) {
    val gifts = listOf("Мишка" to ("🧸" to 15), "Сердце с бантом" to ("💝" to 15), "Подарок" to ("🎁" to 25), "Букет" to ("💐" to 50))
    var selected by remember { mutableStateOf<Pair<String, Pair<String, Int>>?>(null) }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = White, fontSize = 34.sp, modifier = Modifier.clickable { onBack() }.padding(end = 16.dp))
                Text("Отправить подарок", color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("Баланс", color = White, fontSize = 14.sp)
                    Text("⭐ $balance", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.size(112.dp).clip(CircleShape).background(Color(0xFF282828)), contentAlignment = Alignment.Center) { Text("🎁", fontSize = 54.sp) }
                Spacer(Modifier.height(42.dp))
                Text("Купить подарок", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                Text("Подарок можно добавить в профиль или отложить.", color = White, fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 22.sp)
                Spacer(Modifier.height(18.dp))
                Text("Выберите подарок для друга.", color = White, fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                Box(Modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xFF242424)).padding(horizontal = 24.dp, vertical = 10.dp)) { Text("Все", color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(26.dp))
                Text("Коллекционные NFT-подарки", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp))
                val collectibles = listOf("Neon Cat" to "🐱", "Golden Ghost" to "👻", "Pixel Dragon" to "🐉", "Royal Crown" to "👑")
                collectibles.chunked(2).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowItems.forEach { item ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Color(0xFF151515))
                                .padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(item.second, fontSize = 62.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(item.first, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Коллекционный подарок", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                gifts.chunked(3).forEach { rowGifts ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowGifts.forEach { gift ->
                            Column(Modifier.weight(1f).clickable { selected = gift }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) { Text(gift.second.first, fontSize = 76.sp) }
                                Spacer(Modifier.height(12.dp))
                                Row(Modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xFF241B0C)).padding(horizontal = 15.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("⭐", fontSize = 20.sp); Spacer(Modifier.width(5.dp)); Text(gift.second.second.toString(), color = Color(0xFFFFB52E), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        repeat(3 - rowGifts.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
    selected?.let { gift -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(gift.first) },
        text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(gift.second.first, fontSize = 64.sp); Text("Цена: ⭐ " + gift.second.second); Text("Покупка и отправка пока недоступны: серверное списание звёзд не подключено.") } },
        confirmButton = { TextButton(onClick = { selected = null }) { Text("Понятно") } }) }
}

@Composable
private fun StarsWalletScreen(balance: Int, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    val spin = rememberInfiniteTransition(label = "star-spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "angle")
    Surface(Modifier.fillMaxSize(), color = Color.Black) { Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) { Text("‹", color = White, fontSize = 36.sp, modifier = Modifier.clickable { onBack() }.padding(end = 18.dp)); Text("Звёзды LosGram", color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                Text("⭐", fontSize = 150.sp, modifier = Modifier.graphicsLayer {
                    rotationY = angle
                    rotationZ = kotlin.math.sin(Math.toRadians(angle.toDouble())).toFloat() * 5f
                    cameraDistance = 18f * density
                })
            }
            Spacer(Modifier.height(8.dp)); Text("Звёзды LosGram", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp)); Text("Звёзды LosGram нужны для оплаты контента и услуг в мини-приложениях.", color = White, fontSize = 16.sp, lineHeight = 23.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("Подробнее о звёздах  ›", color = Color(0xFFE06A6A), modifier = Modifier.clickable { notice = "Звёзды LosGram — внутренняя валюта для оплаты цифрового контента и услуг." }.padding(top = 4.dp))
            Spacer(Modifier.height(112.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🌟", fontSize = 42.sp)
                Spacer(Modifier.width(8.dp))
                Text(balance.toString(), color = White, fontSize = 42.sp, fontWeight = FontWeight.Bold)
            }
            Text("Ваш баланс", color = White, fontSize = 17.sp)
            Spacer(Modifier.height(22.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { notice = "Покупка звёзд пока не подключена." }, modifier = Modifier.weight(1f).height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White), shape = RoundedCornerShape(40.dp)) { Text("⊕  Пополнить", color = Color.Black) }
                Button(onClick = { notice = "Статистика появится после подключения журнала транзакций." }, modifier = Modifier.weight(1f).height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White), shape = RoundedCornerShape(40.dp)) { Text("▥  Статистика", color = Color.Black) } }
            Text("♧  Подарить звёзды друзьям", color = White, fontSize = 17.sp, modifier = Modifier.clickable { notice = "Перевод звёзд друзьям пока не подключён." }.padding(vertical = 28.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).clickable { notice = "Заработок звёзд будет доступен после подключения партнёрских ссылок." }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Text("✋", fontSize = 28.sp); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Заработать звёзды  NEW", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text("Делитесь ссылками на мини-приложения и получайте часть их дохода в звёздах.", color = White, fontSize = 14.sp) }; Text("›", color = Muted, fontSize = 26.sp) }
            Spacer(Modifier.height(26.dp)); Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) { listOf("Все операции", "Зачисления", "Списания").forEachIndexed { i, label -> Box(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(if (tab == i) Color(0xFF444444) else Color.Transparent).clickable { tab = i }.padding(vertical = 11.dp), contentAlignment = Alignment.Center) { Text(label, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) } } }
            Text("Операций пока нет", color = Muted, modifier = Modifier.padding(vertical = 34.dp))
        } } }
    if (notice.isNotBlank()) AlertDialog(onDismissRequest = { notice = "" }, text = { Text(notice) }, confirmButton = { TextButton(onClick = { notice = "" }) { Text("Понятно") } })
}
@Composable
private fun BotFatherPanel(session: LoginSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bots by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun loadBots() { scope.launch {
        busy = true
        try {
            val a = api("/bots", session.token).optJSONArray("items") ?: JSONArray()
            bots = (0 until a.length()).map { a.getJSONObject(it) }; status = ""
        } catch (e: Exception) { status = e.message ?: "Ошибка загрузки" }
        finally { busy = false }
    } }
    LaunchedEffect(session.token) { loadBots() }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("LOS GRAM BOTFATHER", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("Создавайте ботов LosGram и управляйте их API.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Text("Создать бота", color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(name, { name = it.take(64) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Название") })
        OutlinedTextField(username, { username = it.removePrefix("@").filter { c -> c.isLetterOrDigit() || c == '_' }.take(32) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Username (оканчивается на bot)") })
        OutlinedTextField(description, { description = it.take(512) }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("Описание") })
        Button(onClick = { scope.launch {
            busy = true
            try {
                val res = api("/bots", session.token, "POST", JSONObject().put("name", name.trim()).put("username", username.trim()).put("description", description.trim()))
                token = res.optString("token"); name = ""; username = ""; description = ""; loadBots()
            } catch (e: Exception) { status = e.message ?: "Ошибка"; busy = false }
        } }, enabled = !busy && name.trim().length >= 2 && username.endsWith("bot", true), modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Создать бота") }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Мои боты", color = White, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text("Обновить ↻", color = Blue, modifier = Modifier.clickable { loadBots() }.padding(8.dp))
        }
        if (bots.isEmpty()) Text("Пока нет созданных ботов", color = Muted)
        bots.forEach { b ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp)) {
                Text(b.optString("name"), color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("@${b.optString("username")} · ${if (b.optBoolean("is_active")) "Активен" else "Отключён"}", color = Blue, fontSize = 13.sp)
                Text(b.optString("description"), color = Muted, fontSize = 12.sp)
                Text("Токен: ${b.optString("token_prefix")}••••", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Отозвать токен", color = Blue, modifier = Modifier.clickable { scope.launch {
                        try { token = api("/bots/${b.optInt("id")}/revoke", session.token, "POST", JSONObject()).optString("token") }
                        catch (e: Exception) { status = e.message ?: "Ошибка" }
                    } }.padding(vertical = 8.dp))
                    Text(if (b.optBoolean("is_active")) "Отключить" else "Включить", color = Blue, modifier = Modifier.clickable { scope.launch {
                        try { api("/bots/${b.optInt("id")}/${if (b.optBoolean("is_active")) "disable" else "enable"}", session.token, "POST", JSONObject()); loadBots() }
                        catch (e: Exception) { status = e.message ?: "Ошибка" }
                    } }.padding(vertical = 8.dp))
                }
            }
        }
        if (status.isNotBlank()) Text(status, color = Color(0xFFFF7777), fontSize = 12.sp)
        Text("Команды BotFather: /start, /help, /newbot, /mybots, /token, /revoke, /setdescription, /setabouttext, /setuserpic, /deletebot. Реализация команд расширяется поэтапно.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        Text("Bot API: /getMe · /sendMessage · /getUpdates", color = Muted, fontSize = 12.sp)
    }
    if (token.isNotBlank()) AlertDialog(onDismissRequest = { token = "" }, title = { Text("Токен бота") },
        text = { Column { Text("Сохраните токен сейчас — повторно показать его нельзя.", color = Muted); Text(token, color = White) } },
        confirmButton = { TextButton(onClick = {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("LosGram Bot Token", token)); token = ""
        }) { Text("Скопировать и закрыть") } },
        dismissButton = { TextButton(onClick = { token = "" }) { Text("Закрыть") } })
}

@Composable
private fun SettingsEntry(
    icon: String,
    tint: Color,
    title: String,
    subtitle: String,
    key: String,
    expanded: String,
    onExpand: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .clickable { onExpand(if (expanded == key) "" else key) }
                .padding(vertical = 9.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint),
                contentAlignment = Alignment.Center) {
                Text(icon, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = White.copy(alpha = 0.82f), fontSize = 13.sp,
                    lineHeight = 16.sp)
            }
            if (expanded == key) Text("⌃", color = Muted, fontSize = 20.sp)
        }
        if (expanded == key) {
            SettingsDetail(key)
        }
    }
}


@Composable
private fun SettingsDetail(key: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("losgram_settings", Context.MODE_PRIVATE) }
    var chatAnimations by remember { mutableStateOf(prefs.getBoolean("chat_animations", true)) }
    var linkPreviews by remember { mutableStateOf(prefs.getBoolean("link_previews", true)) }
    var sound by remember { mutableStateOf(prefs.getBoolean("sound_enabled", true)) }
    var vibration by remember { mutableStateOf(prefs.getBoolean("vibration_enabled", true)) }
    var autoDownload by remember { mutableStateOf(prefs.getBoolean("auto_download", false)) }
    var saveToGallery by remember { mutableStateOf(prefs.getBoolean("save_gallery", false)) }
    var energySave by remember { mutableStateOf(prefs.getBoolean("energy_save", false)) }
    var language by remember { mutableStateOf(prefs.getString("language", "Русский") ?: "Русский") }
    var folderMode by remember { mutableStateOf(prefs.getString("folder_mode", "Все чаты") ?: "Все чаты") }
    var helpText by remember { mutableStateOf("") }
    var actionMessage by remember { mutableStateOf("") }
    var accountInfo by remember { mutableStateOf<JSONObject?>(null) }
    var accountLoading by remember { mutableStateOf(false) }
    var accountError by remember { mutableStateOf("") }
    val sessionToken = remember { readToken(context) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(key) {
        if (key == "LosGram Premium" || key == "Звёзды LosGram") {
            accountLoading = true
            accountError = ""
            try {
                if (sessionToken.isNullOrBlank()) throw IllegalStateException("Нужно войти в аккаунт заново.")
                accountInfo = api("/me", sessionToken)
            } catch (e: Exception) { accountError = e.message ?: "Не удалось загрузить данные аккаунта" }
            finally { accountLoading = false }
        }
    }
    fun refreshAccount() {
        scope.launch {
            accountLoading = true
            accountError = ""
            try {
                if (sessionToken.isNullOrBlank()) throw IllegalStateException("Нужно войти в аккаунт заново.")
                accountInfo = api("/me", sessionToken)
            } catch (e: Exception) { accountError = e.message ?: "Не удалось обновить данные" }
            finally { accountLoading = false }
        }
    }
    fun openSystemSettings(action: String) { try { context.startActivity(Intent(action).apply { if (action == android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS) putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName) else data = Uri.parse("package:" + context.packageName) }) } catch (_: Exception) { actionMessage = "Не удалось открыть системные настройки" } }
    Column(Modifier.fillMaxWidth().padding(start = 60.dp, end = 8.dp, bottom = 12.dp)
        .clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp)) {
        when (key) {
            "Настройки чатов" -> {
                PreferenceSwitch("Анимации интерфейса", "Плавные переходы и эффекты", chatAnimations) {
                    chatAnimations = it; prefs.edit().putBoolean("chat_animations", it).apply()
                }
                Divider(color = Navy)
                PreferenceSwitch("Предпросмотр ссылок", "Показывать карточки ссылок в чатах", linkPreviews) {
                    linkPreviews = it; prefs.edit().putBoolean("link_previews", it).apply()
                }
            }
            "Уведомления" -> {
                Text("Системные разрешения и каналы уведомлений Android", color = Muted, fontSize = 12.sp)
                OutlinedButton(onClick = { openSystemSettings(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS) },
                    modifier = Modifier.fillMaxWidth()) { Text("Настроить уведомления Android") }
                PreferenceSwitch("Звуки", "Звуки сообщений и действий", sound) {
                    sound = it; prefs.edit().putBoolean("sound_enabled", it).apply()
                }
                Divider(color = Navy)
                PreferenceSwitch("Вибрация", "Виброотклик при уведомлениях", vibration) {
                    vibration = it; prefs.edit().putBoolean("vibration_enabled", it).apply()
                }
            }
            "Данные и память" -> {
                PreferenceSwitch("Автозагрузка медиа", "Автоматически загружать фото и видео", autoDownload) {
                    autoDownload = it; prefs.edit().putBoolean("auto_download", it).apply()
                }
                Divider(color = Navy)
                PreferenceSwitch("Сохранять в галерею", "Сохранять полученные медиафайлы", saveToGallery) {
                    saveToGallery = it; prefs.edit().putBoolean("save_gallery", it).apply()
                }
                Text("Кэш приложения: " + android.text.format.Formatter.formatShortFileSize(context, context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }),
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                OutlinedButton(onClick = {
                    context.cacheDir.deleteRecursively()
                    actionMessage = "Кэш приложения очищен"
                }, modifier = Modifier.fillMaxWidth()) { Text("Очистить кэш") }
            }
            "Папки с чатами" -> {
                Text("Показывать", color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                listOf("Все чаты", "Личные", "Непрочитанные", "Избранное").forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable {
                        folderMode = option; prefs.edit().putString("folder_mode", option).apply()
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(folderMode == option, onClick = {
                            folderMode = option; prefs.edit().putString("folder_mode", option).apply()
                        }, colors = RadioButtonDefaults.colors(selectedColor = Blue))
                        Text(option, color = White, fontSize = 14.sp)
                    }
                }
                Text("Это быстрые фильтры интерфейса; создание пользовательских папок добавим отдельно.",
                    color = Muted, fontSize = 12.sp)
            }
            "Энергосбережение" -> {
                PreferenceSwitch("Экономия энергии", "Отключать часть визуальных эффектов", energySave) {
                    energySave = it; prefs.edit().putBoolean("energy_save", it).apply()
                    if (it) {
                        chatAnimations = false
                        prefs.edit().putBoolean("chat_animations", false).apply()
                    }
                }
            }
            "Язык" -> {
                listOf("Русский", "English").forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable {
                        language = option; prefs.edit().putString("language", option).apply()
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(language == option, onClick = {
                            language = option; prefs.edit().putString("language", option).apply()
                        }, colors = RadioButtonDefaults.colors(selectedColor = Blue))
                        Text(option, color = White, fontSize = 14.sp)
                    }
                }
                Text("Выбор сохраняется на устройстве. Полный перевод экранов подключим отдельно.",
                    color = Muted, fontSize = 12.sp)
            }
            "Устройства" -> {
                Text("Управление разрешениями и системными возможностями этого устройства.", color = Muted, fontSize = 13.sp)
                OutlinedButton(onClick = { openSystemSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) },
                    modifier = Modifier.fillMaxWidth()) { Text("Открыть настройки приложения") }
            }
            "Конфиденциальность" -> Text("Настройки показа номера и времени посещения доступны в разделе редактирования профиля.",
                color = Muted, fontSize = 13.sp)
            "Аккаунт" -> Text("Изменить имя, username, описание и фото можно во вкладке «Профиль».",
                color = Muted, fontSize = 13.sp)
            "LosGram Premium" -> {
                val active = accountInfo?.optBoolean("premium_active", false) == true
                val until = accountInfo?.optString("premium_until").orEmpty().take(16).replace("T", " ")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("✦", color = Color(0xFFFFD76A), fontSize = 30.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (active) "Premium активен" else "Premium не активен",
                            color = if (active) Color(0xFFFFD76A) else White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (active && until.isNotBlank()) "Действует до: $until UTC" else "Подписка пока не оформлена",
                            color = Muted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Возможности LosGram Premium", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                listOf("Особая отметка Premium в профиле", "Расширенные возможности аккаунта",
                    "Дополнительные настройки персонализации").forEach {
                    Text("• $it", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Text("Premium выдаётся владельцем вручную. Покупки и автоматическое продление отключены.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                if (accountLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue)
                if (accountError.isNotBlank()) Text(accountError, color = Color(0xFFFF8888), fontSize = 12.sp)
                OutlinedButton(onClick = { refreshAccount() }, enabled = !accountLoading,
                    modifier = Modifier.fillMaxWidth()) { Text("Обновить статус") }
            }
            "Звёзды LosGram" -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("⭐", fontSize = 30.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Ваш баланс", color = Muted, fontSize = 12.sp)
                        Text((accountInfo?.optInt("stars_balance") ?: 0).toString(),
                            color = Color(0xFFFFD76A), fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text("Звёзды LosGram", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp))
                Text("Звёзды — внутренняя валюта LosGram. Сейчас баланс можно пополнять и корректировать вручную через панель владельца.",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                Text("Покупка звёзд не подключена. История операций появится после добавления журнала транзакций.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                if (accountLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue)
                if (accountError.isNotBlank()) Text(accountError, color = Color(0xFFFF8888), fontSize = 12.sp)
                OutlinedButton(onClick = { refreshAccount() }, enabled = !accountLoading,
                    modifier = Modifier.fillMaxWidth()) { Text("Обновить баланс") }
            }
            "LosGram для бизнеса" -> Text("Данные бизнес-профиля можно изменить во вкладке «Профиль».",
                color = Muted, fontSize = 13.sp)
            "Отправить подарок" -> LosGramMarketScreen(onBack = { })
            "Задать вопрос" -> {
                OutlinedTextField(helpText, { helpText = it }, Modifier.fillMaxWidth(),
                    label = { Text("Ваш вопрос") }, minLines = 2)
                Text("Отправка обращений появится после подключения поддержки.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            "Вопросы о LosGram" -> Text("LosGram — мессенджер с входом через Telegram-бота, профилями и переписками.",
                color = Muted, fontSize = 13.sp)
            "Возможности LosGram" -> Text("Доступны аккаунт, профиль, переписки, настройки приватности и панель владельца.",
                color = Muted, fontSize = 13.sp)
            "Политика конфиденциальности" -> Text("Данные аккаунта используются для работы LosGram. Не передавайте никому код входа.",
                color = Muted, fontSize = 13.sp)
        }
        if (actionMessage.isNotBlank()) Text(actionMessage, color = Blue, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun OwnerPanel(session: LoginSession) {
    var isAdmin by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var stars by remember { mutableStateOf("0") }
    var premiumDays by remember { mutableStateOf("0") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session.token) {
        try { isAdmin = api("/admin/users?q=", session.token).optJSONArray("items") != null }
        catch (_: Exception) { isAdmin = false }
        checked = true
    }
    if (!checked || !isAdmin) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF3A315B)), contentAlignment = Alignment.Center) {
                Text("♛", color = Color(0xFFC5A6FF), fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Панель владельца", color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Управление аккаунтами LosGram", color = Muted, fontSize = 12.sp)
            }
            Text("OWNER", color = Color(0xFFC5A6FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text("ПОЛЬЗОВАТЕЛИ", color = Color(0xFFC5A6FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Поиск по имени или @username") },
            trailingIcon = {
                Text("⌕", color = Color(0xFFC5A6FF), fontSize = 23.sp,
                    modifier = Modifier.clickable {
                        scope.launch {
                            busy = true
                            try {
                                val arr = api("/admin/users?q=" + Uri.encode(query.trim()), session.token).optJSONArray("items") ?: JSONArray()
                                users = (0 until arr.length()).map { arr.getJSONObject(it) }
                                selected = null
                                status = ""
                            } catch (e: Exception) { status = e.message ?: "Ошибка поиска" }
                            finally { busy = false }
                        }
                    }.padding(horizontal = 12.dp))
            },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF8B6BC8),
                unfocusedBorderColor = Navy, focusedTextColor = White, unfocusedTextColor = White,
                focusedContainerColor = Navy, unfocusedContainerColor = Navy))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF8B6BC8))
        users.forEach { user ->
            val name = user.optString("display_name", "Пользователь")
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (selected?.optInt("id") == user.optInt("id")) Color(0xFF393047) else Navy)
                .clickable { selected = user }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF51416D)), contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase(), color = White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(user.optString("username").takeIf { it.isNotBlank() && it != "null" }?.let { "@$it" }
                        ?: user.optString("virtual_number"), color = Muted, fontSize = 12.sp)
                }
                Text("⭐ ${user.optInt("stars_balance")}", color = Color(0xFFFFD76A), fontSize = 12.sp)
            }
            Spacer(Modifier.height(5.dp))
        }
        selected?.let { user ->
            Divider(color = Navy)
            Spacer(Modifier.height(10.dp))
            Text("УПРАВЛЕНИЕ АККАУНТОМ", color = Color(0xFFC5A6FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(user.optString("display_name"), color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(stars, { stars = it.filter { c -> c == '-' || c.isDigit() }.take(7) },
                Modifier.fillMaxWidth(), label = { Text("Изменение звёзд (+ / −)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF8B6BC8), focusedTextColor = White, unfocusedTextColor = White))
            OutlinedTextField(premiumDays, { premiumDays = it.filter { c -> c == '-' || c.isDigit() }.take(5) },
                Modifier.fillMaxWidth(), label = { Text("Дни Premium (+ / −)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF8B6BC8), focusedTextColor = White, unfocusedTextColor = White))
            Button(onClick = {
                scope.launch {
                    busy = true
                    try {
                        val result = api("/admin/grant", session.token, "POST",
                            JSONObject().put("user_id", user.optInt("id"))
                                .put("stars_delta", stars.toIntOrNull() ?: 0)
                                .put("premium_days", premiumDays.toIntOrNull() ?: 0))
                        user.put("stars_balance", result.optInt("stars_balance"))
                        users = users.toList()
                        stars = "0"; premiumDays = "0"
                        status = "Изменения применены"
                    } catch (e: Exception) { status = e.message ?: "Не удалось применить изменения" }
                    finally { busy = false }
                }
            }, enabled = !busy && ((stars.toIntOrNull() ?: 0) != 0 || (premiumDays.toIntOrNull() ?: 0) != 0),
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF70549B))) {
                Text(if (busy) "Обрабатываем..." else "Применить изменения", color = Color.White)
            }
        }
        if (status.isNotBlank()) Text(status, color = if (status == "Изменения применены") Color(0xFF9BD18B) else Color(0xFFFF8888), fontSize = 12.sp)
    }
}

@Composable
private fun PreferenceSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 16.sp)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    expanded: String,
    onClick: (String) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp))
            .background(Panel).clickable { onClick(title) }.padding(16.dp)
    ) {
        Text(title, color = White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = Muted, fontSize = 13.sp)
        if (expanded == title) {
            Spacer(Modifier.height(7.dp))
            Text("Этот раздел добавлен в интерфейс. Подключение функций будет следующим этапом.", color = Blue, fontSize = 13.sp)
        }
    }
}


@Composable
private fun ContactsScreen(
    session: LoginSession,
    onBack: () -> Unit,
    onOpenChat: (ChatItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = Navy) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", color = Blue, fontSize = 30.sp,
                    modifier = Modifier.clickable { onBack() }.padding(end = 14.dp))
                Text("Контакты", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Text("Найти людей в LosGram", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; error = "" },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Имя или @username") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Blue, unfocusedBorderColor = Panel,
                    focusedTextColor = White, unfocusedTextColor = White,
                    cursorColor = Blue, focusedContainerColor = Panel,
                    unfocusedContainerColor = Panel
                )
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (query.trim().length < 2) {
                        error = "Введите минимум 2 символа"
                    } else {
                        scope.launch {
                            loading = true
                            error = ""
                            try {
                                val response = api("/users/search?q=" + Uri.encode(query.trim()), session.token)
                                val arr = response.optJSONArray("items") ?: JSONArray()
                                results = (0 until arr.length()).map { index ->
                                    val user = arr.getJSONObject(index)
                                    user.optInt("id") to user.optString("display_name", "Пользователь")
                                }
                            } catch (e: Exception) {
                                error = e.message ?: "Не удалось выполнить поиск"
                            } finally {
                                loading = false
                            }
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Blue)
            ) { Text(if (loading) "Ищем..." else "Найти людей") }

            if (error.isNotBlank()) {
                Text(error, color = Color(0xFFFF7777), modifier = Modifier.padding(top = 10.dp))
            }
            if (!loading && results.isEmpty() && error.isBlank()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Введите имя или username, чтобы найти пользователей LosGram",
                        color = Muted, fontSize = 14.sp)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(results, key = { it.first }) { (userId, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    loading = true
                                    error = ""
                                    try {
                                        val response = api(
                                            "/conversations/direct", session.token, "POST",
                                            JSONObject().put("user_id", userId)
                                        )
                                        onOpenChat(ChatItem(response.optInt("id"), name, "Нет сообщений"))
                                    } catch (e: Exception) {
                                        error = e.message ?: "Не удалось открыть переписку"
                                    } finally {
                                        loading = false
                                    }
                                }
                            }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(50.dp).clip(CircleShape).background(Blue),
                                contentAlignment = Alignment.Center) {
                                Text(name.take(1).uppercase(), color = Color.White,
                                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, color = White, fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium)
                                Text("Открыть личный чат", color = Muted, fontSize = 13.sp)
                            }
                            Text("›", color = Muted, fontSize = 24.sp)
                        }
                        HorizontalDivider(color = Panel, thickness = 0.6.dp)
                    }
                }
            }
        }
    }
}
