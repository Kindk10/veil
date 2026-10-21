package veil.app

// Каркас Android-приложения VEIL (Jetpack Compose). Экраны:
// PIN → список чатов → чат 1:1 → настройки (юзернейм, паническое стирание).
// Вся криптография и сеть — в модуле veil.core; UI к ключам напрямую не прикасается.

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import veil.core.SecureStorage
import veil.core.Session
import veil.core.VeilApi
import veil.net.Transport as NetTransport

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VeilApp.context = applicationContext
        // Tor-only по умолчанию: адрес onion-сервиса задаётся после деплоя реле.
        NetTransport.torOnly = false // реле пока доступно только по TLS (onion-режим реактивируем)
        // Transport.baseUrl = "http://<onion-адрес>.onion"  // из /var/lib/tor/veil/hostname

        setContent { VeilTheme { Root() } }
    }
}

@Composable
fun VeilTheme(content: @Composable () -> Unit) {
    // Тёмная анонимная стилистика: почти чёрный фон, приглушённый зелёный акцент.
    val colors = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF4AE28B),
        onPrimary = androidx.compose.ui.graphics.Color(0xFF06130B),
        background = androidx.compose.ui.graphics.Color(0xFF0B0F0D),
        onBackground = androidx.compose.ui.graphics.Color(0xFFD7E2DC),
        surface = androidx.compose.ui.graphics.Color(0xFF121A16),
        onSurface = androidx.compose.ui.graphics.Color(0xFFD7E2DC),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1A2420),
        error = androidx.compose.ui.graphics.Color(0xFFE2574A)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun Root() {
    var unlocked by remember { mutableStateOf(false) }
    if (!unlocked) UnlockScreen { unlocked = true } else ChatsScreen()
}

@Composable
fun UnlockScreen(onUnlock: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("VEIL", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = pin, onValueChange = { pin = it },
            label = { Text("PIN") }, isError = error,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )
        if (error) Text("Неверный PIN. Данные останутся зашифрованными.", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            val store = SecureStorage.open(VeilApp.context!!, pin.toCharArray())
            if (store != null) { VeilApp.sessionStore = store; onUnlock() } else error = true
        }) { Text("Разблокировать") }
    }
}

@Composable
fun ChatsScreen() {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<Pair<String, String>>()) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Мой юзернейм", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = username, onValueChange = { username = it },
            label = { Text("только a-z0-9_ , 3-32") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.padding(vertical = 8.dp)) {
            Button(onClick = { scope.launch {
                VeilApp.api()?.setUsername(username)
            } }) { Text("Занять") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch {
                val r = VeilApp.api()?.lookup(username)
                if (r != null) messages = listOf(r.idHash to "")
            } }) { Text("Найти") }
        }
        Text("Входящие (после деплоя реле появятся конверты)", modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(messages) { m -> Text("${m.first}: ${m.second}") }
        }
        Text("Шумовой трафик", modifier = Modifier.padding(top = 8.dp))
        val noiseInterval by remember { mutableStateOf(30) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(10, 30, 60).forEach { sec ->
                OutlinedButton(
                    onClick = { veil.net.CoverTraffic.setInterval(sec) },
                    modifier = Modifier.padding(end = 4.dp)
                ) { Text(if (sec == noiseInterval) "$sec сек ●" else "$sec сек") }
            }
        }
        Text(if (noiseInterval == 10) "макс. скрытность (батарея ↓)" else "пресет $noiseInterval сек")
        // ПАНИЧЕСКОЕ СТИРАНИЕ: безвозвратно удаляет аккаунт и данные.
        Button(onClick = { scope.launch {
            VeilApp.api()?.eraseAccount()
            android.os.Process.killProcess(android.os.Process.myPid())
        } }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Text("Удалить аккаунт и все данные")
        }
    }
}

object VeilApp {
    @Volatile var context: android.content.Context? = null
    @Volatile var sessionStore: SecureStorage? = null
    @Volatile var session: Session? = null

    fun api(): VeilApi? {
        val s = sessionStore ?: return null
        // ID и подписант создаются в фазе инициализации (Identity.createNew),
        // здесь используется уже существующая идентичность.
        return veilInit?.apiFor(s)
    }
    @Volatile var veilInit: VeilInit? = null
}

// Точка инициализации идентичности — заполняется при первом запуске
// (createNew) либо при загрузке существующих ключей (existing).
interface VeilInit {
    fun apiFor(store: SecureStorage): VeilApi
}
