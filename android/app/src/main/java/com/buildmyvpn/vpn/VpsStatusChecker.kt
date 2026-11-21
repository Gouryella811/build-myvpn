package com.buildmyvpn.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

enum class OnlineStatus { UNKNOWN, CHECKING, ONLINE, OFFLINE }

data class VpsRegionInfo(
    val countryCode: String,
    val label: String
)

@Serializable
private data class IpWhoIsResponse(
    val success: Boolean = false,
    @SerialName("country_code") val countryCode: String = "",
    val country: String = "",
    val city: String = ""
)

/**
 * Lightweight client-side VPS health & geolocation checker (no SSH credentials required).
 */
object VpsStatusChecker {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Performs a lightweight 2-second TCP connect probe to host:port to determine online reachability.
     */
    suspend fun probeTcpOnline(host: String, port: Int, timeoutMs: Int = 2000): OnlineStatus =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
                OnlineStatus.ONLINE
            }.getOrDefault(OnlineStatus.OFFLINE)
        }

    /**
     * Resolves VPS country/city once via public HTTPS GeoIP API and caches on ServerProfile.
     */
    suspend fun lookupRegion(host: String): VpsRegionInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://ipwho.is/${host.trim()}")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string().orEmpty()
                val parsed = json.decodeFromString(IpWhoIsResponse.serializer(), body)
                if (!parsed.success || parsed.countryCode.isBlank()) return@runCatching null
                val label = listOf(parsed.country, parsed.city)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" · ")
                VpsRegionInfo(parsed.countryCode.uppercase(), label.ifBlank { parsed.countryCode.uppercase() })
            }
        }.getOrNull()
    }
}
