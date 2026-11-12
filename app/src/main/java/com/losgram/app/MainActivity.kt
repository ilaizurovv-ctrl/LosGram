package com.losgram.app

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val API_BASE_URL = "https://YOUR-LOS-GRAM-SERVER.onrender.com"
private const val TELEGRAM_BOT_URL = "https://t.me/LosGram_Bot"

private val Navy = Color(0xFF17212B)
private val Panel = Color(0xFF1F2C38)
private val Blue = Color(0xFF3390EC)
private val Muted = Color(0xFF8A9AA9)
private val White = Color(0xFFF5F7FA)

data class Chat(
    val name: String,
    val message: String,
    val time: String,
    val initials: String,
    val unread: Int = 0,
    val online: Boolean = false
)

private val demoChats = listOf(
    Chat("Александр", "Привет! Как дела?", "23:14", "А", 2, true),
    Chat("LosGram Новости", "Добро пожаловать в LosGram!", "22:48", "L", 5),
    Chat("Мария", "Отправила тебе фотографии", "Вчера", "М", 0, true),
    Chat("Рабочая группа", "Игорь: Отлично, договорились", "Вчера", "Р", 12),
    Chat("Избранное", "Заметки и сохранённые сообщения", "Пн", "★")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Blue,
                    background = Navy,
                    surface = Panel,
                    onBackground = White,
                    onSurface = White
                )
            ) {
                LosGramApp()
            }
        }
    }
}

@Composable
fun LosGramApp() {
    var loggedIn by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("Пользователь") }
    if (loggedIn) {
        MessengerHome(displayName)
    } else {
        TelegramLoginScreen(
            onLogin = { id, code ->
                val result = verifyLosGramLogin(id, code)
                displayName = result
                loggedIn = true
            }
        )
    }
}

private suspend fun verifyLosGramLogin(losgramId: String, code: String): String =
    withContext(Dispatchers.IO) {
        val base = API_BASE_URL.trimEnd('/')
        if (base.contains("YOUR-LOS-GRAM-SERVER")) {
            throw IllegalStateException("Сервер LosGram ещё не подключён. Сначала нужно развернуть backend и указать его HTTPS-адрес.")
        }
        val connection = (URL("$base/auth/telegram/verify").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val body = JSONObject().put("losgram_id", losgramId).put("code", code).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(json.optString("detail", "Не удалось войти. Проверьте номер и код."))
            }
            val user = json.optJSONObject("user")
            user?.optString("display_name")?.takeIf { it.isNotBlank() && it != "null" } ?: "Пользователь"
        } finally {
            connection.disconnect()
        }
    }

@Composable
fun TelegramLoginScreen(onLogin: suspend (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var losgramId by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = Navy) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("LosGram", color = Blue, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Вход в мессенджер", color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                "Получите виртуальный номер и код в официальном Telegram-боте LosGram",
                color = Muted, fontSize = 14.sp
            )
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_BOT_URL))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Открыть @LosGram_Bot", color = Color.White)
            }
            Spacer(Modifier.height(22.dp))
            OutlinedTextField(
                value = losgramId,
                onValueChange = { losgramId = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(10); error = "" },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Виртуальный номер") },
                placeholder = { Text("LG12345678") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                shape = RoundedCornerShape(12.dp),
                colors = loginFieldColors()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6); error = "" },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Код из Telegram") },
                placeholder = { Text("6 цифр") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                shape = RoundedCornerShape(12.dp),
                colors = loginFieldColors()
            )
            if (error.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = Color(0xFFFF7777), fontSize = 13.sp)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (losgramId.length != 10 || code.length != 6) {
                        error = "Введите номер LG и шестизначный код из бота."
                    } else {
                        loading = true
                        error = ""
                        scope.launch {
                            try {
                                onLogin(losgramId, code)
                            } catch (e: Exception) {
                                error = e.message ?: "Ошибка подключения к серверу."
                            } finally {
                                loading = false
                            }
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (loading) "Проверяем..." else "Войти", color = Color.White)
            }
            Spacer(Modifier.height(16.dp))
            Text("Без номера телефона. Код действует 5 минут.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun loginFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Blue,
    unfocusedBorderColor = Panel,
    focusedTextColor = White,
    unfocusedTextColor = White,
    focusedLabelColor = Blue,
    unfocusedLabelColor = Muted,
    cursorColor = Blue,
    focusedContainerColor = Panel,
    unfocusedContainerColor = Panel
)

@Composable
fun MessengerHome(displayName: String) {
    var selectedTab by remember { mutableStateOf("Чаты") }
    var search by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize(), color = Navy) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("LosGram", color = Blue, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("Привет, $displayName", color = Muted, fontSize = 12.sp)
                }
                Text("✎", color = White, fontSize = 25.sp, modifier = Modifier.padding(horizontal = 10.dp))
                Text("⋮", color = White, fontSize = 27.sp)
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                placeholder = { Text("Поиск", color = Muted) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Blue, unfocusedBorderColor = Panel,
                    focusedTextColor = White, unfocusedTextColor = White,
                    cursorColor = Blue, focusedContainerColor = Panel, unfocusedContainerColor = Panel
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf("Контакты", "Чаты", "Звонки").forEach { tab ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { selectedTab = tab }.padding(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Text(tab, color = if (selectedTab == tab) Blue else Muted,
                            fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal)
                        if (selectedTab == tab) {
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.width(28.dp).height(3.dp).clip(CircleShape).background(Blue))
                        }
                    }
                }
            }

            when (selectedTab) {
                "Чаты" -> {
                    val filtered = demoChats.filter {
                        it.name.contains(search, ignoreCase = true) || it.message.contains(search, ignoreCase = true)
                    }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filtered) { chat -> ChatRow(chat) }
                    }
                }
                "Контакты" -> EmptyState("Контакты", "Здесь появятся пользователи LosGram")
                else -> EmptyState("Звонки", "История звонков появится здесь")
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(Panel).padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text("☰  Меню", color = Muted)
                Text("⚙  Настройки", color = Muted)
                Text("👤  Профиль", color = Muted)
            }
        }
    }
}

@Composable
fun ChatRow(chat: Chat) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { }.padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                modifier = Modifier.size(54.dp).clip(CircleShape).background(avatarColor(chat.initials)),
                contentAlignment = Alignment.Center
            ) {
                Text(chat.initials, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
            if (chat.online) {
                Box(
                    Modifier.size(13.dp).clip(CircleShape).background(Color(0xFF4CAF50))
                        .padding(2.dp).clip(CircleShape).background(Navy)
                )
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(chat.name, color = White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(chat.message, color = Muted, fontSize = 13.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(chat.time, color = Muted, fontSize = 11.sp)
            if (chat.unread > 0) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.clip(CircleShape).background(Blue).padding(horizontal = 7.dp, vertical = 3.dp)) {
                    Text(chat.unread.toString(), color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = Muted)
    }
}

fun avatarColor(seed: String): Color {
    val palette = listOf(
        Color(0xFF527DA3), Color(0xFF9C6ADE), Color(0xFF4F9B8F),
        Color(0xFFB77955), Color(0xFF6D83B5)
    )
    return palette[(seed.hashCode().absoluteValue) % palette.size]
}

private val Int.absoluteValue: Int
    get() = if (this == Int.MIN_VALUE) 0 else kotlin.math.abs(this)
