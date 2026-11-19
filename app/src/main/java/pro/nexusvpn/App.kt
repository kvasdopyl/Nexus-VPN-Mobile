package pro.nexusvpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.system.Os
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
        const val CHANNEL = "vpn"
        private const val TAG = "NexusVPN"

        /** Startup problems that did not crash the app: shown on the main screen. */
        val startupErrors = mutableListOf<String>()

        fun stackTrace(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
    }

    val nexusDataDir: File by lazy { File(filesDir, "data").apply { mkdirs() } }
    val crashFile: File by lazy { File(filesDir, "last-crash.txt") }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // a crash is written to a file and shown on the next start, instead of the app just closing
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                crashFile.writeText(
                    "Nexus VPN Mobile ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\nПоток: ${thread.name}\n\n${stackTrace(e)}",
                )
            }
            previous?.uncaughtException(thread, e)
        }

        runCatching { Os.setenv("NEXUSVPN_DATA_DIR", nexusDataDir.absolutePath, true) }
            .onFailure { startupErrors += "Папка данных: ${stackTrace(it)}" }
        runCatching { if (!Python.isStarted()) Python.start(AndroidPlatform(this)) }
            .onFailure { startupErrors += "Запуск Python: ${stackTrace(it)}" }
        runCatching {
            val run = File(nexusDataDir, "run").apply { mkdirs() }
            Libbox.setup(SetupOptions().also {
                it.basePath = filesDir.path
                it.workingPath = run.path
                it.tempPath = cacheDir.path
                it.fixAndroidStack = Build.VERSION.SDK_INT in Build.VERSION_CODES.N..Build.VERSION_CODES.N_MR1
                it.logMaxLines = 3000
            })
        }.onFailure { startupErrors += "Ядро sing-box: ${stackTrace(it)}" }
        startupErrors.forEach { Log.e(TAG, it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
    }
}

/** The program's Python code: every call returns a JSON object; any error comes back as {"error": …}, never a crash. */
object Py {
    private val bridge by lazy { Python.getInstance().getModule("nexus_android.bridge") }

    fun init(): JSONObject =
        try {
            JSONObject(bridge.callAttr("init").toString())
        } catch (e: Throwable) {
            JSONObject().put("error", e.message ?: e.toString()).put("trace", App.stackTrace(e))
        }

    fun call(name: String, vararg args: Any?): JSONObject =
        try {
            JSONObject(bridge.callAttr("call", name, *args).toString())
        } catch (e: Throwable) {
            JSONObject().put("error", e.message ?: e.toString())
        }
}
