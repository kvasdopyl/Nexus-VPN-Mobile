package pro.nexusvpn

import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Called from Python (nexus_android.bridge._Vpn): starts and stops the core inside the VPN service. */
object VpnBridge {
    @Volatile private var running = false
    @Volatile private var error = ""
    @Volatile private var latch: CountDownLatch? = null

    @JvmStatic
    fun start(configPath: String): String {
        val ctx = App.instance
        if (VpnService.prepare(ctx) != null) {
            return "Нужно разрешение на VPN: откройте Nexus VPN Mobile и нажмите кнопку включения."
        }
        error = ""
        val l = CountDownLatch(1)
        latch = l
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, NexusVpnService::class.java).setAction(NexusVpnService.ACTION_START)
                .putExtra(NexusVpnService.EXTRA_CONFIG, configPath),
        )
        if (!l.await(25, TimeUnit.SECONDS)) return "Служба VPN не запустилась за 25 секунд"
        return error
    }

    @JvmStatic
    fun reload(configPath: String): String {
        val svc = NexusVpnService.instance ?: return "служба VPN не запущена"
        return svc.reload(configPath)
    }

    @JvmStatic
    fun stop() {
        NexusVpnService.instance?.stopVpn()
        running = false
    }

    @JvmStatic
    fun isRunning(): Boolean = running && NexusVpnService.instance != null

    @JvmStatic
    fun lastError(): String = error

    internal fun onStarted() {
        running = true
        latch?.countDown()
    }

    internal fun onFailed(message: String) {
        running = false
        error = message
        latch?.countDown()
    }

    internal fun onStopped() {
        running = false
    }
}
