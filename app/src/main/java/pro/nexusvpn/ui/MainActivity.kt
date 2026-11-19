package pro.nexusvpn.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pro.nexusvpn.App
import pro.nexusvpn.Py

enum class Screen(val title: String) {
    Home("Главная"), Servers("Серверы"), Custom("Своя маршрутизация"), Games("Игры"), Discord("Discord"),
    Smart("Умная маршрутизация"), Connections("Соединения"), Updates("Обновления"), Settings("Настройки"),
    Logs("Журнал"), About("О программе"),
}

/** Shared by the screens: the latest status and a way to show a message. */
class AppState(val snackbar: SnackbarHostState, val ensureVpnPermission: suspend () -> Boolean) {
    var status by mutableStateOf(JSONObject())
    var screen by mutableStateOf(Screen.Home)
    var help by mutableStateOf(JSONObject())

    suspend fun toast(text: String) {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(text)
    }

    suspend fun refresh() {
        status = py("status")
        status.optJSONArray("events").objects().forEach { toast(it.optString("text")) }
    }
}

class MainActivity : ComponentActivity() {
    private var permissionResult: CompletableDeferred<Boolean>? = null
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionResult?.complete(it.resultCode == RESULT_OK)
    }
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var pendingLink by mutableStateOf<String?>(null)

    private suspend fun ensureVpnPermission(): Boolean {
        val intent = VpnService.prepare(this) ?: return true
        val d = CompletableDeferred<Boolean>()
        permissionResult = d
        vpnPermission.launch(intent)
        return d.await()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        pendingLink = intent?.dataString
        setContent { MaterialThemed { Root(this::ensureVpnPermission, pendingLink) { pendingLink = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingLink = intent.dataString
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(ensureVpn: suspend () -> Boolean, link: String?, linkHandled: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val state = remember { AppState(snackbar, ensureVpn) }
    var ready by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf("") }
    var lastCrash by remember { mutableStateOf("") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        val app = App.instance
        lastCrash = withContext(Dispatchers.IO) {
            runCatching { app.crashFile.readText().also { app.crashFile.delete() } }.getOrDefault("")
        }
        val init = withContext(Dispatchers.IO) { Py.init() }
        if (init.has("error")) {
            startError = (App.startupErrors + init.optString("trace").ifEmpty { init.optString("error") }).joinToString("\n\n")
            return@LaunchedEffect
        }
        state.help = py("help")
        ready = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                state.refresh()
                delay(1000)
            }
        }
    }
    LaunchedEffect(link, ready) {
        if (ready && !link.isNullOrBlank()) {
            val r = py("add_text", link)
            state.toast(r.optString("error").ifEmpty { "Добавлено серверов: ${r.optInt("added")}" })
            state.screen = Screen.Servers
            linkHandled()
        }
    }
    BackHandler(enabled = state.screen != Screen.Home || drawer.isOpen) {
        if (drawer.isOpen) scope.launch { drawer.close() } else state.screen = Screen.Home
    }

    if (lastCrash.isNotEmpty()) {
        CrashDialog(lastCrash) { lastCrash = "" }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = C.card) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text("Nexus VPN Mobile", color = C.accent, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                        modifier = Modifier.padding(12.dp))
                    Screen.entries.forEach { s ->
                        NavigationDrawerItem(
                            label = { Text(s.title) },
                            selected = state.screen == s,
                            onClick = { state.screen = s; scope.launch { drawer.close() } },
                            colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = C.line),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        },
    ) {
        Scaffold(
            containerColor = C.bg,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(state.screen.title) },
                    navigationIcon = {
                        IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Меню") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = C.bg),
                )
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                if (startError.isNotEmpty()) {
                    ErrorReport("Программа не смогла запуститься", startError)
                } else if (!ready) {
                    Page { Muted("Запуск…") }
                } else {
                    when (state.screen) {
                        Screen.Home -> HomeScreen(state)
                        Screen.Servers -> ServersScreen(state)
                        Screen.Custom -> CustomScreen(state)
                        Screen.Games -> GamesScreen(state)
                        Screen.Discord -> DiscordScreen(state)
                        Screen.Smart -> SmartScreen(state)
                        Screen.Connections -> ConnectionsScreen(state)
                        Screen.Updates -> UpdatesScreen(state)
                        Screen.Settings -> SettingsScreen(state)
                        Screen.Logs -> LogsScreen(state)
                        Screen.About -> AboutScreen(state)
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorReport(title: String, text: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Page {
        Text(title, color = C.red, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Muted("Нажмите «Копировать» и пришлите текст разработчику — по нему видно, что исправить.")
        androidx.compose.material3.Button({ copyText(ctx, text) }) { Text("Копировать") }
        Section { Mono(text) }
    }
}

@Composable
fun CrashDialog(text: String, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Прошлый запуск завершился ошибкой") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Muted("Пришлите этот текст разработчику — по нему видно, что исправить.")
                Spacer(Modifier.height(8.dp))
                Mono(text)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton({ copyText(ctx, text); onClose() }) { Text("Копировать") } },
        dismissButton = { androidx.compose.material3.TextButton(onClose) { Text("Закрыть") } },
    )
}
