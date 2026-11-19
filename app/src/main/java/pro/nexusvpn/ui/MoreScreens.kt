package pro.nexusvpn.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val VERDICT = mapOf("proxy" to "через VPN", "direct" to "напрямую")

// ======================================================================= «Умная маршрутизация»
@Composable
fun SmartScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var data by remember { mutableStateOf(JSONObject()) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<JSONObject?>(null) }
    var testing by remember { mutableStateOf(false) }
    var clearAll by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(mapOf<String, Boolean>()) }
    LaunchedEffect(Unit) {
        data = py("smart")
        presets = data.optJSONArray("presets").objects().associate { it.optString("key") to it.optBoolean("on") }
    }

    Page {
        Muted("Что программа узнала сама. Ваши правила («Своя маршрутизация») всегда важнее автоматических.")
        TabRow(tab, containerColor = C.bg) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Изученное") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Готовые базы") })
        }
        if (tab == 0) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Поиск по сайту / приложению…") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("" to "Все", "proxy" to "Через VPN", "direct" to "Напрямую", "user" to "Мои", "auto" to "Авто").forEach { (k, t) ->
                    FilterChip(filter == k, { filter = k }, label = { Text(t) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ testing = true }) { Text("Проверить сайт…") }
                OutlinedButton({ clearAll = true }) { Text("Забыть всё автоматическое") }
            }
            val q = query.trim().lowercase()
            val rows = data.optJSONArray("rules").objects().filter { r ->
                (filter.isEmpty() || r.optString("verdict") == filter || r.optString("source") == filter) &&
                    (q.isEmpty() || q in r.optString("value").lowercase() || q in r.optString("process").lowercase())
            }
            Section("${rows.size} записей", state.help.optString("learned")) {
                rows.take(400).forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = C.line)
                    Row(Modifier.fillMaxWidth().clickable { picked = r }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val kind = r.optString("kind")
                            val value = r.optString("value")
                            Text(if (kind == "process") appLabel(ctx, value) else value, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted(listOf(KIND_TEXT[kind] ?: kind, if (r.optString("source") == "user") "мои" else "авто",
                                r.optString("process"), r.optString("reason")).filter { it.isNotEmpty() }.joinToString(" · "))
                        }
                        Badge(VERDICT[r.optString("verdict")] ?: "", if (r.optString("verdict") == "proxy") C.accent else C.green)
                    }
                }
                if (rows.isEmpty()) Muted("Пока ничего. Включите VPN в режиме «Умная» и пользуйтесь телефоном как обычно.")
            }
        } else {
            Section("Готовые базы (обновляются автоматически раз в сутки)", state.help.optString("presets")) {
                data.optJSONArray("presets").objects().forEach { p ->
                    val key = p.optString("key")
                    Row(Modifier.fillMaxWidth().clickable { presets = presets + (key to !(presets[key] ?: false)) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(presets[key] ?: false, { presets = presets + (key to it) })
                        Text(p.optString("title"), fontSize = 14.sp)
                    }
                }
                Button({
                    scope.launch {
                        data = py("set_presets", JSONObject(presets as Map<*, *>).toString())
                        state.toast("Базы применены, загрузка новых баз идёт в фоне")
                    }
                }) { Text("Применить") }
            }
        }
    }

    picked?.let { r ->
        val kind = r.optString("kind")
        val value = r.optString("value")
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(value) },
            text = { Muted("Сейчас: ${VERDICT[r.optString("verdict")]}") },
            confirmButton = {
                Column {
                    TextButton({ picked = null; scope.launch { data = py("smart_set", kind, value, "proxy") } }) { Text("→ Всегда через VPN") }
                    TextButton({ picked = null; scope.launch { data = py("smart_set", kind, value, "direct") } }) { Text("→ Всегда напрямую") }
                    TextButton({ picked = null; scope.launch { data = py("smart_forget", kind, value) } }) {
                        Text("Забыть (изучить заново)", color = C.red)
                    }
                }
            },
        )
    }
    if (testing) {
        TextDialog("Проверить сайт", hint = "Программа проверит, открывается ли сайт без VPN, и запомнит результат.",
            onDismiss = { testing = false }, confirm = "Проверить") { host ->
            testing = false
            scope.launch {
                val r = py("smart_test", host)
                state.toast(r.optString("error").ifEmpty { "Проверка запущена — результат появится в списке" })
                delay(4000)
                data = py("smart")
            }
        }
    }
    if (clearAll) {
        Confirm("Забыть всё автоматическое", "Программа начнёт изучать заново. Ваши правила останутся.", "Забыть",
            onDismiss = { clearAll = false }) { scope.launch { data = py("smart_clear_auto") } }
    }
}

