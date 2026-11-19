package pro.nexusvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val MODES = listOf("global" to "Всё через VPN", "custom" to "Своя", "smart" to "Умная")
private val DARK_TEXT = Color(0xFF04131F)

@Composable
fun HomeScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val st = state.status
    val mode = st.optString("mode", "smart")
    val status = st.optString("state", "stopped")
    val help = state.help

    Page {
        if (!st.isNull("update") && st.has("update")) {
            val up = st.getJSONObject("update")
            Card(
                colors = CardDefaults.cardColors(containerColor = C.accent.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { state.screen = Screen.Updates },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Доступно обновление ${up.optString("name")}", color = C.accent, fontWeight = FontWeight.SemiBold)
                    Muted("Нажмите, чтобы скачать")
                }
            }
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val color = when (status) {
                "running" -> C.green
                "starting", "stopping" -> C.amber
                "error" -> C.red
                else -> C.muted
            }
            Box(
                Modifier.size(190.dp).background(color.copy(alpha = 0.18f), CircleShape).clickable {
                    scope.launch {
                        val on = status == "running" || status == "starting"
                        if (!on && !state.ensureVpnPermission()) {
                            state.toast("Без разрешения на VPN подключиться нельзя")
                            return@launch
                        }
                        val r = py("toggle")
                        if (r.optString("error").isNotEmpty()) state.toast(r.getString("error"))
                        state.refresh()
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(150.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                    Text(
                        when (status) {
                            "running" -> "ВКЛЮЧЁН"
                            "starting", "stopping" -> "…"
                            else -> "ВЫКЛЮЧЕН"
                        },
                        color = if (status == "running" || status == "starting") DARK_TEXT else C.text,
                        fontWeight = FontWeight.Bold, fontSize = 22.sp, textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(st.optString("state_text"), color = color, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            if (status == "running") {
                val d = st.optInt("delay", -1)
                Muted("Задержка через VPN: ${if (d >= 0) "$d мс" else "…"}")
            }
            if (status == "error" && st.optString("error").isNotEmpty()) Muted(st.optString("error"))
        }

        Section("Режим маршрутизации", help.optString("mode_$mode")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MODES.forEach { (key, title) ->
                    val on = key == mode
                    Box(
                        Modifier.weight(1f).background(if (on) C.accent else C.line, RoundedCornerShape(10.dp))
                            .clickable {
                                scope.launch {
                                    val r = py("set_mode", key)
                                    if (r.optBoolean("custom_empty")) {
                                        state.toast("Список «Через VPN» пуст — добавьте сайты или приложения")
                                        state.screen = Screen.Custom
                                    }
                                    state.refresh()
                                }
                            }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            title, textAlign = TextAlign.Center, fontSize = 13.sp, color = if (on) DARK_TEXT else C.text,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            Muted(
                when (mode) {
                    "global" -> "Весь трафик телефона идёт через VPN."
                    "custom" -> "Через VPN идут только сайты и приложения из «Своей маршрутизации»."
                    else -> "Программа сама находит заблокированное и запоминает. Остальное идёт напрямую на полной скорости."
                },
            )
            if (mode == "smart") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted(
                        "Изучено: через VPN ${st.optInt("learned_proxy")} · напрямую ${st.optInt("learned_direct")}",
                        Modifier.weight(1f),
                    )
                    TextButton({ state.screen = Screen.Smart }) { Text("Подробнее") }
                }
            }
        }

        Section("Подключение", help.optString("server")) {
            val nodes = st.optJSONArray("nodes").objects()
            Dropdown(
                "Сервер",
                listOf("" to "Авто — самый быстрый") + nodes.map { it.optString("tag") to it.optString("title") },
                st.optString("active_node"),
            ) { tag -> scope.launch { py("select_node", tag); state.refresh() } }
            if (nodes.isEmpty()) {
                TextButton({ state.screen = Screen.Servers }) { Text("Добавить подписку или сервер") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Скорость", "↑ ${bytes(st.optLong("up"))}/с\n↓ ${bytes(st.optLong("down"))}/с", Modifier.weight(1f))
            StatCard(
                "Через VPN", "${bytes(st.optLong("vpn_bytes"))}\nнапрямую: ${bytes(st.optLong("direct_bytes"))}",
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard(
                "Соединения", "через VPN: ${st.optInt("vpn_conns")}\nнапрямую: ${st.optInt("direct_conns")}",
                Modifier.weight(1f),
            )
            StatCard("Время работы", duration(st.optLong("uptime")), Modifier.weight(1f))
        }
        val now = st.optJSONArray("vpn_now").strings()
        if (now.isNotEmpty()) {
            Section("Сейчас через VPN") { Text(now.joinToString(", "), color = C.accent, fontSize = 13.sp) }
        }
    }
}

@Composable
fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = C.card), shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, color = C.muted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}
