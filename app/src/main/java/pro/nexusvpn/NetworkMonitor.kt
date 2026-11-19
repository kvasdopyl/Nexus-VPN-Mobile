package pro.nexusvpn

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.system.ErrnoException
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The phone's real network (Wi-Fi / mobile), never our own VPN.
 * registerDefaultNetworkCallback reports the VPN itself since Android 9: the core would then send its own
 * traffic back into the tunnel and the internet stops. A network *request* gets the underlying network.
 */
object NetworkMonitor {
    @Volatile var network: Network? = null
        private set
    private var listener: InterfaceUpdateListener? = null
    private var registered = false
    private val connectivity by lazy { App.instance.getSystemService(ConnectivityManager::class.java) }
    private val handler by lazy { Handler(HandlerThread("network-monitor").apply { start() }.looper) }
    private val firstNetwork = CountDownLatch(1)

    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }.build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (isVpn(network)) return
            this@NetworkMonitor.network = network
            firstNetwork.countDown()
            notifyListener()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == this@NetworkMonitor.network) notifyListener()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (network == this@NetworkMonitor.network) notifyListener()
        }

        override fun onLost(network: Network) {
            if (network == this@NetworkMonitor.network) {
                this@NetworkMonitor.network = null
                notifyListener()
            }
        }
    }

    private fun isVpn(network: Network): Boolean =
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    @Synchronized
    fun start() {
        if (registered) return
        when {
            Build.VERSION.SDK_INT >= 31 -> connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
            Build.VERSION.SDK_INT >= 28 -> connectivity.requestNetwork(request, callback, handler)
            else -> connectivity.registerDefaultNetworkCallback(callback, handler)
        }
        registered = true
        firstNetwork.await(3, TimeUnit.SECONDS)
    }

    @Synchronized
    fun stop() {
        listener = null
        if (!registered) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false
        network = null
    }

    @Synchronized
    fun setListener(l: InterfaceUpdateListener?) {
        listener = l
        notifyListener()
    }

    private fun notifyListener() {
        val l = listener ?: return
        val n = network
        val name = n?.let { connectivity.getLinkProperties(it)?.interfaceName }
        if (name == null) {
            l.updateDefaultInterface("", -1, false, false)
            return
        }
        var index = -1
        for (attempt in 0 until 10) {
            index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
            if (index >= 0) break
            Thread.sleep(100)
        }
        val caps = connectivity.getNetworkCapabilities(n)
        val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        l.updateDefaultInterface(name, index, expensive, false)
    }
}

/** DNS «как у телефона» (type local / dhcp in the config): asks Android's resolver on the real network. */
object LocalResolver : LocalDNSTransport {
    private val executor = Executors.newCachedThreadPool()

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("raw DNS needs Android 10")
        val net = NetworkMonitor.network ?: error("нет сети")
        val done = CountDownLatch(1)
        val signal = CancellationSignal()
        ctx.onCancel { signal.cancel(); done.countDown() }
        DnsResolver.getInstance().rawQuery(net, message, DnsResolver.FLAG_NO_RETRY, executor, signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                    done.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    val cause = error.cause
                    if (cause is ErrnoException) ctx.errnoCode(cause.errno) else ctx.errorCode(2)
                    done.countDown()
                }
            })
        done.await(15, TimeUnit.SECONDS)
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val net = NetworkMonitor.network ?: error("нет сети")
        val all: Array<InetAddress> = net.getAllByName(domain)
        val filtered = all.filter {
            when {
                network.endsWith("4") -> it is java.net.Inet4Address
                network.endsWith("6") -> it is java.net.Inet6Address
                else -> true
            }
        }
        if (filtered.isEmpty()) {
            ctx.errorCode(3)
            return
        }
        ctx.success(filtered.mapNotNull { it.hostAddress }.joinToString("\n"))
    }
}
