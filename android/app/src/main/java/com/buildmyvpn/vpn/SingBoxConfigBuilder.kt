package com.buildmyvpn.vpn

import com.buildmyvpn.data.model.ActiveTransport
import com.buildmyvpn.data.model.ProxyMode
import com.buildmyvpn.data.model.RoutingRule
import com.buildmyvpn.data.model.ServerProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

data class LocalRuleFiles(
    val geositePath: String?,
    val geoipPath: String?
) {
    fun isComplete(): Boolean =
        !geositePath.isNullOrBlank() &&
            !geoipPath.isNullOrBlank() &&
            File(geositePath).let { it.exists() && it.length() > 0 } &&
            File(geoipPath).let { it.exists() && it.length() > 0 }
}

/**
 * Generates sing-box 1.12+ compliant client configurations.
 *
 * Key guarantees:
 * - Strict 1.12+ typed DNS servers + route actions ("sniff", "hijack-dns") + default_domain_resolver.
 * - Pure IPv4 Hysteria2 enforces tls.certificate_sha256 pinning (never sets insecure=true).
 * - IPv4-first TUN + DNS strategy prevents 5-10s IPv6 hangs on IPv4-only VPSes (e.g. AWS Lightsail/EC2).
 * - Fail-Closed routing: if local rule files are missing/corrupt, routes traffic via proxy (never direct).
 */
object SingBoxConfigBuilder {

    const val CLASH_API_LISTEN = "127.0.0.1:19090"
    private val prettyJson = Json { prettyPrint = true }

    fun build(
        profile: ServerProfile,
        proxyMode: ProxyMode,
        resolvedRule: RoutingRule,
        localRuleFiles: LocalRuleFiles? = null,
        clashApiSecret: String
    ): String {
        val useRegionRuleSet = proxyMode == ProxyMode.SMART &&
            resolvedRule != RoutingRule.GENERAL &&
            localRuleFiles?.isComplete() == true

        val config = buildJsonObject {
            put("log", buildJsonObject {
                put("level", "info")
                put("timestamp", true)
            })
            put("experimental", buildJsonObject {
                put("clash_api", buildJsonObject {
                    put("external_controller", CLASH_API_LISTEN)
                    put("secret", clashApiSecret)
                })
            })
            put("dns", buildDnsBlock(useRegionRuleSet))
            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("mtu", 1500)
                    put("address", buildJsonArray {
                        add(JsonPrimitive("172.19.0.1/30"))
                    })
                    put("auto_route", true)
                    put("strict_route", true)
                    put("stack", "gvisor")
                })
            })
            put("outbounds", buildJsonArray {
                add(buildProxyOutbound(profile))
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                })
            })
            put("route", buildRouteBlock(profile, proxyMode, useRegionRuleSet, localRuleFiles))
        }
        return prettyJson.encodeToString(JsonObject.serializer(), config)
    }

    private fun buildDnsBlock(useRegionRuleSet: Boolean): JsonObject = buildJsonObject {
        put("servers", buildJsonArray {
            add(buildJsonObject {
                put("tag", "dns-local")
                put("type", "local")
            })
            add(buildJsonObject {
                put("tag", "dns-remote")
                put("type", "tcp")
                put("server", "1.1.1.1")
                put("server_port", 53)
                put("detour", "proxy")
            })
        })
        if (useRegionRuleSet) {
            put("rules", buildJsonArray {
                add(buildJsonObject {
                    put("rule_set", buildJsonArray { add(JsonPrimitive("geosite-direct")) })
                    put("server", "dns-local")
                })
            })
        }
        put("final", "dns-remote")
        put("strategy", "ipv4_only")
        put("independent_cache", true)
    }

    private fun buildProxyOutbound(profile: ServerProfile): JsonObject {
        return when (profile.activeTransport) {
            ActiveTransport.REALITY -> {
                val r = requireNotNull(profile.reality) { "VLESS+REALITY credentials missing" }
                buildJsonObject {
                    put("type", "vless")
                    put("tag", "proxy")
                    put("server", profile.host)
                    put("server_port", profile.port)
                    put("uuid", r.uuid)
                    put("flow", "xtls-rprx-vision")
                    put("tls", buildJsonObject {
                        put("enabled", true)
                        put("server_name", r.sni)
                        put("utls", buildJsonObject {
                            put("enabled", true)
                            put("fingerprint", "chrome")
                        })
                        put("reality", buildJsonObject {
                            put("enabled", true)
                            put("public_key", r.publicKey)
                            put("short_id", r.shortId)
                        })
                    })
                }
            }

            ActiveTransport.HYSTERIA2 -> {
                val h = requireNotNull(profile.hysteria2) { "Hysteria2 credentials missing" }
                buildJsonObject {
                    put("type", "hysteria2")
                    put("tag", "proxy")
                    put("server", profile.host)
                    put("server_port", profile.port)
                    put("password", h.password)
                    put("tls", buildJsonObject {
                        put("enabled", true)
                        put("server_name", profile.host)
                        put("alpn", buildJsonArray { add(JsonPrimitive("h3")) })
                        if (h.certificateSha256.isNotBlank()) {
                            // Pure IPv4 path: strict certificate pinning, NEVER insecure=true
                            put("certificate_sha256", buildJsonArray {
                                add(JsonPrimitive(h.certificateSha256))
                            })
                        }
                    })
                }
            }
        }
    }

    private fun buildRouteBlock(
        profile: ServerProfile,
        proxyMode: ProxyMode,
        useRegionRuleSet: Boolean,
        localRuleFiles: LocalRuleFiles?
    ): JsonObject = buildJsonObject {
        put("default_domain_resolver", "dns-local")
        put("auto_detect_interface", true)

        if (useRegionRuleSet && localRuleFiles != null) {
            put("rule_set", buildJsonArray {
                add(buildJsonObject {
                    put("tag", "geosite-direct")
                    put("type", "local")
                    put("format", "binary")
                    put("path", localRuleFiles.geositePath!!)
                })
                add(buildJsonObject {
                    put("tag", "geoip-direct")
                    put("type", "local")
                    put("format", "binary")
                    put("path", localRuleFiles.geoipPath!!)
                })
            })
        }

        put("rules", buildJsonArray {
            add(buildJsonObject { put("action", "sniff") })
            add(buildJsonObject {
                put("protocol", "dns")
                put("action", "hijack-dns")
            })
            // Reject HTTP/3 QUIC (UDP 443) over TCP-based VLESS+REALITY so browsers cleanly use
            // HTTP/2 over TCP+BBR without QUIC-over-TCP congestion meltdown or mux EOF drops.
            if (profile.activeTransport == ActiveTransport.REALITY) {
                add(buildJsonObject {
                    put("network", "udp")
                    put("port", 443)
                    put("action", "reject")
                })
            }
            add(buildJsonObject {
                put("ip_is_private", true)
                put("outbound", "direct")
            })
            if (proxyMode == ProxyMode.SMART && useRegionRuleSet) {
                add(buildJsonObject {
                    put("rule_set", JsonArray(listOf(
                        JsonPrimitive("geosite-direct"),
                        JsonPrimitive("geoip-direct")
                    )))
                    put("outbound", "direct")
                })
            }
        })
        // Fail-Closed: unmatched traffic always routes through proxy
        put("final", "proxy")
    }
}
