package com.buildmyvpn.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed interface DelayResult {
    data object Idle : DelayResult
    data object Testing : DelayResult
    data class Success(val millis: Int) : DelayResult
    data object Timeout : DelayResult
    data object Failed : DelayResult
}

@Serializable
private data class ClashDelayResponse(val delay: Int)

/**
 * Measures real end-to-end proxy egress latency via sing-box's local Clash API delay endpoint.
 * Guarantees traffic traverses Phone -> VPN -> User VPS -> Test URL (never ICMP ping, never mocked).
 */
class DelayTester(
    private val probeUrl: String = "https://www.gstatic.com/generate_204",
    private val timeoutMs: Int = 5000
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout((timeoutMs + 1000).toLong(), TimeUnit.MILLISECONDS)
        .build()

    suspend fun measureProxyDelay(clashApiSecret: String): DelayResult = withContext(Dispatchers.IO) {
        val encodedUrl = URLEncoder.encode(probeUrl, "UTF-8")
        val endpoint = "http://${SingBoxConfigBuilder.CLASH_API_LISTEN}/proxies/proxy/delay?url=$encodedUrl&timeout=$timeoutMs"
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $clashApiSecret")
            .get()
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { resp ->
                if (resp.code == 504) return@withContext DelayResult.Timeout
                if (!resp.isSuccessful) return@withContext DelayResult.Failed
                val body = resp.body?.string().orEmpty()
                val parsed = json.decodeFromString(ClashDelayResponse.serializer(), body)
                if (parsed.delay > 0) DelayResult.Success(parsed.delay) else DelayResult.Failed
            }
        }.getOrElse { ex ->
            if (ex is java.net.SocketTimeoutException) DelayResult.Timeout else DelayResult.Failed
        }
    }
}
