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

private val Navy = Color(0xFF17212B)
private val Panel = Color(0xFF1F2C38)
private val Blue = Color(0xFF3390EC)
private val Muted = Color(0xFF8A9AA9)
private val White = Color(0xFFF5F7FA)

data class LoginSession(val token: String, val userId: Int, val displayName: String, val virtualNumber: String)
data class ChatItem(val id: Int, val name: String, val message: String)
data class MessageItem(val id: Int, val senderId: Int, val body: String, val mine: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val json = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        if (status !in 200..299) {
            throw IllegalStateException(json.optString("detail").ifBlank { "Ошибка сервера ($status)" })
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
                ChatItem(item.optInt("id"),
                    item.optString("title").takeIf { it.isNotBlank() && it != "null" }
                        ?: other?.optString("display_name", "Чат").orEmpty().ifBlank { "Чат" },
                    last?.optString("body") ?: "Нет сообщений")
            }
        } catch (e: Exception) {
            error = e.message ?: "Не удалось загрузить чаты"
        } finally { loading = false }
    }

    if (selectedChat != null) {
        ConversationScreen(session, selectedChat!!, onBack = { selectedChat = null })
        return
    }

    Surface(Modifier.fillMaxSize(), color = Navy) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LosGram", color = Blue, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("${session.displayName} · ${session.virtualNumber}", color = Muted, fontSize = 12.sp)
                }
                Text("Выйти", color = Muted, modifier = Modifier.clickable { onLogout() }.padding(8.dp))
            }
            if (error.isNotBlank()) {
                Text(error, color = Color(0xFFFF7777), modifier = Modifier.padding(horizontal = 16.dp))
                Text("Повторить", color = Blue, modifier = Modifier.clickable { refreshKey++ }.padding(16.dp))
            }
            when {
                loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Blue)
                }
                chats.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Пока нет чатов", color = White, fontSize = 20.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Найдите пользователя, чтобы начать переписку.", color = Muted)
                    }
                }
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(chats, key = { it.id }) { chat ->
                        Row(Modifier.fillMaxWidth().clickable { selectedChat = chat }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(50.dp).clip(CircleShape).background(Blue),
                                contentAlignment = Alignment.Center) {
                                Text(chat.name.take(1).uppercase(), color = Color.White, fontSize = 20.sp)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(chat.name, color = White, fontWeight = FontWeight.SemiBold)
                                Text(chat.message, color = Muted, maxLines = 1, fontSize = 13.sp)
                            }
                        }
                        HorizontalDivider(color = Panel)
                    }
                }
            }
            var search by remember { mutableStateOf("") }
            var searchResults by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
            var searching by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().background(Panel).padding(12.dp)) {
                OutlinedTextField(value = search, onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Найти пользователя по имени или @username") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Blue, unfocusedBorderColor = Navy,
                        focusedTextColor = White, unfocusedTextColor = White,
                        focusedContainerColor = Navy, unfocusedContainerColor = Navy))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Искать", color = Blue, modifier = Modifier.clickable {
                        if (search.trim().length >= 2) scope.launch {
                            searching = true
                            try {
                                val result = api("/users/search?q=" + Uri.encode(search.trim()), session.token)
                                val arr = result.optJSONArray("items") ?: JSONArray()
                                searchResults = (0 until arr.length()).map {
                                    val u = arr.getJSONObject(it)
                                    u.optInt("id") to u.optString("display_name", "Пользователь")
                                }
                            } catch (e: Exception) { error = e.message ?: "Ошибка поиска" }
                            finally { searching = false }
                        }
                    }.padding(8.dp))
                    if (searching) CircularProgressIndicator(Modifier.size(18.dp), color = Blue, strokeWidth = 2.dp)
                }
                searchResults.forEach { (userId, name) ->
                    Text("Начать чат: $name", color = White,
                        modifier = Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                try {
                                    val result = api("/conversations/direct", session.token, "POST",
                                        JSONObject().put("user_id", userId))
                                    selectedChat = ChatItem(result.optInt("id"), name, "Нет сообщений")
                                    searchResults = emptyList()
                                    search = ""
                                } catch (e: Exception) { error = e.message ?: "Не удалось создать чат" }
                            }
                        }.padding(8.dp))
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
