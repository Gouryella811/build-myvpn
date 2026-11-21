package com.buildmyvpn.vpn

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.lang.reflect.Proxy

/**
 * Runtime bridge to `io.nekohasekai.libbox` (compiled from SagerNet/sing-box v1.12+).
 *
 * Uses dynamic Proxy for `PlatformInterface` so the Kotlin project compiles cleanly
 * both before and after `libbox.aar` is dropped into `android/app/libs/`, and tolerates
 * minor PlatformInterface additions across sing-box 1.12.x patch releases.
 */
class LibboxBridge(
    private val vpnService: VpnService,
    private val appContext: Context
) {
    companion object {
        private const val TAG = "LibboxBridge"
        private const val LIBBOX_CLASS = "io.nekohasekai.libbox.Libbox"
        private const val PLATFORM_IFACE = "io.nekohasekai.libbox.PlatformInterface"
        private const val SETUP_OPTIONS_CLASS = "io.nekohasekai.libbox.SetupOptions"
    }

    private var boxService: Any? = null
    private var tunPfd: ParcelFileDescriptor? = null

    fun isAvailable(): Boolean = runCatching {
        Class.forName(LIBBOX_CLASS)
        true
    }.getOrDefault(false)

    fun start(configJson: String) {
        val libboxClass = Class.forName(LIBBOX_CLASS)
        setupBaseDirs(libboxClass)

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
            libboxClass.getMethod("setup", optsClass).invoke(null, opts)
        }.onFailure { Log.w(TAG, "Libbox.setup skipped or already initialized: ${it.message}") }
    }

    private fun handlePlatformCall(methodName: String, args: Array<out Any?>?): Any? {
        return when (methodName) {
            "usePlatformAutoDetectInterfaceControl" -> true
            "autoDetectInterfaceControl" -> {
                val fd = (args?.firstOrNull() as? Number)?.toInt() ?: return null
                vpnService.protect(fd)
                null
            }
            "openTun" -> openAndroidTun()
            "useProcFS" -> false
            "findConnectionOwner" -> -1
            "packageNameByUid" -> ""
            "uidByPackageName" -> -1
            "underNetworkExtension" -> false
            "includeAllNetworks" -> false
            "clearDNSCache" -> null
            "readWIFIState" -> null
            "startDefaultInterfaceMonitor" -> null
            "closeDefaultInterfaceMonitor" -> null
            "getInterfaces" -> null
            "sendNotification" -> null
            "systemCertificates" -> null
            else -> null
        }
    }

    private fun openAndroidTun(): Int {
        tunPfd?.close()
        val builder = vpnService.Builder()
            .setSession("Build MyVpn")
            .setMtu(9000)
            .addAddress("172.19.0.1", 30)
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("172.19.0.2")

        // Prevent VPN routing loops by excluding Build MyVpn itself
        runCatching { builder.addDisallowedApplication(appContext.packageName) }

        val pfd = builder.establish() ?: error("VpnService.Builder.establish() returned null")
        tunPfd = pfd
        return pfd.fd
    }
}
