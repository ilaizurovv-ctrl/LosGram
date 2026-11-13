package com.losgram.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
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
data class ChatItem(val id: Int, val name: String, val message: String, val isGlobal: Boolean = false)
data class MessageItem(val id: Int, val senderId: Int, val body: String, val mine: Boolean)

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
                    item.optString("kind") == "global"
                )
            }.sortedByDescending { it.isGlobal }
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
                searchResults = (0 until arr.length()).map {
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

    if (selectedTab == 2 || selectedTab == 3) {
        ProfileSettingsScreen(
            session = session,
            showProfile = selectedTab == 3,
            onBack = { selectedTab = 0 },
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
                                scope.launch {
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
                                        .background(if (chat.isGlobal) Blue else Color(0xFF527DA3)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(if (chat.isGlobal) "L" else chat.name.take(1).uppercase(),
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
                val labels = listOf("Чаты", "Контакты", "Настройки", "Профиль")
                val icons = listOf("●", "☻", "⚙", "◉")
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
private fun ConversationScreen(session: LoginSession, chat: ChatItem, onBack: () -> Unit) {
    var messages by remember { mutableStateOf<List<MessageItem>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        val response = api("/conversations/${chat.id}/messages", session.token)
        val arr = response.optJSONArray("items") ?: JSONArray()
        messages = (0 until arr.length()).map {
            val item = arr.getJSONObject(it)
            val sender = item.optInt("sender_id")
            MessageItem(item.optInt("id"), sender, item.optString("body"), sender == session.userId)
        }
    }

    LaunchedEffect(chat.id) {
        try {
            while (true) {
                try {
                    reload()
                    error = ""
                } catch (e: Exception) {
                    error = e.message ?: "Не удалось загрузить сообщения"
                } finally {
                    loading = false
                }
                delay(3000)
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Stop polling when the user leaves this conversation.
        }
    }

    Surface(Modifier.fillMaxSize(), color = Navy) {
        Column {
            Row(Modifier.fillMaxWidth().background(Panel).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Blue, fontSize = 30.sp, modifier = Modifier.clickable { onBack() }.padding(end = 14.dp))
                Text(chat.name, color = White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            if (error.isNotBlank()) Text(error, color = Color(0xFFFF7777), modifier = Modifier.padding(12.dp))
            if (loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Blue)
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                items(messages, key = { it.id }) { message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement =
                        if (message.mine) Arrangement.End else Arrangement.Start) {
                        Text(message.body, color = White,
                            modifier = Modifier.padding(vertical = 4.dp)
                                .background(if (message.mine) Color(0xFF245B87) else Panel,
                                    RoundedCornerShape(14.dp)).padding(12.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(Panel).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = draft, onValueChange = { draft = it },
                    modifier = Modifier.weight(1f), placeholder = { Text("Сообщение") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Blue, unfocusedBorderColor = Navy,
                        focusedTextColor = White, unfocusedTextColor = White,
                        focusedContainerColor = Navy, unfocusedContainerColor = Navy))
                Spacer(Modifier.width(8.dp))
                Button(enabled = draft.isNotBlank(), onClick = {
                    val text = draft.trim()
                    scope.launch {
                        try {
                            api("/conversations/${chat.id}/messages", session.token, "POST",
                                JSONObject().put("body", text))
                            draft = ""
                            reload()
                            error = ""
                        } catch (e: Exception) { error = e.message ?: "Не удалось отправить сообщение" }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("➤") }
            }
        }
    }
}

@Composable
private fun ProfileSettingsScreen(
    session: LoginSession,
    showProfile: Boolean,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    var expanded by remember { mutableStateOf("") }
    val title = if (showProfile) "Профиль" else "Настройки"
    Surface(Modifier.fillMaxSize(), color = Navy) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", color = Blue, fontSize = 32.sp,
                    modifier = Modifier.clickable { onBack() }.padding(end = 18.dp))
                Text(title, color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            }
            if (showProfile) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(112.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                        Text(session.displayName.take(1).uppercase(), color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(session.displayName, color = White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(session.virtualNumber, color = Muted, fontSize = 16.sp)
                    Spacer(Modifier.height(22.dp))
                    SettingsRow("Изменить имя", "Настроить отображаемое имя", expanded, { expanded = if (expanded == it) "" else it })
                    SettingsRow("Аватар профиля", "Фотография профиля — скоро", expanded, { expanded = if (expanded == it) "" else it })
                    SettingsRow("О себе", "Добавить описание — скоро", expanded, { expanded = if (expanded == it) "" else it })
                    SettingsRow("Мой QR-код", "Быстро поделиться профилем — скоро", expanded, { expanded = if (expanded == it) "" else it })
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(18.dp)) {
                            Text("⭐ Los Premium", color = Color(0xFFFFD76A), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("Больше возможностей, эксклюзивное оформление и расширенные функции.", color = Muted, fontSize = 14.sp)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { expanded = "premium" }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                                Text("Подробнее")
                            }
                            if (expanded == "premium") Text("Подписка и оплата появятся после подключения безопасной платёжной системы.", color = White, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(18.dp)) {
                            Text("⭐ Звёзды LosGram", color = Color(0xFFFFD76A), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text("Подарки авторам и поддержка каналов — скоро.", color = Muted, fontSize = 14.sp)
                            TextButton(onClick = { expanded = "stars" }) { Text("Узнать больше", color = Blue) }
                            if (expanded == "stars") Text("Баланс звёзд и покупки будут доступны после интеграции платежей.", color = White, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        SettingsRow("Los Premium для бизнеса", "Инструменты для компаний и магазинов", expanded, { expanded = if (expanded == it) "" else it })
                        SettingsRow("Уведомления", "Звуки и настройки уведомлений", expanded, { expanded = if (expanded == it) "" else it })
                        SettingsRow("Конфиденциальность", "Кто может видеть ваши данные", expanded, { expanded = if (expanded == it) "" else it })
                        SettingsRow("Оформление", "Тема и внешний вид приложения", expanded, { expanded = if (expanded == it) "" else it })
                        SettingsRow("Язык", "Язык приложения", expanded, { expanded = if (expanded == it) "" else it })
                        SettingsRow("Устройства", "Активные сеансы — скоро", expanded, { expanded = if (expanded == it) "" else it })
                        SettingsRow("Помощь", "Поддержка LosGram", expanded, { expanded = if (expanded == it) "" else it })
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                            Text("Выйти из аккаунта", color = Color(0xFFFF7777))
                        }
                    }
                }
            }
        }
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
