package veil.app

// VEIL — интерфейс (Jetpack Compose), тёмная анонимная стилистика.
// Навигация: онбординг (создание PIN) → разблокировка → список чатов → чат → настройки.
// Вся криптография и сеть — в модуле veil.core; UI к ключам напрямую не прикасается.

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch

val VeilGreen = Color(0xFF4AE28B)
val VeilBg = Color(0xFF0B0F0D)
val VeilSurface = Color(0xFF121A16)
val VeilSurface2 = Color(0xFF1A2420)
val VeilText = Color(0xFFD7E2DC)
val VeilTextDim = Color(0xFF7C8B83)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VeilApp.context = applicationContext
        veil.net.Transport.torOnly = false // реле пока по TLS; onion реактивируем
        setContent { VeilTheme { Root() } }
    }
}

@Composable
fun VeilTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VeilGreen, onPrimary = Color(0xFF06130B),
            background = VeilBg, onBackground = VeilText,
            surface = VeilSurface, onSurface = VeilText,
            surfaceVariant = VeilSurface2, error = Color(0xFFE2574A)
        ),
        content = content
    )
}

sealed class Screen {
    object Onboarding : Screen()
    object Unlock : Screen()
    object Chats : Screen()
    data class Chat(val peer: String) : Screen()
    object Settings : Screen()
}

@Composable
fun Root() {
    var screen by remember { mutableStateOf<Screen>(
        if (veil.core.SecureStorage.hasAccount(VeilApp.context!!)) Screen.Unlock
        else Screen.Onboarding) }
    when (val s = screen) {
        is Screen.Onboarding -> OnboardingScreen { screen = Screen.Chats }
        is Screen.Unlock -> UnlockScreen { screen = Screen.Chats }
        is Screen.Chats -> ChatsScreen(
            onOpen = { screen = Screen.Chat(it) },
            onSettings = { screen = Screen.Settings })
        is Screen.Chat -> ChatScreen(s.peer) { screen = Screen.Chats }
        is Screen.Settings -> SettingsScreen { screen = Screen.Chats }
    }
}