// ======================================================================= «Соединения»
@Composable
fun ConnectionsScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var conns by remember { mutableStateOf(listOf<JSONObject>()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            conns = py("connections").optJSONArray("connections").objects()
            delay(2000)
        }
    }
    Page {
        OutlinedTextField(query, { query = it }, placeholder = { Text("Поиск…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val q = query.trim().lowercase()
        val shown = conns.filter { q.isEmpty() || q in it.optString("host").lowercase() || q in it.optString("app").lowercase() }
        Muted("Всего: ${shown.size} · через VPN: ${shown.count { it.optJSONArray("chains").strings().firstOrNull() != "direct" }}. " +
            "Нажмите на соединение, чтобы закрыть его.")
        Section {
            if (state.status.optString("state") != "running") Muted("VPN выключен.")
            shown.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(color = C.line)
                val chain = c.optJSONArray("chains").strings()
                val vpn = chain.firstOrNull() != "direct"
                Row(Modifier.fillMaxWidth().clickable {
                    scope.launch { conns = py("close_connection", c.optString("id")).optJSONArray("connections").objects() }
                }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${c.optString("host")}:${c.optString("port")}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        val app = c.optString("app")
                        Muted(listOf(if (app.isNotEmpty()) appLabel(ctx, app) else "", c.optString("network").uppercase(),
                            "↑${bytes(c.optLong("up"))} ↓${bytes(c.optLong("down"))}").filter { it.isNotEmpty() }.joinToString(" · "))
                    }
                    Badge(if (vpn) chain.reversed().joinToString(" → ") else "напрямую", if (vpn) C.accent else C.green)
                }
            }
        }
    }
}

// ======================================================================= «Обновления»
@Composable
fun UpdatesScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var info by remember { mutableStateOf(JSONObject()) }
    var update by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        info = py("updates")
        update = info.optJSONObject("update")
    }
    Page {
        Section("Приложение", state.help.optString("app_update")) {
            Text("Nexus VPN Mobile ${info.optString("version_text")}")
            val u = update
            if (u != null && u.optBoolean("newer")) {
                Text("Доступна версия ${u.optString("name")}", color = C.accent, fontWeight = FontWeight.SemiBold)
                if (u.optString("notes").isNotEmpty()) Muted(u.optString("notes").take(600))
                Button({
                    scope.launch {
                        busy = "Скачивание…"
                        val r = py("download_update")
                        busy = ""
                        if (r.has("error")) {
                            state.toast(r.getString("error"))
                        } else {
                            installApk(ctx, File(r.getString("path")))
                        }
                    }
                }) { Text("Скачать и установить") }
            } else if (u != null) {
                Muted("Установлена последняя версия")
            }
            OutlinedButton({
                scope.launch {
                    busy = "Проверка…"
                    val r = py("check_update")
                    busy = ""
                    if (r.has("error")) state.toast(r.getString("error")) else update = r
                }
            }) { Text("Проверить обновления") }
            if (busy.isNotEmpty()) Muted(busy)
        }
        Section("Ядро") {
            Text("sing-box ${Libbox.version()}")
            Muted("Ядро встроено в приложение и обновляется вместе с ним.")
        }
        Section("Базы правил") {
            val age = info.opt("rules_age_days")
            Muted(if (age == null || age == JSONObject.NULL) "Ещё не загружены" else "Обновлены $age дн. назад")
            OutlinedButton({
                scope.launch {
                    busy = "Загрузка баз…"
                    val r = py("update_rules")
                    busy = ""
                    val failed = r.optJSONObject("failed")
                    state.toast(if (failed == null || failed.length() == 0) "Базы обновлены: ${r.optInt("ok")}" else "Не загрузились: ${failed.keys().asSequence().joinToString()}")
                    info = py("updates")
                }
            }) { Text("Обновить базы правил") }
        }
    }
}

