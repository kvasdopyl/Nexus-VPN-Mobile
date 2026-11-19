package pro.nexusvpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ServersScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var data by remember { mutableStateOf(JSONObject()) }
    var delays by remember { mutableStateOf(JSONObject()) }
    var busy by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var editSub by remember { mutableStateOf<JSONObject?>(null) }
    var deleteSub by remember { mutableStateOf<String?>(null) }

    suspend fun load() { data = py("servers") }
    LaunchedEffect(Unit) { load() }

    fun run(label: String, block: suspend () -> Unit) {
        scope.launch {
            busy = label
            try { block() } finally { busy = "" }
            load()
        }
    }

    Page {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                run("Добавление…") {
                    val text = clipboardText(ctx)
                    if (text.isBlank()) {
                        state.toast("Буфер обмена пуст. Скопируйте ссылку на подписку или сервер.")
                    } else {
                        val r = py("add_text", text)
                        state.toast(r.optString("error").ifEmpty {
                            if (r.has("name")) "Подписка «${r.optString("name")}» добавлена" else "Добавлено серверов: ${r.optInt("added")}"
                        })
                    }
                }
            }) { Text("Вставить из буфера") }
            OutlinedButton({ adding = true }) { Text("Добавить…") }
            OutlinedButton({
                run("Обновление подписок…") {
                    py("update_subs", "[]").optJSONArray("messages").strings().forEach { state.toast(it) }
                }
            }) { Text("Обновить подписки") }
            OutlinedButton({
                run("Проверка задержки…") {
                    val r = py("test_delays")
                    if (r.has("error")) state.toast(r.getString("error")) else delays = r.getJSONObject("delays")
                }
            }) { Text("Проверить задержку") }
        }
        if (busy.isNotEmpty()) Muted(busy)

        val subs = data.optJSONArray("subs").objects()
        if (subs.isNotEmpty()) {
            Section("Подписки", state.help.optString("subscriptions")) {
                subs.forEach { s -> SubRow(s, onUpdate = {
                    run("Обновление…") {
                        py("update_subs", org.json.JSONArray(listOf(s.optString("name"))).toString())
                            .optJSONArray("messages").strings().forEach { state.toast(it) }
                    }
                }, onEdit = { editSub = s }, onDelete = { deleteSub = s.optString("name") }) }
            }
        }

        val nodes = data.optJSONArray("nodes").objects()
        Section("Серверы (${nodes.size})", state.help.optString("server")) {
            val auto = data.optBoolean("auto", true)
            Row(Modifier.fillMaxWidth().clickable { scope.launch { py("select_node", ""); load() } },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(auto, { scope.launch { py("select_node", ""); load() } })
                Column { Text("Авто"); Muted("самый быстрый сервер, при сбое — следующий") }
            }
            nodes.forEach { n ->
                HorizontalDivider(color = C.line)
                NodeRow(n, delays.optInt(n.optString("tag"), Int.MIN_VALUE), onSelect = {
                    scope.launch { py("select_node", n.optString("tag")); load() }
                }, onToggle = { on ->
                    scope.launch { py("toggle_node", n.optString("tag"), on); load() }
                }, onCopy = {
                    scope.launch {
                        copyText(ctx, py("node_link", n.optString("tag")).optString("link"))
                        state.toast("Ссылка скопирована")
                    }
                }, onDelete = {
                    scope.launch { py("remove_nodes", org.json.JSONArray(listOf(n.optString("tag"))).toString()); load() }
                })
            }
            if (nodes.isEmpty()) Muted("Серверов пока нет. Скопируйте ссылку на подписку и нажмите «Вставить из буфера».")
        }
    }

    if (adding) {
        TextDialog(
            "Добавить", hint = "Ссылка на подписку (https://…) или ссылки на серверы (vless://, vmess://, trojan://, " +
                "ss://, hysteria2://, tuic://, wireguard://) — по одной на строку.",
            multiline = true, confirm = "Добавить", onDismiss = { adding = false },
        ) { text ->
            adding = false
            run("Добавление…") {
                val r = py("add_text", text)
                state.toast(r.optString("error").ifEmpty {
                    if (r.has("name")) "Подписка «${r.optString("name")}» добавлена" else "Добавлено серверов: ${r.optInt("added")}"
                })
            }
        }
    }
    editSub?.let { s ->
        SubEditDialog(s, onDismiss = { editSub = null }) { name, url, enabled ->
            editSub = null
            run("Сохранение…") { py("edit_sub", s.optString("name"), name, url, enabled) }
        }
    }
    deleteSub?.let { name ->
        Confirm("Удалить подписку", "Удалить «$name» вместе с её серверами?", "Удалить", onDismiss = { deleteSub = null }) {
            run("Удаление…") { py("delete_sub", name) }
        }
    }
}

