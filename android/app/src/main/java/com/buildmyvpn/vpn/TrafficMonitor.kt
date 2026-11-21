package com.buildmyvpn.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class TrafficSpeed(
    val upBytesPerSec: Long = 0L,
    val downBytesPerSec: Long = 0L
)

@Serializable
private data class ClashTrafficTick(
    val up: Long = 0L,
    val down: Long = 0L
)

/**
 * Streams real-time upload/download speeds directly from sing-box's local Clash API `/traffic`
 * endpoint (127.0.0.1:19090). Zero server-side changes, zero extra VPS ports exposed.
 */
class TrafficMonitor {

    private val json = Json { ignoreUnknownKeys = true }
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // continuous streaming response
        .build()

    suspend fun streamTraffic(
        clashApiSecret: String,
        onTick: suspend (TrafficSpeed) -> Unit
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("http://${SingBoxConfigBuilder.CLASH_API_LISTEN}/traffic")
            .header("Authorization", "Bearer $clashApiSecret")
            .get()
            .build()

        while (currentCoroutineContext().isActive) {
            runCatching {
                streamClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val bodyStream = resp.body?.byteStream() ?: return@use
                    BufferedReader(InputStreamReader(bodyStream, Charsets.UTF_8)).use { reader ->
                        var line: String? = null
                        while (currentCoroutineContext().isActive && reader.readLine().also { line = it } != null) {
                            val trimmed = line?.trim().orEmpty()
                            if (!trimmed.startsWith("{")) continue
                            val tick = runCatching {
                                json.decodeFromString(ClashTrafficTick.serializer(), trimmed)
                            }.getOrNull() ?: continue
                            onTick(TrafficSpeed(upBytesPerSec = tick.up.coerceAtLeast(0L), downBytesPerSec = tick.down.coerceAtLeast(0L)))
                        }
                    }
                }
            }
            if (currentCoroutineContext().isActive) {
                delay(1500)
            }
        }
    }
}
