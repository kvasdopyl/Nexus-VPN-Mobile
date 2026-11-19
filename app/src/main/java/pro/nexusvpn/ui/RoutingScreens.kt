package pro.nexusvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val TITLES = mapOf("proxy" to "Через VPN", "direct" to "Напрямую")

private fun entry(kind: String, value: String) = JSONObject().put("kind", kind).put("value", value)

// ======================================================================= «Своя маршрутизация»
@Composable
fun CustomScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var data by remember { mutableStateOf(JSONObject()) }
    var tab by remember { mutableIntStateOf(0) }
    var addText by remember { mutableStateOf(false) }
    var addApps by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    val verdict = if (tab == 0) "proxy" else "direct"
    val other = if (tab == 0) "direct" else "proxy"

    LaunchedEffect(Unit) { data = py("routing") }
    fun apply(block: suspend () -> JSONObject) {
        scope.launch {
            val r = block()
            if (r.has("error")) state.toast(r.getString("error")) else data = r
            r.optJSONArray("errors")?.strings()?.takeIf { it.isNotEmpty() }?.let { state.toast("Не распознано: ${it.joinToString(", ")}") }
        }
    }

    Page {
        Muted("Что идёт через VPN, а что напрямую. Всё применяется сразу — без переподключения.")
        Section("Сервисы через VPN — нажмите, чтобы включить или выключить", state.help.optString("mode_custom")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                data.optJSONArray("services").objects().forEach { s ->
                    val on = s.optBoolean("on")
                    FilterChip(on, { apply { py("routing_service", s.optString("name"), !on) } },
                        label = { Text((if (on) "✓ " else "") + s.optString("name")) })
                }
            }
            SwitchRow(
                "Также использовать готовые базы (как в умной маршрутизации)", data.optBoolean("use_presets"),
                state.help.optString("custom_use_presets"),
            ) { on -> apply { py("routing_use_presets", on) } }
        }

        val proxy = data.optJSONArray("proxy").objects()
        val direct = data.optJSONArray("direct").objects()
        TabRow(tab, containerColor = C.bg) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Через VPN (${proxy.size})") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Напрямую (${direct.size})") })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ addText = true }) { Text("+ Сайты / IP") }
            Button({ addApps = true }) { Text("+ Приложения") }
            OutlinedButton({
                scope.launch {
                    copyText(ctx, py("routing_export", verdict).optString("text"))
                    state.toast("Список скопирован")
                }
            }) { Text("Скопировать список") }
            OutlinedButton({ clear = true }) { Text("Очистить") }
        }
        val list = if (tab == 0) proxy else direct
        Section {
            if (list.isEmpty()) Muted("Список пуст.")
            list.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(color = C.line)
                val kind = e.optString("kind")
                val value = e.optString("value")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (kind == "process") appLabel(ctx, value) else value, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted(KIND_TEXT[kind] ?: kind) 
                        if (kind == "process" && appLabel(ctx, value) != value) Muted(value)
                    }
                    TextButton({ apply { py("routing_change", other, JSONArray(listOf(entry(kind, value))).toString(), "[]") } }) {
                        Text("→ ${TITLES[other]}", fontSize = 12.sp)
                    }
                    TextButton({ apply { py("routing_change", verdict, "[]", JSONArray(listOf(entry(kind, value))).toString()) } }) {
                        Text("✕", color = C.red)
                    }
                }
            }
        }
    }

    if (addText) {
        TextDialog(
            "Добавить в «${TITLES[verdict]}»",
            hint = "Адреса сайтов или IP — по одному на строку. Ссылки https://site.com/… сами превратятся в site.com.",
            multiline = true, confirm = "Добавить", onDismiss = { addText = false },
        ) { text ->
            addText = false
            apply { py("routing_add_text", verdict, text) }
        }
    }
    if (addApps) {
        val listed = data.optJSONArray(verdict).objects().filter { it.optString("kind") == "process" }.map { it.optString("value") }.toSet()
        AppPicker("Приложения: «${TITLES[verdict]}»", listed, onDismiss = { addApps = false }) { apps ->
            addApps = false
            val chosen = apps.map { it.pkg }.toSet()
            val add = JSONArray((chosen - listed).map { entry("process", it) })
            val remove = JSONArray((listed - chosen).map { entry("process", it) })
            apply { py("routing_change", verdict, add.toString(), remove.toString()) }
        }
    }
    if (clear) {
        val cur = data.optJSONArray(verdict) ?: JSONArray()
        Confirm("Очистить", "Убрать все записи (${cur.length()}) из «${TITLES[verdict]}»?", "Очистить", onDismiss = { clear = false }) {
            apply { py("routing_change", verdict, "[]", cur.toString()) }
        }
    }
}

// ======================================================================= «Игры»
private val GAME_MODES = listOf("off" to "Выкл", "direct" to "Напрямую", "proxy" to "Через VPN")