@Composable
private fun SubRow(s: JSONObject, onUpdate: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.optString("name"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = mutableListOf("серверов: ${s.optInt("count")}")
            val upd = s.optDouble("updated_at", 0.0)
            if (upd > 0) parts += "обновлено " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date((upd * 1000).toLong()))
            val total = s.optLong("total")
            if (total > 0) parts += "${bytes(s.optLong("upload") + s.optLong("download"))} из ${bytes(total)}"
            val exp = s.optLong("expire")
            if (exp > 0) parts += "до " + SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(exp * 1000))
            Muted(parts.joinToString(" · "))
            if (s.optString("error").isNotEmpty()) Text(s.optString("error"), color = C.red, fontSize = 12.sp)
            if (!s.optBoolean("enabled", true)) Muted("выключена")
        }
        Box {
            TextButton({ menu = true }) { Text("⋮", fontSize = 20.sp) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Обновить") }, { menu = false; onUpdate() })
                DropdownMenuItem({ Text("Изменить") }, { menu = false; onEdit() })
                DropdownMenuItem({ Text("Удалить", color = C.red) }, { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun NodeRow(
    n: JSONObject, delay: Int, onSelect: () -> Unit, onToggle: (Boolean) -> Unit, onCopy: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val ok = n.optBoolean("supported", true)
    Row(Modifier.fillMaxWidth().clickable(enabled = ok, onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(n.optBoolean("active"), onSelect, enabled = ok)
        Column(Modifier.weight(1f)) {
            Text(n.optString("title"), maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (ok) C.text else C.muted, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (n.optString("group").isNotEmpty()) Muted(n.optString("group"))
                when (n.optInt("voice")) {
                    2 -> Badge("голос по UDP", C.green)
                    1 -> Badge("UDP в TCP", C.amber)
                }
                if (delay != Int.MIN_VALUE) Badge(if (delay > 0) "$delay мс" else "нет ответа", if (delay > 0) C.accent else C.red)
            }
            if (!ok) Text(n.optString("reason"), color = C.amber, fontSize = 12.sp)
        }
        Switch(n.optBoolean("enabled"), onToggle, enabled = ok)
        Spacer(Modifier.width(4.dp))
        Box {
            TextButton({ menu = true }) { Text("⋮", fontSize = 20.sp) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Скопировать ссылку") }, { menu = false; onCopy() })
                DropdownMenuItem({ Text("Удалить", color = C.red) }, { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun SubEditDialog(s: JSONObject, onDismiss: () -> Unit, onSave: (String, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf(s.optString("name")) }
    var url by remember { mutableStateOf(s.optString("url")) }
    var enabled by remember { mutableStateOf(s.optBoolean("enabled", true)) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подписка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                androidx.compose.material3.OutlinedTextField(url, { url = it }, label = { Text("Ссылка") })
                SwitchRow("Подписка включена", enabled) { enabled = it }
            }
        },
        confirmButton = { TextButton({ onSave(name, url, enabled) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}
