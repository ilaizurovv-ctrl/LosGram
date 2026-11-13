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
    var displayName by remember { mutableStateOf(session.displayName) }
    var username by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var avatarUrl by remember { mutableStateOf("") }
    var businessName by remember { mutableStateOf("") }
    var businessDescription by remember { mutableStateOf("") }
    var notifications by remember { mutableStateOf(true) }
    var showPhone by remember { mutableStateOf(false) }
    var showLastSeen by remember { mutableStateOf(true) }
    var theme by remember { mutableStateOf("dark") }
    var expandedSetting by remember { mutableStateOf("") }
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
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                                .background(Panel).padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(112.dp).clip(CircleShape).background(Blue),
                                    contentAlignment = Alignment.Center) {
                                    Text(displayName.trim().take(1).uppercase().ifBlank { "L" },
                                        color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(displayName.ifBlank { "Ваше имя" }, color = White,
                                    fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text(if (username.isNotBlank()) "@$username" else "Имя пользователя не задано",
                                    color = Blue, fontSize = 14.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(session.virtualNumber, color = Muted, fontSize = 13.sp)
                                if (bio.isNotBlank()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(bio, color = White, fontSize = 14.sp)
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                            Text("РЕДАКТИРОВАТЬ ПРОФИЛЬ", color = Blue, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .background(Panel).padding(16.dp)) {
                                Text("Имя и фамилия", color = Muted, fontSize = 12.sp)
                                OutlinedTextField(displayName, { displayName = it.take(80) },
                                    Modifier.fillMaxWidth(), singleLine = true,
                                    placeholder = { Text("Как вас называть") })
                                Spacer(Modifier.height(10.dp))
                                Text("Имя пользователя", color = Muted, fontSize = 12.sp)
                                OutlinedTextField(username, {
                                    username = it.removePrefix("@").filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(32)
                                }, Modifier.fillMaxWidth(), singleLine = true,
                                    leadingIcon = { Text("@", color = Blue) },
                                    placeholder = { Text("username") })
                                Text("От 3 до 32 символов: латинские буквы, цифры и _",
                                    color = Muted, fontSize = 11.sp)
                                Spacer(Modifier.height(10.dp))
                                Text("О себе", color = Muted, fontSize = 12.sp)
                                OutlinedTextField(bio, { bio = it.take(160) },
                                    Modifier.fillMaxWidth(), minLines = 3,
                                    placeholder = { Text("Расскажите немного о себе") })
                                Text("${bio.length}/160", color = Muted, fontSize = 11.sp,
                                    modifier = Modifier.align(Alignment.End))
                                Spacer(Modifier.height(10.dp))
                                Text("Имя пользователя для поиска", color = Muted, fontSize = 12.sp)
                                Text(link, color = Blue, fontSize = 14.sp)
                                Spacer(Modifier.height(14.dp))
                                Button(onClick = { saveProfile() }, enabled = !saving && displayName.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                                    Text(if (saving) "Сохраняем..." else "Сохранить изменения")
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .background(Panel).padding(16.dp)) {
                                Text("ФОТО ПРОФИЛЯ", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Ссылка на изображение (HTTPS)", color = Muted, fontSize = 12.sp)
                                OutlinedTextField(avatarUrl, { avatarUrl = it.take(500) },
                                    Modifier.fillMaxWidth(), singleLine = true,
                                    placeholder = { Text("https://...") })
                                Text("Загрузка фото с устройства и истории профиля будут добавлены отдельно.",
                                    color = Muted, fontSize = 11.sp)
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { saveProfile() }, enabled = !saving,
                                    modifier = Modifier.fillMaxWidth()) { Text("Сохранить фото-ссылку") }
                            }
                            Spacer(Modifier.height(14.dp))
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .background(Panel).padding(16.dp)) {
                                Text("КОНФИДЕНЦИАЛЬНОСТЬ", color = Blue, fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold)
                                PreferenceSwitch("Показывать номер", "Разрешить другим видеть номер LosGram",
                                    showPhone) { value ->
                                    showPhone = value
                                    scope.launch { try { api("/me/settings", session.token, "PATCH",
                                        JSONObject().put("show_phone", value)) }
                                        catch (e: Exception) { message = e.message ?: "Ошибка сохранения" } }
                                }
                                Divider(color = Navy)
                                PreferenceSwitch("Последняя активность", "Показывать время посещения",
                                    showLastSeen) { value ->
                                    showLastSeen = value
                                    scope.launch { try { api("/me/settings", session.token, "PATCH",
                                        JSONObject().put("show_last_seen", value)) }
                                        catch (e: Exception) { message = e.message ?: "Ошибка сохранения" } }
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
                                    "Дополнительные возможности подписки", "LosGram Premium", expandedSetting) { expandedSetting = it }
                                SettingsEntry("★", Color(0xFFFF9F0A), "Звёзды LosGram",
                                    "Баланс и начисления звёзд", "Звёзды LosGram", expandedSetting) { expandedSetting = it }
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
            Text(
                when (key) {
                    "Устройства" -> "Здесь будет список активных устройств и возможность завершить сеанс."
                    "Конфиденциальность" -> "Настройте видимость номера, времени посещения и доступ к аккаунту."
                    "Уведомления" -> "Настройки звуков и уведомлений будут подключены отдельно."
                    "Данные и память" -> "Управление автозагрузкой фото, видео и очисткой кэша."
                    "Папки с чатами" -> "Создавайте папки и распределяйте переписки по категориям."
                    "Энергосбережение" -> "Настройки анимации и фоновой активности для экономии заряда."
                    "Язык" -> "Сейчас выбран русский язык."
                    "Аккаунт" -> "Изменение имени, username, номера и описания профиля доступно в разделе «Профиль»."
                    "LosGram Premium" -> "Подписка пока управляется владельцем вручную. Покупка не подключена."
                    "Звёзды LosGram" -> "Начисление звёзд выполняется владельцем через панель владельца."
                    "LosGram для бизнеса" -> "Настройки бизнес-профиля доступны в профиле LosGram."
                    else -> "Раздел интерфейса LosGram. Подключение дополнительных функций будет следующим этапом."
                },
                color = Muted, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(start = 60.dp, end = 8.dp, bottom = 10.dp)
            )
        }
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