@Composable
fun GamesScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf(listOf<JSONObject>()) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { games = py("games").optJSONArray("games").objects() }

    Page {
        Muted("Игры можно пустить напрямую (минимальный пинг, без VPN) или через VPN (если сервер игры заблокирован). " +
            "Выбор действует в любом режиме маршрутизации.")
        Button({ picking = true }) { Text("+ Добавить свою игру") }
        Section {
            games.forEachIndexed { i, g ->
                if (i > 0) HorizontalDivider(color = C.line)
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.optString("name"), fontWeight = FontWeight.SemiBold)
                            if (g.optString("note").isNotEmpty()) Muted(g.optString("note"))
                        }
                        if (g.optBoolean("custom")) {
                            TextButton({ scope.launch { games = py("remove_game", g.optString("key")).optJSONArray("games").objects() } }) {
                                Text("Удалить", color = C.red)
                            }
                        }
                    }
                    Segmented(GAME_MODES, g.optString("mode", "off")) { m ->
                        scope.launch { games = py("set_game", g.optString("key"), m).optJSONArray("games").objects() }
                    }
                }
            }
        }
    }
    if (picking) {
        AppPicker("Выберите игру", emptySet(), single = true, onDismiss = { picking = false }) { apps ->
            picking = false
            apps.firstOrNull()?.let { a ->
                scope.launch {
                    games = py("add_game", a.label, a.pkg).optJSONArray("games").objects()
                    state.toast("«${a.label}» добавлена и идёт напрямую")
                }
            }
        }
    }
}

@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(C.line, RoundedCornerShape(10.dp)).padding(3.dp)) {
        options.forEach { (key, title) ->
            val on = key == selected
            Box(
                Modifier.weight(1f).background(if (on) C.accent else C.line, RoundedCornerShape(8.dp))
                    .clickable { onSelect(key) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(title, fontSize = 13.sp, textAlign = TextAlign.Center,
                    color = if (on) androidx.compose.ui.graphics.Color(0xFF04131F) else C.text)
            }
        }
    }
}

// ======================================================================= «Discord»
@Composable
fun DiscordScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf(JSONObject()) }
    LaunchedEffect(Unit) {
        while (true) {
            d = py("discord")
            delay(3000)
        }
    }
    Page {
        Section("Discord через VPN", state.help.optString("discord")) {
            SwitchRow(
                if (d.optBoolean("enabled")) "Включено" else "Выключено", d.optBoolean("enabled"),
                subtitle = "Сообщения, голос и видео Discord идут через VPN",
            ) { on -> scope.launch { d = py("discord_enable", on) } }
        }
        Section("Сервер для голоса", state.help.optString("discord_node")) {
            val nodes = d.optJSONArray("nodes").objects()
            Dropdown(
                "", listOf("" to "Авто — лучший сервер с UDP") + nodes.map {
                    it.optString("tag") to it.optString("title") + if (it.optInt("voice") == 2) "  · голос по UDP" else ""
                }, d.optString("chosen"),
            ) { tag -> scope.launch { d = py("discord_node", tag, false) } }
            if (d.optString("current").isNotEmpty()) Muted("Сейчас голос идёт через: ${d.optString("current")}")
            OutlinedButton({ scope.launch { d = py("discord_node", d.optString("chosen"), true); state.toast("Звонок переподключается…") } }) {
                Text("Переподключить звонок сейчас")
            }
            Muted("Смена сервера не обрывает текущий звонок — он перейдёт на новый сервер при следующем подключении. " +
                "Кнопка выше переносит звонок сразу (Discord переподключит голос за 1–2 секунды).")
        }
        Section("Если голос заикается или пропадает") {
            Text(html(
                "1. Выберите выше сервер с пометкой <b>«голос по UDP»</b> (Hysteria2, TUIC, WireGuard, Shadowsocks). " +
                    "Через VLESS и Trojan голос идёт внутри TCP: одна потерянная посылка останавливает звук.<br>" +
                    "2. У голосового канала оставьте регион <b>«Автоматический выбор»</b>.<br>" +
                    "3. Если пропадает весь интернет, а не только Discord, — смените основной сервер на главной.<br><br>" +
                    "Программа сама следит за звонком: если сервер голоса перестал отвечать, звонок переедет на " +
                    "работающий сервер за пару секунд.",
            ), fontSize = 13.sp)
        }
        val conns = d.optJSONArray("connections").objects()
        Section("Соединения Discord (${conns.size})") {
            if (conns.isEmpty()) Muted("Discord сейчас ничего не передаёт.")
            conns.take(30).forEach { c ->
                Row {
                    Text(c.optString("host"), fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(6.dp))
                    Badge(c.optString("network").uppercase(), if (c.optString("network") == "udp") C.green else C.accent)
                }
            }
        }
    }
}
