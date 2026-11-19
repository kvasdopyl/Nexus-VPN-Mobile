package pro.nexusvpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import pro.nexusvpn.ui.MainActivity
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import io.nekohasekai.libbox.Notification as BoxNotification
import io.nekohasekai.libbox.NetworkInterface as BoxInterface

/** The Android VPN: owns the TUN and runs the sing-box core (libbox) with the config the Python side wrote. */
class NexusVpnService : VpnService(), PlatformInterface, CommandServerHandler {
    companion object {
        const val ACTION_START = "pro.nexusvpn.START"
        const val ACTION_STOP = "pro.nexusvpn.STOP"
        const val EXTRA_CONFIG = "config"
        private const val NOTIFY_ID = 1

        @Volatile var instance: NexusVpnService? = null
            private set
    }

    private val lock = Any()
    private var server: CommandServer? = null
    private var tun: ParcelFileDescriptor? = null
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Thread { Py.call("stop") }.start()
            return START_NOT_STICKY
        }
        showNotification("Подключение…")
        val path = intent?.getStringExtra(EXTRA_CONFIG)
        if (path == null) {
            // started by Android itself («Постоянная VPN» or after the app was killed): let the program connect
            Thread { Py.init(); Py.call("start") }.start()
            return START_STICKY
        }
        Thread { startCore(path) }.start()
        return START_STICKY
    }

    private fun startCore(path: String) {
        synchronized(lock) {
            try {
                closeCore()
                NetworkMonitor.start()
                val s = CommandServer(this, this)
                s.start()
                server = s
                s.startOrReloadService(File(path).readText(), OverrideOptions())
                showNotification("Подключено")
                VpnBridge.onStarted()
            } catch (e: Throwable) {
                closeCore()
                VpnBridge.onFailed("Ядро не запустилось: ${e.message ?: e}")
                stopSelfCompletely()
            }
        }
    }

    fun reload(path: String): String = synchronized(lock) {
        val s = server ?: return "ядро не запущено"
        try {
            s.startOrReloadService(File(path).readText(), OverrideOptions())
            ""
        } catch (e: Throwable) {
            e.message ?: e.toString()
        }
    }

    fun stopVpn() {
        synchronized(lock) {
            closeCore()
        }
        VpnBridge.onStopped()
        stopSelfCompletely()
    }

    private fun closeCore() {
        server?.let { s ->
            runCatching { s.closeService() }
            runCatching { s.close() }
        }
        server = null
        runCatching { tun?.close() }
        tun = null
        NetworkMonitor.stop()
    }

    private fun stopSelfCompletely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        synchronized(lock) { closeCore() }
        VpnBridge.onStopped()
        Thread { Py.call("revoked") }.start()
        stopSelfCompletely()
    }

    override fun onDestroy() {
        synchronized(lock) { closeCore() }
        VpnBridge.onStopped()
        instance = null
        super.onDestroy()
    }

    private fun showNotification(text: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NexusVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Отключить", stop)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFY_ID, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            } else {
                0
            },
        )
    }

    // ------------------------------------------------------------------ PlatformInterface
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) error("protect($fd) failed")
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("нет разрешения на VPN")
        val b = Builder().setSession(getString(R.string.app_name)).setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        options.inet4Address.forEach { b.addAddress(it.address(), it.prefix()) }
        options.inet6Address.forEach { b.addAddress(it.address(), it.prefix()) }
        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                options.dnsServerAddress.forEachString { b.addDnsServer(it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                var any4 = false
                options.inet4RouteAddress.forEach { b.addRoute(it.toIpPrefix()); any4 = true }
                if (!any4 && options.inet4Address.hasNext()) b.addRoute("0.0.0.0", 0)
                var any6 = false
                options.inet6RouteAddress.forEach { b.addRoute(it.toIpPrefix()); any6 = true }
                if (!any6 && options.inet6Address.hasNext()) b.addRoute("::", 0)
                options.inet4RouteExcludeAddress.forEach { b.excludeRoute(it.toIpPrefix()) }
                options.inet6RouteExcludeAddress.forEach { b.excludeRoute(it.toIpPrefix()) }
            } else {
                options.inet4RouteRange.forEach { b.addRoute(it.address(), it.prefix()) }
                options.inet6RouteRange.forEach { b.addRoute(it.address(), it.prefix()) }
            }
            options.includePackage.forEachString {
                runCatching { b.addAllowedApplication(it) }
            }
            options.excludePackage.forEachString {
                runCatching { b.addDisallowedApplication(it) }
            }
        }
        val pfd = b.establish() ?: error("Android не разрешил создать VPN")
        tun = pfd
        return pfd.fd
    }

    override fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("Android 9 и старше не сообщает владельца соединения")
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol, InetSocketAddress(sourceAddress, sourcePort), InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) error("владелец не найден")
        val pkgs = packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        return ConnectionOwner().also {
            it.userId = uid
            it.userName = pkgs.firstOrNull() ?: ""
            it.setAndroidPackageNames(StringArray(pkgs))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        NetworkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        NetworkMonitor.setListener(null)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val out = mutableListOf<BoxInterface>()
        for (network in connectivity.allNetworks) {
            val lp = connectivity.getLinkProperties(network) ?: continue
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            val ni = all.find { it.name == lp.interfaceName } ?: continue
            out += BoxInterface().also { bi ->
                bi.name = lp.interfaceName
                bi.index = ni.index
                runCatching { bi.mtu = ni.mtu }
                bi.dnsServer = StringArray(lp.dnsServers.mapNotNull { it.hostAddress })
                bi.gateway = StringArray(
                    lp.routes.filter { it.destination.prefixLength == 0 }.mapNotNull { it.gateway }
                        .filterNot { it.isAnyLocalAddress }.mapNotNull { it.hostAddress },
                )
                bi.type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                bi.addresses = StringArray(ni.interfaceAddresses.map { a ->
                    val host = a.address.hostAddress?.substringBefore('%') ?: ""
                    "$host/${a.networkPrefixLength}"
                })
                var f = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    f = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (ni.isLoopback) f = f or OsConstants.IFF_LOOPBACK
                if (ni.isPointToPoint) f = f or OsConstants.IFF_POINTOPOINT
                if (ni.supportsMulticast()) f = f or OsConstants.IFF_MULTICAST
                bi.flags = f
                bi.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        val it = out.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = it.hasNext()
            override fun next(): BoxInterface = it.next()
        }
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() {}
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport = LocalResolver
    override fun sendNotification(notification: BoxNotification) {}
    override fun cancelNotification(identifier: String, typeID: Int) {}
    override fun startNeighborMonitor(listener: NeighborUpdateListener) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) {}
    override fun registerMyInterface(name: String) {}
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() = error("not supported")
    override fun openShellSession(
        user: PlatformUser, command: String, environ: StringIterator, term: String, rows: Int, cols: Int,
    ): ShellSession = error("not supported")
    override fun lookupUser(username: String): PlatformUser = error("not supported")
    override fun lookupSFTPServer(): String = error("not supported")
    override fun readSystemSSHHostKey(): String = error("not supported")
    override fun tailscaleHostname(): String = "nexusvpn"
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions): BridgeSession = error("not supported")

    // ------------------------------------------------------------------ CommandServerHandler
    override fun serviceStop() {
        Thread { Py.call("stop") }.start()
    }

    override fun serviceReload() {}
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun triggerNativeCrash() {}
    override fun writeDebugMessage(message: String) {}
    override fun connectSSHAgent(): Int = error("not supported")
}

class StringArray(list: List<String>) : StringIterator {
    private val items = list
    private var i = 0
    override fun hasNext(): Boolean = i < items.size
    override fun next(): String = items[i++]
    override fun len(): Int = items.size
}

private inline fun io.nekohasekai.libbox.RoutePrefixIterator.forEach(block: (RoutePrefix) -> Unit) {
    while (hasNext()) block(next())
}

private inline fun StringIterator.forEachString(block: (String) -> Unit) {
    while (hasNext()) block(next())
}

private fun RoutePrefix.toIpPrefix(): IpPrefix =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        IpPrefix(InetAddress.getByName(address()), prefix())
    } else {
        error("unused below Android 13")
    }
