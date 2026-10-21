package veil.app

// Группы: участники обмениваются сообщениями напрямую E2E-конвертами,
// реле не знает ни названия, ни состава группы (состав — в каждом конверте).

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class GroupInfo(val gid: String, val name: String, val members: List<String>)

fun loadGroups(): List<GroupInfo> {
    val raw = VeilApp.prefs.getString("groups", "{}") ?: "{}"
    val o = JSONObject(raw)
    return o.keys().asSequence().map { gid ->
        val g = o.getJSONObject(gid)
        GroupInfo(gid, g.getString("name"), (0 until g.getJSONArray("members").length())
            .map { g.getJSONArray("members").getString(it) })
    }.toList()
}

fun saveGroup(g: GroupInfo) {
    val o = JSONObject(VeilApp.prefs.getString("groups", "{}") ?: "{}")
    o.put(g.gid, JSONObject().put("name", g.name).put("members", JSONArray(g.members)))
    VeilApp.prefs.edit().putString("groups", o.toString()).apply()
}

@Composable
fun NewGroupScreen(onDone: () -> Unit) {
    val t = VeilThemes.current
    var name by remember { mutableStateOf("") }
    var member by remember { mutableStateOf("") }
    var members by remember { mutableStateOf(listOf<String>()) }
    Column(Modifier.fillMaxSize().background(t.bg).padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Новая группа", color = t.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = name, onValueChange = { name = it },
            label = { Text("Название группы") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = member, onValueChange = { member = it },
            label = { Text("Юзернейм участника") },
            trailingIcon = {
                TextButton(onClick = {
                    val m = member.lowercase().trim()
                    if (m.isNotBlank() && m !in members) members = members + m
                    member = ""
                }) { Text("+", color = t.accent) }
            }, modifier = Modifier.fillMaxWidth())
        members.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(m); Text("  $m", color = t.text); Spacer(Modifier.weight(1f))
                TextButton(onClick = { members = members - m }) { Text("✕", color = t.textDim) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val gid = veil.core.Group.id()
                saveGroup(GroupInfo(gid, name.ifBlank { "Группа" }, members))
                onDone()
            },
            enabled = members.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Создать")
        }
        Text("Название и состав группы известны только её участникам — они шифруются вместе с сообщениями.",
            color = t.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
fun GroupChatScreen(gid: String, onBack: () -> Unit) {
    val t = VeilThemes.current
    val info = remember { loadGroups().firstOrNull { it.gid == gid } ?: GroupInfo(gid, "Группа", emptyList()) }
    var messages by remember { mutableStateOf(listOf<UiMsg>()) }
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun sendToAll(json: String) = scope.launch {
        members@ for (m in info.members) {
            val ok = VeilApp.api()?.send(VeilApp.resolveId(m), json.toByteArray()) ?: false
            if (!ok) { messages = messages + listOf(UiMsg("in", "⚠ доставка не дошла: $m")); break@members }
        }
    }

    Column(Modifier.fillMaxSize().background(t.bg)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(t.surface).padding(10.dp)) {
            TextButton(onClick = onBack) { Text("←", color = t.accent, fontSize = 22.sp) }
            Avatar(info.name)
            Column(Modifier.padding(start = 10.dp)) {
                Text(info.name, color = t.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("${info.members.size} участников · E2E", color = t.accent, fontSize = 11.sp)
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            items(messages.size) { i ->
                val m = messages[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (m.dir == "out") Arrangement.End else Arrangement.Start) {
                    Box(Modifier.widthIn(max = 300.dp).background(
                        if (m.dir == "out") t.bubbleOut else t.surface2,
                        RoundedCornerShape(14.dp)).padding(12.dp)) {
                        Text(m.text, color = if (m.dir == "out") t.bubbleOutText else t.text)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(t.surface).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("Сообщение в группу…", color = t.textDim) },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp))
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        messages = messages + listOf(UiMsg("out", input))
                        val json = veil.core.Group.msg(gid, input)
                        input = ""
                        sendToAll(json)
                    }
                },
                shape = CircleShape) { Text("➤") }
        }
    }
}
