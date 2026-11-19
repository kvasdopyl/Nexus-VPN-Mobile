package pro.nexusvpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pro.nexusvpn.Py

object C {
    val bg = Color(0xFF0B1220)
    val card = Color(0xFF111A2E)
    val line = Color(0xFF1E2A44)
    val text = Color(0xFFE5E7EB)
    val muted = Color(0xFF94A3B8)
    val accent = Color(0xFF38BDF8)
    val green = Color(0xFF22C55E)
    val amber = Color(0xFFF59E0B)
    val red = Color(0xFFEF4444)
}

val NexusColors = darkColorScheme(
    primary = C.accent, onPrimary = Color(0xFF04131F), secondary = C.green, background = C.bg, surface = C.card,
    surfaceVariant = C.line, onSurface = C.text, onBackground = C.text, onSurfaceVariant = C.muted, error = C.red,
    surfaceContainer = C.card, surfaceContainerHigh = C.card, surfaceContainerHighest = C.line,
)

suspend fun py(name: String, vararg args: Any?): JSONObject = withContext(Dispatchers.IO) { Py.call(name, *args) }

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.2f ГБ".format(n / 1073741824.0)
    n >= 1L shl 20 -> "%.1f МБ".format(n / 1048576.0)
    n >= 1L shl 10 -> "%.1f КБ".format(n / 1024.0)
    else -> "$n Б"
}

fun duration(sec: Long): String {
    val d = sec / 86400
    val h = sec % 86400 / 3600
    val m = sec % 3600 / 60
    return if (d > 0) "${d}д ${h}ч ${m}м" else if (h > 0) "${h}ч ${m}м" else "${m}м ${sec % 60}с"
}

fun copyText(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Nexus VPN Mobile", text))
}

fun clipboardText(ctx: Context): String =
    ctx.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun Section(title: String? = null, help: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = C.card),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.SemiBold, color = C.accent, modifier = Modifier.weight(1f))
                    if (!help.isNullOrBlank()) HelpIcon(help)
                }
            }
            content()
        }
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, color = C.muted, fontSize = 13.sp, modifier = modifier)

fun html(text: String): AnnotatedString = AnnotatedString.fromHtml(text)

@Composable
fun HelpIcon(text: String) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.size(26.dp).background(C.line, CircleShape).clickable { open = true },
        contentAlignment = Alignment.Center,
    ) { Text("?", color = C.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton({ open = false }) { Text("Понятно") } },
            text = { Text(html(text), modifier = Modifier.verticalScroll(rememberScrollState())) },
        )
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, help: String? = null, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Muted(subtitle)
        }
        if (!help.isNullOrBlank()) {
            HelpIcon(help)
            Spacer(Modifier.width(8.dp))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> Dropdown(
    label: String, options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
            Column(Modifier.weight(1f)) {
                if (label.isNotEmpty()) Text(label, color = C.muted, fontSize = 11.sp)
                Text(
                    options.firstOrNull { it.first == selected }?.second ?: selected.toString(),
                    color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text("▾", color = C.muted)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (value, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}

@Composable
fun TextDialog(
    title: String, hint: String = "", initial: String = "", multiline: Boolean = false, number: Boolean = false,
    confirm: String = "Готово", onDismiss: () -> Unit, onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hint.isNotEmpty()) Muted(hint)
                OutlinedTextField(
                    text, { text = it }, modifier = Modifier.fillMaxWidth().let { if (multiline) it.height(180.dp) else it },
                    singleLine = !multiline,
                    keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                )
            }
        },
        confirmButton = { TextButton({ onConfirm(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun Confirm(title: String, text: String, yes: String = "Да", onDismiss: () -> Unit, onYes: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton({ onDismiss(); onYes() }) { Text(yes, color = C.red) } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}

data class AppInfo(val pkg: String, val label: String, val system: Boolean, val info: ApplicationInfo)

fun installedApps(pm: PackageManager): List<AppInfo> =
    pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .filter { it.packageName != pro.nexusvpn.App.instance.packageName }
        .map {
            AppInfo(
                it.packageName, pm.getApplicationLabel(it).toString(),
                it.flags and ApplicationInfo.FLAG_SYSTEM != 0 && it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0, it,
            )
        }
        .sortedBy { it.label.lowercase() }

fun appLabel(ctx: Context, pkg: String): String =
    runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }
        .getOrDefault(pkg)

@Composable
fun AppIcon(info: ApplicationInfo, size: Int = 36) {
    val ctx = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, info.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { info.loadIcon(ctx.packageManager).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.size(size.dp)) else Box(Modifier.size(size.dp).background(C.line, CircleShape))
}

/** Pick installed apps: search, system apps on demand, already chosen ones ticked. */
@Composable
fun AppPicker(
    title: String, selected: Set<String>, single: Boolean = false, onDismiss: () -> Unit, onDone: (List<AppInfo>) -> Unit,
) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(selected) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { installedApps(ctx.packageManager) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(containerColor = C.bg) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(query, { query = it }, placeholder = { Text("Поиск…") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                SwitchRow("Показать системные приложения", showSystem) { showSystem = it }
                val list = apps
                if (list == null) {
                    Muted("Загрузка списка приложений…")
                } else {
                    val q = query.trim().lowercase()
                    val shown = list.filter {
                        (showSystem || !it.system || it.pkg in chosen) &&
                            (q.isEmpty() || q in it.label.lowercase() || q in it.pkg.lowercase())
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(shown, key = { it.pkg }) { app ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (single) {
                                        onDone(listOf(app))
                                    } else {
                                        chosen = if (app.pkg in chosen) chosen - app.pkg else chosen + app.pkg
                                    }
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(app.info)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Muted(app.pkg)
                                }
                                if (!single) Checkbox(app.pkg in chosen, { chosen = if (it) chosen + app.pkg else chosen - app.pkg })
                            }
                            HorizontalDivider(color = C.line)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("Отмена") }
                    if (!single) {
                        TextButton({
                            val all = apps.orEmpty().associateBy { it.pkg }
                            onDone(chosen.mapNotNull { all[it] })
                        }) { Text("Готово (${chosen.size})") }
                    }
                }
            }
        }
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = C.text)

val KIND_TEXT = mapOf("domain" to "Сайт", "full" to "Адрес сайта", "keyword" to "Слово в адресе", "ip" to "IP / подсеть",
    "process" to "Приложение")

@Composable
fun MaterialThemed(content: @Composable () -> Unit) = MaterialTheme(colorScheme = NexusColors, content = content)
