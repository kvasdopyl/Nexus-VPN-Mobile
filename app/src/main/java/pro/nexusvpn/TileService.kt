package pro.nexusvpn

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import pro.nexusvpn.ui.MainActivity

/** The button in the notification shade. */
class TileService : android.service.quicksettings.TileService() {
    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        if (VpnService.prepare(this) != null) {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
            return
        }
        qsTile?.apply { state = Tile.STATE_UNAVAILABLE; updateTile() }
        Thread {
            Py.init()
            Py.call("toggle")
            refresh()
        }.start()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (VpnBridge.isRunning()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
