package com.buildmyvpn.rules

import android.content.Context
import com.buildmyvpn.data.model.RoutingRule
import com.buildmyvpn.vpn.LocalRuleFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Serializable
data class RuleManifest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("singbox_version") val singboxVersion: String = "",
    val files: Map<String, RuleFileEntry> = emptyMap()
)

@Serializable
data class RuleFileEntry(
    val size: Long,
    val sha256: String
)

sealed interface RuleUpdateResult {
    data class Updated(val updatedAt: String) : RuleUpdateResult
    data class Failed(val reason: String) : RuleUpdateResult
}

/**
 * Downloads, verifies (SHA-256 + minimum size), and atomically caches .srs rule-sets
 * from the self-hosted Build MyVpn rules mirror.
 *
 * Fail-Closed: if local rule files are absent or invalid, returns null so SingBoxConfigBuilder
 * safely routes unmatched traffic through the VPN proxy instead of leaking direct traffic.
 */
class RuleSetManager(
    private val context: Context,
    private val mirrorBaseUrl: String = DEFAULT_MIRROR_BASE_URL
) {
    companion object {
        // Point to your GitHub repo's rolling "rules-latest" release once published
        const val DEFAULT_MIRROR_BASE_URL =
            "https://github.com/Gouryella811/build-myvpn/releases/download/rules-latest"
        private const val MIN_RULE_BYTES = 1024L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val rulesDir: File
        get() = File(context.filesDir, "rules").apply { mkdirs() }

    /**
     * Copies any bundled baseline .srs snapshots from assets/rules/ on first launch.
     */
    suspend fun seedBundledRulesIfNeeded() = withContext(Dispatchers.IO) {
        val assetFiles = runCatching { context.assets.list("rules").orEmpty() }.getOrDefault(emptyArray())
        for (name in assetFiles) {
            if (!name.endsWith(".srs")) continue
            val dest = File(rulesDir, name)
            if (!dest.exists() || dest.length() < MIN_RULE_BYTES) {
                runCatching {
                    context.assets.open("rules/$name").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    fun getLocalRuleFiles(effectiveRule: RoutingRule): LocalRuleFiles? {
        val code = when (effectiveRule) {
            RoutingRule.CN -> "cn"
            RoutingRule.IR -> "ir"
            RoutingRule.RU -> "ru"
            RoutingRule.GENERAL -> return null
        }
        val geosite = File(rulesDir, "geosite-$code.srs")
        val geoip = File(rulesDir, "geoip-$code.srs")
        val candidate = LocalRuleFiles(geosite.absolutePath, geoip.absolutePath)
        return if (candidate.isComplete()) candidate else null
    }

    suspend fun updateAllRules(): RuleUpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val manifestReq = Request.Builder()
                .url("$mirrorBaseUrl/manifest.json")
                .get()
                .build()

            val manifestBody = httpClient.newCall(manifestReq).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} fetching manifest.json")
                resp.body?.string() ?: error("Empty manifest.json")
            }
            val manifest = json.decodeFromString(RuleManifest.serializer(), manifestBody)
            require(manifest.files.isNotEmpty()) { "Manifest contains no rule files" }

            for ((fileName, entry) in manifest.files) {
                if (!fileName.endsWith(".srs") || fileName.contains("/") || fileName.contains("..")) continue
                val dest = File(rulesDir, fileName)
                if (dest.exists() && sha256Hex(dest.readBytes()).equals(entry.sha256, ignoreCase = true)) {
                    continue
                }

                val fileReq = Request.Builder()
                    .url("$mirrorBaseUrl/$fileName")
                    .get()
                    .build()
                val bytes = httpClient.newCall(fileReq).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code} fetching $fileName")
                    resp.body?.bytes() ?: error("Empty body for $fileName")
                }

                require(bytes.size >= MIN_RULE_BYTES) { "$fileName smaller than safety threshold" }
                val actualHash = sha256Hex(bytes)
                require(actualHash.equals(entry.sha256, ignoreCase = true)) {
                    "SHA-256 mismatch for $fileName"
                }

                // Atomic write via temp file + rename
                val tmp = File(rulesDir, "$fileName.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(dest)) {
                    dest.writeBytes(bytes)
                    tmp.delete()
                }
            }
            RuleUpdateResult.Updated(manifest.updatedAt)
        }.getOrElse { ex ->
            RuleUpdateResult.Failed(ex.message ?: "Unknown rule update error")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