fun installApk(ctx: android.content.Context, apk: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ======================================================================= «Настройки»
@Composable
fun SettingsScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var sections by remember { mutableStateOf(listOf<JSONObject>()) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var apps by remember { mutableStateOf<JSONObject?>(null) }
    var reset by remember { mutableStateOf(false) }
    suspend fun load() { sections = py("settings_spec").optJSONArray("sections").objects() }
    LaunchedEffect(Unit) { load() }
    fun save(key: String, value: Any) {
        scope.launch {
            val r = py("set_setting", key, JSONArray(listOf(value)).toString().removePrefix("[").removeSuffix("]"))
            if (r.has("error")) state.toast(r.getString("error"))
            load()
        }
    }

    Page {
        sections.forEach { sec ->
            Section(sec.optString("title")) {
                sec.optJSONArray("fields").objects().forEachIndexed { i, f ->
                    if (i > 0) HorizontalDivider(color = C.line)
                    val key = f.optString("key")
                    val help = f.optString("help")
                    when (f.optString("type")) {
                        "bool" -> SwitchRow(f.optString("title"), f.optBoolean("value"), help) { save(key, it) }
                        "choice" -> {
                            val opts = f.optJSONArray("options").let { a -> (0 until a.length()).map { a.getJSONArray(it) } }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dropdown(f.optString("title"), opts.map { it.get(0).toString() to it.getString(1) },
                                    f.get("value").toString(), Modifier.weight(1f)) { save(key, it) }
                                if (help.isNotBlank()) { Spacer(Modifier.width(8.dp)); HelpIcon(help) }
                            }
                        }
                        "apps" -> {
                            val list = f.optJSONArray("value").strings()
                            Row(Modifier.fillMaxWidth().clickable { apps = f }, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.optString("title"))
                                    Muted(if (list.isEmpty()) "не выбрано" else list.joinToString(", ") { appLabel(ctx, it) })
                                }
                                if (help.isNotBlank()) HelpIcon(help)
                            }
                        }
                        else -> Row(Modifier.fillMaxWidth().clickable { editing = f }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.optString("title"))
                                Muted(f.get("value").toString())
                            }
                            if (help.isNotBlank()) HelpIcon(help)
                        }
                    }
                }
            }
        }
        OutlinedButton({ reset = true }) { Text("Сбросить по умолчанию", color = C.red) }
    }

    editing?.let { f ->
        val number = f.optString("type") == "int"
        TextDialog(
            f.optString("title"), hint = if (number) "от ${f.optInt("min")} до ${f.optInt("max")}" else "",
            initial = f.get("value").toString(), number = number, onDismiss = { editing = null },
        ) { text ->
            editing = null
            if (number) text.trim().toIntOrNull()?.let { save(f.optString("key"), it) } ?: scope.launch { state.toast("Нужно число") }
            else save(f.optString("key"), text)
        }
    }
    apps?.let { f ->
        AppPicker(f.optString("title"), f.optJSONArray("value").strings().toSet(), onDismiss = { apps = null }) { chosen ->
            apps = null
            save(f.optString("key"), JSONArray(chosen.map { it.pkg }))
        }
    }
    if (reset) {
        Confirm("Сброс", "Вернуть все настройки по умолчанию? Подписки сохранятся.", "Сбросить", onDismiss = { reset = false }) {
            scope.launch { py("reset_settings"); load() }
        }
    }
}

// ======================================================================= «Журнал»
@Composable
fun LogsScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var logs by remember { mutableStateOf(JSONObject()) }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            logs = py("logs")
            delay(2000)
        }
    }
    val lines = logs.optJSONArray(if (tab == 0) "app" else "core").strings()
    Page {
        TabRow(tab, containerColor = C.bg) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Программа") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Ядро") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ copyText(ctx, lines.joinToString("\n")); scope.launch { state.toast("Журнал скопирован") } }) { Text("Копировать") }
            OutlinedButton({
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, lines.takeLast(400).joinToString("\n")), "Журнал Nexus VPN Mobile"))
            }) { Text("Поделиться") }
        }
        Section {
            if (lines.isEmpty()) Muted("Пусто")
            lines.takeLast(300).reversed().forEach { Mono(it) }
        }
    }
}

// ======================================================================= «О программе»
@Composable
fun AboutScreen(state: AppState) {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf(JSONObject()) }
    LaunchedEffect(Unit) { info = py("about") }
    Page {
        Section("Nexus VPN Mobile") {
            Text("Версия ${info.optString("version")}")
            Muted("VPN-клиент с умной самообучающейся маршрутизацией. Те же настройки и возможности, что у NexusVPN Pro для компьютера.")
            val repo = info.optString("repo")
            if (repo.isNotEmpty()) {
                TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$repo"))) }) {
                    Text("github.com/$repo")
                }
            }
        }
        Section("Чтобы VPN не отключался") {
            Muted("Android может останавливать фоновые приложения ради экономии батареи. Разрешите NexusVPN работать в фоне " +
                "и включите «Постоянную VPN» — тогда телефон сам держит подключение.")
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                Button({
                    @Suppress("BatteryLife")
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Разрешить работу в фоне") }
            } else {
                Muted("✓ Работа в фоне разрешена")
            }
            OutlinedButton({ ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }) { Text("Настройки VPN Android («Постоянная VPN»)") }
        }
        Section("Удаление") {
            Muted("Перед удалением выключите VPN. Все настройки, подписки и изученное удалятся вместе с приложением.")
            OutlinedButton({
                ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Удалить приложение", color = C.red) }
        }
        Muted("Папка данных: ${info.optString("data_dir")}", Modifier.padding(top = 4.dp))
    }
}