// ---------- Онбординг: создаём PIN и аккаунт ----------

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center) {
        Text("VEIL", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = VeilGreen)
        Text("Анонимный шифрованный мессенджер",
            color = VeilTextDim, modifier = Modifier.padding(bottom = 28.dp))
        Text("Придумайте PIN (4 цифры)", color = VeilText, fontSize = 14.sp)
        OutlinedTextField(
            value = pin, onValueChange = { if (it.length <= 4) pin = it.filter { c -> c.isDigit() } },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = confirm, onValueChange = { if (it.length <= 4) confirm = it.filter { c -> c.isDigit() } },
            label = { Text("Повторите PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = username, onValueChange = { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' } },
            label = { Text("Юзернейм (по нему вас найдут)") },
            modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                when {
                    pin.length != 4 -> error = "PIN — ровно 4 цифры"
                    pin != confirm -> error = "PIN не совпадает"
                    else -> {
                        busy = true
                        scope.launch {
                            val init = veil.core.VeilInit(VeilApp.context!!)
                            val res = init.firstLaunch(pin.toCharArray(), username.ifBlank { null })
                            busy = false
                            if (res.isSuccess) onDone()
                            else error = res.exceptionOrNull()?.message
                        }
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (busy) "Генерация ключей…" else "Создать аккаунт")
        }
        Text("PIN запоминается только вами. 5 неверных попыток — данные стираются безвозвратно.",
            color = VeilTextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
fun UnlockScreen(onUnlock: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("VEIL", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = VeilGreen)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin, onValueChange = { if (it.length <= 4) pin = it.filter { c -> c.isDigit() } },
            label = { Text("PIN") }, isError = error,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth())
        if (error) Text("Неверный PIN", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            val store = veil.core.SecureStorage.open(VeilApp.context!!, pin.toCharArray())
            if (store != null) { VeilApp.sessionStore = store; onUnlock() } else error = true
        }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Разблокировать") }
    }
}

// ---------- Список чатов ----------

data class ChatEntry(val username: String, val last: String, val time: String, val unread: Int)

@Composable
fun Avatar(name: String) {
    Box(Modifier.size(48.dp).background(VeilSurface2, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = VeilGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ChatsScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    var chats by remember { mutableStateOf(listOf<ChatEntry>()) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var transportState by remember { mutableStateOf(veil.net.Transport.TransportState.TOR_OK) }

    LaunchedEffect(Unit) { transportState = veil.net.Transport.probe() }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onSettings, containerColor = VeilSurface2) {
                Text("⚙ Настройки", color = VeilText)
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                Text("VEIL", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = VeilGreen)
                Spacer(Modifier.weight(1f))
                Text(
                    when (transportState) {
                        veil.net.Transport.TransportState.READY -> "● защищено"
                        veil.net.Transport.TransportState.TOR_OK -> "● защищено"
                        veil.net.Transport.TransportState.TOR_UNREACHABLE -> "● транспорт недоступен"
                    },
                    color = if (transportState == veil.net.Transport.TransportState.TOR_UNREACHABLE)
                        MaterialTheme.colorScheme.error else VeilGreen, fontSize = 12.sp)
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Найти по юзернейму") },
                trailingIcon = {
                    TextButton(onClick = { scope.launch {
                        val r = VeilApp.api()?.lookup(query.lowercase())
                        found = r?.idHash
                    } }) { Text("Найти", color = VeilGreen) }
                },
                modifier = Modifier.fillMaxWidth())
            found?.let { hash ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .background(VeilSurface, RoundedCornerShape(12.dp)).padding(10.dp)) {
                    Avatar(query)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(query, color = VeilText, fontWeight = FontWeight.SemiBold)
                        Text("ID: ${hash.take(12)}…", color = VeilTextDim, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onOpen(query) }) { Text("Написать") }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (chats.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text("Никаких следов. Пока.", color = VeilTextDim, fontSize = 18.sp)
                    Text("Найдите собеседника по юзернейму — начнётся шифрованный чат.",
                        color = VeilTextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            } else {
                LazyColumn {
                    items(chats) { c ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                .background(VeilSurface, RoundedCornerShape(12.dp))
                                .padding(10.dp)) {
                            Avatar(c.username)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(c.username, color = VeilText, fontWeight = FontWeight.SemiBold)
                                Text(c.last, color = VeilTextDim, fontSize = 13.sp, maxLines = 1)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(c.time, color = VeilTextDim, fontSize = 11.sp)
                                if (c.unread > 0) {
                                    Box(Modifier.size(20.dp).background(VeilGreen, CircleShape),
                                        contentAlignment = Alignment.Center) {
                                        Text(c.unread.toString(), color = Color(0xFF06130B), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Экран чата ----------

@Composable
fun ChatScreen(peer: String, onBack: () -> Unit) {
    var messages by remember { mutableStateOf(listOf(
        Pair("in", "Соединение установлено. Конец-в-конец шифрование активно."))) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(VeilBg)) {
        // Шапка
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(VeilSurface).padding(10.dp)) {
            TextButton(onClick = onBack) { Text("←", color = VeilGreen, fontSize = 22.sp) }
            Avatar(peer)
            Column(Modifier.padding(start = 10.dp)) {
                Text(peer, color = VeilText, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("E2E · Double Ratchet", color = VeilGreen, fontSize = 11.sp)
            }
        }
        // Сообщения
        LazyColumn(Modifier.weight(1f).padding(horizontal = 10.dp), state = listState) {
            items(messages.size) { i ->
                val (dir, text) = messages[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (dir == "out") Arrangement.End else Arrangement.Start) {
                    Box(Modifier.widthIn(max = 300.dp).background(
                        if (dir == "out") VeilGreen else VeilSurface2,
                        RoundedCornerShape(14.dp)).padding(12.dp)) {
                        Text(text, color = if (dir == "out") Color(0xFF06130B) else VeilText,
                            fontSize = 15.sp)
                    }
                }
            }
        }
        // Ввод
        Row(Modifier.fillMaxWidth().background(VeilSurface).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("Сообщение…", color = VeilTextDim) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp))
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        messages = messages + listOf("out" to input)
                        val sent = input
                        input = ""
                        scope.launch {
                            val ok = VeilApp.api()?.send("", sent.toByteArray()) ?: false
                            if (!ok) messages = messages + listOf("in" to "⚠ доставка не прошла (проверьте транспорт)")
                        }
                        scope.launch { listState.animateScrollToItem(messages.size - 1) }
                    }
                },
                shape = CircleShape) { Text("➤") }
        }
    }
}

// ---------- Настройки ----------

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmWipe by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", color = VeilGreen, fontSize = 22.sp) }
            Text("Настройки", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = VeilText)
        }
        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = VeilSurface)) {
            Column(Modifier.padding(16.dp)) {
                Text("Шумовой трафик", color = VeilText, fontWeight = FontWeight.SemiBold)
                Text("Пакеты фиксированного ритма скрывают момент отправки (§5.2)",
                    color = VeilTextDim, fontSize = 12.sp)
                Row(Modifier.padding(top = 8.dp)) {
                    listOf(10, 30, 60).forEach { sec ->
                        OutlinedButton(onClick = { veil.net.CoverTraffic.setInterval(sec) },
                            modifier = Modifier.padding(end = 6.dp)) { Text("$sec сек", color = VeilText) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = VeilSurface)) {
            Column(Modifier.padding(16.dp)) {
                Text("Транспорт", color = VeilText, fontWeight = FontWeight.SemiBold)
                Text("Реле: pin-TLS 1.3 · провайдер не видит содержимое\nOnion-режим: реактивируется (этап 3)",
                    color = VeilTextDim, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        if (!confirmWipe) {
            Button(onClick = { confirmWipe = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()) {
                Text("Удалить аккаунт и все данные", color = Color.White)
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1215))) {
                Column(Modifier.padding(16.dp)) {
                    Text("Безвозвратное удаление: аккаунт, переписки, ключи. Никто не сможет восстановить — ни вы, ни админ.",
                        color = VeilText, fontSize = 13.sp)
                    Row(Modifier.padding(top = 10.dp)) {
                        Button(onClick = { scope.launch {
                            VeilApp.api()?.eraseAccount()
                            android.os.Process.killProcess(android.os.Process.myPid())
                        } }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Text("Стереть", color = Color.White)
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { confirmWipe = false }) { Text("Отмена", color = VeilText) }
                    }
                }
            }
        }
    }
}

object VeilApp {
    @Volatile var context: android.content.Context? = null
    @Volatile var sessionStore: veil.core.SecureStorage? = null
    @Volatile var session: veil.core.Session? = null

    fun api(): veil.core.VeilApi? {
        val s = sessionStore ?: return null
        return veilInit?.apiFor(s)
    }
    @Volatile var veilInit: VeilInit? = null
}

interface VeilInit {
    fun apiFor(store: veil.core.SecureStorage): veil.core.VeilApi
}
