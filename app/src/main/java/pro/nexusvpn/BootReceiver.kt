package pro.nexusvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** «Подключаться при включении телефона»: reconnect if the VPN was on when the phone went off. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (VpnService.prepare(context) != null) return
        val pending = goAsync()
        Thread {
            try {
                val info = Py.init()
                val reconnect = if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    info.optBoolean("autostart") && info.optBoolean("was_connected")
                } else {
                    info.optBoolean("was_connected")
                }
                if (reconnect) Py.call("start")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
