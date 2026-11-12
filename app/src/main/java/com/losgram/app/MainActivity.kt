package com.losgram.app

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                    Text("Твой мессенджер", color = Muted, fontSize = 12.sp)
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
                    focusedBorderColor = Blue,
                    unfocusedBorderColor = Panel,
                    focusedTextColor = White,
                    unfocusedTextColor = White,
                    cursorColor = Blue,
                    focusedContainerColor = Panel,
                    unfocusedContainerColor = Panel
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
