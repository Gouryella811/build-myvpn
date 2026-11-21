package com.buildmyvpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.io.File
import java.lang.reflect.Proxy
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Complete Android PlatformInterface bridge for `io.nekohasekai.libbox` (sing-box v1.12+).
 *
 * Implements:
 * - DefaultInterfaceMonitor (`startDefaultInterfaceMonitor` / `closeDefaultInterfaceMonitor`)
 * - Real `getInterfaces()` returning active non-VPN Android network interfaces + DNS servers
 * - Real `localDNSTransport()` using Android DnsResolver (API 29+) / Network.getAllByName (API 26-28)
 * - Proper `openTun(TunOptions)` honoring MTU, addresses, DNS server, routes, and app exclusion
 * - `FixAndroidStack = true` for Go 1.23+ Android compatibility
 * - Live `writeLog` callback for diagnostics
 */
class LibboxBridge(
    private val vpnService: VpnService,
    private val appContext: Context,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "LibboxBridge"
        private const val LIBBOX_CLASS = "io.nekohasekai.libbox.Libbox"
        private const val PLATFORM_IFACE = "io.nekohasekai.libbox.PlatformInterface"
        private const val SETUP_OPTIONS_CLASS = "io.nekohasekai.libbox.SetupOptions"
        private const val LOCAL_DNS_IFACE = "io.nekohasekai.libbox.LocalDNSTransport"
        private const val NET_IFACE_CLASS = "io.nekohasekai.libbox.NetworkInterface"
        private const val NET_IFACE_ITER_IFACE = "io.nekohasekai.libbox.NetworkInterfaceIterator"
        private const val STRING_ITER_IFACE = "io.nekohasekai.libbox.StringIterator"
        private const val RCODE_NXDOMAIN = 3
    }

    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    private val defaultNetworkRef = AtomicReference<Network?>(null)
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var interfaceUpdateListener: Any? = null
    private var boxService: Any? = null
    private var tunPfd: ParcelFileDescriptor? = null

    fun isAvailable(): Boolean = runCatching {
        Class.forName(LIBBOX_CLASS)
        true
    }.getOrDefault(false)

    fun start(configJson: String) {
        val libboxClass = Class.forName(LIBBOX_CLASS)
        setupBaseDirs(libboxClass)

        // Seed default non-VPN network BEFORE sing-box initializes
        defaultNetworkRef.set(findCurrentNonVpnNetwork())

        val platformIfaceClass = Class.forName(PLATFORM_IFACE)
        val platformProxy = Proxy.newProxyInstance(
            platformIfaceClass.classLoader,
            arrayOf(platformIfaceClass)
        ) { _, method, args ->
            handlePlatformCall(method.name, args)
        }

        val newServiceMethod = libboxClass.getMethod(
            "newService",
            String::class.java,
            platformIfaceClass
        )
        val service = newServiceMethod.invoke(null, configJson, platformProxy)
            ?: error("Libbox.newService returned null")

        service.javaClass.getMethod("start").invoke(service)
        boxService = service
    }

    fun stop() {
        unregisterDefaultNetworkCallback()
        interfaceUpdateListener = null

        runCatching {
            boxService?.let { it.javaClass.getMethod("close").invoke(it) }
        }.onFailure { Log.w(TAG, "Error closing libbox service", it) }
        boxService = null

        runCatching { tunPfd?.close() }
        tunPfd = null
    }

    private fun setupBaseDirs(libboxClass: Class<*>) {
        runCatching {
            val optsClass = Class.forName(SETUP_OPTIONS_CLASS)
            val opts = optsClass.getDeclaredConstructor().newInstance()
            val baseDir = File(appContext.filesDir, "singbox").apply { mkdirs() }
            val tempDir = File(appContext.cacheDir, "singbox_tmp").apply { mkdirs() }
            optsClass.getMethod("setBasePath", String::class.java).invoke(opts, baseDir.absolutePath)
            optsClass.getMethod("setWorkingPath", String::class.java).invoke(opts, baseDir.absolutePath)
            optsClass.getMethod("setTempPath", String::class.java).invoke(opts, tempDir.absolutePath)
            runCatching {
                optsClass.getMethod("setFixAndroidStack", Boolean::class.javaPrimitiveType).invoke(opts, true)
            }
            libboxClass.getMethod("setup", optsClass).invoke(null, opts)
        }.onFailure { Log.w(TAG, "Libbox.setup warning: ${it.message}") }
    }

    private fun handlePlatformCall(methodName: String, args: Array<out Any?>?): Any? {
        return when (methodName) {
            "localDNSTransport" -> createLocalDnsTransportProxy()
            "usePlatformAutoDetectInterfaceControl" -> true
            "autoDetectInterfaceControl" -> {
                val fd = (args?.firstOrNull() as? Number)?.toInt() ?: return null
                vpnService.protect(fd)
                null
            }
            "openTun" -> openAndroidTun(args?.firstOrNull())
            "writeLog" -> {
                val msg = args?.firstOrNull()?.toString().orEmpty()
                if (msg.isNotBlank()) {
                    Log.i("sing-box", msg)
                    onLog(msg)
                }
                null
            }
            "useProcFS" -> false
            "findConnectionOwner" -> -1
            "packageNameByUid" -> ""
            "uidByPackageName" -> -1
            "startDefaultInterfaceMonitor" -> {
                val listener = args?.firstOrNull()
                startDefaultInterfaceMonitor(listener)
                null
            }
            "closeDefaultInterfaceMonitor" -> {
                unregisterDefaultNetworkCallback()
                interfaceUpdateListener = null
                null
            }
            "getInterfaces" -> buildNetworkInterfaceIterator()
            "underNetworkExtension" -> false
            "includeAllNetworks" -> false
            "readWIFIState" -> null
            "systemCertificates" -> createStringIterator(emptyList())
            "clearDNSCache" -> null
            "sendNotification" -> null
            else -> null
        }
    }

    private fun openAndroidTun(tunOptions: Any?): Int {
        tunPfd?.close()

        val mtu = runCatching {
            (tunOptions?.javaClass?.getMethod("getMTU")?.invoke(tunOptions) as? Number)?.toInt()
        }.getOrNull()?.takeIf { it > 0 } ?: 9000

        val builder = vpnService.Builder()
            .setSession("Build MyVpn")
            .setMtu(mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Apply IPv4 & IPv6 addresses from TunOptions (fallback to 172.19.0.1/30)
        var addedInet4 = false
        var addedInet6 = false
        if (tunOptions != null) {
            runCatching {
                val iter4 = tunOptions.javaClass.getMethod("getInet4Address").invoke(tunOptions)
                if (iter4 != null) {
                    val hasNext = iter4.javaClass.getMethod("hasNext")
                    val next = iter4.javaClass.getMethod("next")
                    while (hasNext.invoke(iter4) == true) {
                        val prefix = next.invoke(iter4) ?: continue
                        val addr = prefix.javaClass.getMethod("address").invoke(prefix) as String
                        val bits = (prefix.javaClass.getMethod("prefix").invoke(prefix) as Number).toInt()
                        builder.addAddress(addr, bits)
                        addedInet4 = true
                    }
                }
            }
            runCatching {
                val iter6 = tunOptions.javaClass.getMethod("getInet6Address").invoke(tunOptions)
                if (iter6 != null) {
                    val hasNext = iter6.javaClass.getMethod("hasNext")
                    val next = iter6.javaClass.getMethod("next")
                    while (hasNext.invoke(iter6) == true) {
                        val prefix = next.invoke(iter6) ?: continue
                        val addr = prefix.javaClass.getMethod("address").invoke(prefix) as String
                        val bits = (prefix.javaClass.getMethod("prefix").invoke(prefix) as Number).toInt()
                        builder.addAddress(addr, bits)
                        addedInet6 = true
                    }
                }
            }
        }
        if (!addedInet4) {
            builder.addAddress("172.19.0.1", 30)
            addedInet4 = true
        }

        // Configure DNS server from TunOptions (usually 172.19.0.2 so sing-box hijacks port 53)
        val dnsAddr = runCatching {
            val box = tunOptions?.javaClass?.getMethod("getDNSServerAddress")?.invoke(tunOptions)
            box?.javaClass?.getMethod("getValue")?.invoke(box) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "172.19.0.2"
        builder.addDnsServer(dnsAddr)

        // Route default IPv4 (and IPv6 only if configured) into TUN
        if (addedInet4) builder.addRoute("0.0.0.0", 0)
        if (addedInet6) builder.addRoute("::", 0)

        // Exclude Build MyVpn itself to avoid VPN loops and keep local Clash API reachable
        runCatching { builder.addDisallowedApplication(appContext.packageName) }

        val pfd = builder.establish() ?: error("VpnService.Builder.establish() returned null")
        tunPfd = pfd
        onLog("TUN established (fd=${pfd.fd}, mtu=$mtu, dns=$dnsAddr)")
        return pfd.fd
    }

    // -------------------------------------------------------------------------
    // DefaultInterfaceMonitor (critical for sing-box auto_detect_interface)
    // -------------------------------------------------------------------------
    private fun startDefaultInterfaceMonitor(listener: Any?) {
        interfaceUpdateListener = listener
        val cm = connectivityManager ?: return
        unregisterDefaultNetworkCallback()

        val current = findCurrentNonVpnNetwork()
        defaultNetworkRef.set(current)
        notifyDefaultInterface(current)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                defaultNetworkRef.set(network)
                notifyDefaultInterface(network)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    defaultNetworkRef.set(network)
                    notifyDefaultInterface(network)
                }
            }

            override fun onLost(network: Network) {
                if (defaultNetworkRef.get() == network) {
                    val fallback = findCurrentNonVpnNetwork()
                    defaultNetworkRef.set(fallback)
                    notifyDefaultInterface(fallback)
                }
            }
        }
        defaultNetworkCallback = callback
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed: ${it.message}") }
    }

    private fun unregisterDefaultNetworkCallback() {
        val cm = connectivityManager ?: return
        defaultNetworkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        defaultNetworkCallback = null
    }

    private fun findCurrentNonVpnNetwork(): Network? {
        val cm = connectivityManager ?: return null
        val active = cm.activeNetwork
        if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                return active
            }
        }
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net)
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    private fun notifyDefaultInterface(network: Network?) {
        val listener = interfaceUpdateListener ?: return
        val updateMethod = runCatching {
            listener.javaClass.getMethod(
                "updateDefaultInterface",
                String::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
        }.getOrNull() ?: return

        if (network == null) {
            runCatching { updateMethod.invoke(listener, "", -1, false, false) }
            return
        }

        val cm = connectivityManager ?: return
        val lp = cm.getLinkProperties(network) ?: return
        val ifaceName = lp.interfaceName ?: return
        val ifaceIndex = runCatching { NetworkInterface.getByName(ifaceName)?.index }.getOrNull() ?: return
        val caps = cm.getNetworkCapabilities(network)
        val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false

        runCatching {
            updateMethod.invoke(listener, ifaceName, ifaceIndex, isExpensive, false)
            onLog("Default interface updated: $ifaceName (idx=$ifaceIndex)")
        }.onFailure { Log.w(TAG, "updateDefaultInterface failed: ${it.message}") }
    }

    // -------------------------------------------------------------------------
    // Real NetworkInterfaceIterator for sing-box InterfaceFinder
    // -------------------------------------------------------------------------
    private fun buildNetworkInterfaceIterator(): Any {
        val netIfaceClass = Class.forName(NET_IFACE_CLASS)
        val iterIfaceClass = Class.forName(NET_IFACE_ITER_IFACE)
        val libboxClass = Class.forName(LIBBOX_CLASS)

        val typeWifi = runCatching { libboxClass.getField("InterfaceTypeWIFI").getInt(null) }.getOrDefault(0)
        val typeCell = runCatching { libboxClass.getField("InterfaceTypeCellular").getInt(null) }.getOrDefault(1)
        val typeEth = runCatching { libboxClass.getField("InterfaceTypeEthernet").getInt(null) }.getOrDefault(2)
        val typeOther = runCatching { libboxClass.getField("InterfaceTypeOther").getInt(null) }.getOrDefault(3)

        val result = mutableListOf<Any>()
        val cm = connectivityManager
        val sysIfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())

        if (cm != null) {
            @Suppress("DEPRECATION")
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
                val lp = cm.getLinkProperties(network) ?: continue
                val name = lp.interfaceName ?: continue
                val sysIface = sysIfaces.find { it.name == name } ?: continue

                val item = netIfaceClass.getDeclaredConstructor().newInstance()
                netIfaceClass.getMethod("setName", String::class.java).invoke(item, name)
                netIfaceClass.getMethod("setIndex", Int::class.javaPrimitiveType).invoke(item, sysIface.index)
                val mtu = runCatching { sysIface.mtu }.getOrDefault(1500).takeIf { it > 0 } ?: 1500
                netIfaceClass.getMethod("setMTU", Int::class.javaPrimitiveType).invoke(item, mtu)

                val cidrs = sysIface.interfaceAddresses.mapNotNull { it.toCidrString() }
                val strIterClass = Class.forName(STRING_ITER_IFACE)
                netIfaceClass.getMethod("setAddresses", strIterClass).invoke(item, createStringIterator(cidrs))

                val dnsList = lp.dnsServers.mapNotNull { it.hostAddress }
                netIfaceClass.getMethod("setDNSServer", strIterClass).invoke(item, createStringIterator(dnsList))

                val ifaceType = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> typeWifi
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> typeCell
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> typeEth
                    else -> typeOther
                }
                netIfaceClass.getMethod("setType", Int::class.javaPrimitiveType).invoke(item, ifaceType)

                var flags = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (runCatching { sysIface.isLoopback }.getOrDefault(false)) flags = flags or OsConstants.IFF_LOOPBACK
                if (runCatching { sysIface.isPointToPoint }.getOrDefault(false)) flags = flags or OsConstants.IFF_POINTOPOINT
                if (runCatching { sysIface.supportsMulticast() }.getOrDefault(false)) flags = flags or OsConstants.IFF_MULTICAST
                netIfaceClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(item, flags)

                val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                netIfaceClass.getMethod("setMetered", Boolean::class.javaPrimitiveType).invoke(item, metered)

                result.add(item)
            }
        }

        val iterator = result.iterator()
        return Proxy.newProxyInstance(
            iterIfaceClass.classLoader,
            arrayOf(iterIfaceClass)
        ) { _, method, _ ->
            when (method.name) {
                "hasNext" -> iterator.hasNext()
                "next" -> if (iterator.hasNext()) iterator.next() else null
                else -> null
            }
        }
    }

    private fun InterfaceAddress.toCidrString(): String? {
        val host = address?.hostAddress ?: return null
        val cleanHost = host.substringBefore('%')
        return when (address) {
            is Inet4Address, is Inet6Address -> "$cleanHost/$networkPrefixLength"
            else -> null
        }
    }

    private fun createStringIterator(items: List<String>): Any {
        val strIterClass = Class.forName(STRING_ITER_IFACE)
        val iter = items.iterator()
        return Proxy.newProxyInstance(
            strIterClass.classLoader,
            arrayOf(strIterClass)
        ) { _, method, _ ->
            when (method.name) {
                "len" -> items.size
                "hasNext" -> iter.hasNext()
                "next" -> if (iter.hasNext()) iter.next() else ""
                else -> null
            }
        }
    }

    // -------------------------------------------------------------------------
    // LocalDNSTransport (Android DnsResolver for sing-box type="local" DNS)
    // -------------------------------------------------------------------------
    private fun createLocalDnsTransportProxy(): Any {
        val localDnsClass = Class.forName(LOCAL_DNS_IFACE)
        return Proxy.newProxyInstance(
            localDnsClass.classLoader,
            arrayOf(localDnsClass)
        ) { _, method, args ->
            when (method.name) {
                "raw" -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                "exchange" -> {
                    val ctx = args?.getOrNull(0)
                    val message = args?.getOrNull(1) as? ByteArray
                    if (ctx != null && message != null) {
                        handleRawDnsExchange(ctx, message)
                    }
                    null
                }
                "lookup" -> {
                    val ctx = args?.getOrNull(0)
                    val domain = args?.getOrNull(2) as? String
                    if (ctx != null && !domain.isNullOrBlank()) {
                        handleDomainLookup(ctx, domain)
                    }
                    null
                }
                else -> null
            }
        }
    }

    private fun handleRawDnsExchange(ctx: Any, message: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val net = defaultNetworkRef.get() ?: findCurrentNonVpnNetwork() ?: error("missing default interface")
        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>(null)
        val signal = CancellationSignal()

        DnsResolver.getInstance().rawQuery(
            net,
            message,
            DnsResolver.FLAG_NO_RETRY,
            Dispatchers.IO.asExecutor(),
            signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    runCatching {
                        if (rcode == 0) {
                            ctx.javaClass.getMethod("rawSuccess", ByteArray::class.java).invoke(ctx, answer)
                        } else {
                            ctx.javaClass.getMethod("errorCode", Int::class.javaPrimitiveType).invoke(ctx, rcode)
                        }
                    }
                    latch.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    val cause = error.cause
                    if (cause is ErrnoException) {
                        runCatching {
                            ctx.javaClass.getMethod("errnoCode", Int::class.javaPrimitiveType).invoke(ctx, cause.errno)
                        }
                    } else {
                        errorRef.set(error)
                    }
                    latch.countDown()
                }
            }
        )
        latch.await(5, TimeUnit.SECONDS)
        errorRef.get()?.let { throw it }
    }

    private fun handleDomainLookup(ctx: Any, domain: String) {
        val net = defaultNetworkRef.get() ?: findCurrentNonVpnNetwork() ?: error("missing default interface")
        try {
            val addrs = net.getAllByName(domain).mapNotNull { it.hostAddress }.joinToString("\n")
            ctx.javaClass.getMethod("success", String::class.java).invoke(ctx, addrs)
        } catch (_: UnknownHostException) {
            ctx.javaClass.getMethod("errorCode", Int::class.javaPrimitiveType).invoke(ctx, RCODE_NXDOMAIN)
        }
    }
}
