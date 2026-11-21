package com.buildmyvpn.data.model

import kotlinx.serialization.Serializable
import java.net.URLEncoder

enum class ProxyMode { SMART, GLOBAL }

enum class RoutingRule { GENERAL, CN, IR, RU }

enum class DeployedProtocolMode { REALITY, DUAL, HYSTERIA2 }

enum class ActiveTransport { REALITY, HYSTERIA2 }

@Serializable
data class RealityCredentials(
    val uuid: String,
    val publicKey: String,
    val shortId: String,
    val sni: String
)

@Serializable
data class Hysteria2Credentials(
    val password: String,
    val tlsMode: String,            // "acme" | "self_signed_pinned"
    val certificateSha256: String   // Base64 DER SHA-256 when pure IPv4; empty for ACME domain
)

/**
 * Persisted server profile.
 * Security policy: SSH password is intentionally NOT stored on disk ("use-and-discard").
 */
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 443,
    val sshPort: Int = 22,
    val sshUsername: String = "root",
    val sshHostKeySha256: String? = null,
    val deployedMode: DeployedProtocolMode = DeployedProtocolMode.REALITY,
    val activeTransport: ActiveTransport = ActiveTransport.REALITY,
    val reality: RealityCredentials? = null,
    val hysteria2: Hysteria2Credentials? = null,
    val bbrStatus: String? = null,
    val countryCode: String? = null,
    val regionLabel: String? = null,
    val usedRxBytes: Long = 0L,
    val usedTxBytes: Long = 0L
) {
    val totalUsedBytes: Long
        get() = (usedRxBytes + usedTxBytes).coerceAtLeast(0L)

    val flagEmoji: String
        get() {
            val cc = countryCode?.trim()?.uppercase() ?: return ""
            if (cc.length != 2 || !cc[0].isLetter() || !cc[1].isLetter()) return ""
            val first = Character.toChars(0x1F1E6 + (cc[0] - 'A'))
            val second = Character.toChars(0x1F1E6 + (cc[1] - 'A'))
            return String(first) + String(second)
        }

    /**
     * Generates a standard shareable URI for desktop/iOS clients (hidden in Server Edit screen).
     */
    fun toShareUri(): String? {
        val label = URLEncoder.encode(name, "UTF-8")
        return when (activeTransport) {
            ActiveTransport.REALITY -> {
                val r = reality ?: return null
                "vless://${r.uuid}@$host:$port?encryption=none&flow=xtls-rprx-vision&security=reality&sni=${r.sni}&fp=chrome&pbk=${r.publicKey}&sid=${r.shortId}&type=tcp#$label"
            }
            ActiveTransport.HYSTERIA2 -> {
                val h = hysteria2 ?: return null
                val pinParam = if (h.certificateSha256.isNotBlank()) {
                    "&pinSHA256=${URLEncoder.encode(h.certificateSha256, "UTF-8")}"
                } else ""
                "hysteria2://${h.password}@$host:$port/?sni=$host$pinParam#$label"
            }
        }
    }
}
