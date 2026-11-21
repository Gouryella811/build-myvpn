package com.buildmyvpn.deploy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DeployEvent(
    val version: String = "",
    val step: String,
    val status: String, // "running" | "ok" | "error"
    val code: String = "OK",
    val message: String = "",
    val os: String? = null,
    val arch: String? = null,
    @SerialName("bbr_status") val bbrStatus: String? = null,
    val node: DeployedNodePayload? = null
)

@Serializable
data class DeployedNodePayload(
    val host: String,
    val port: Int,
    val protocol: String,
    @SerialName("bbr_status") val bbrStatus: String? = null,
    @SerialName("singbox_version") val singboxVersion: String? = null,
    val reality: DeployedRealityPayload? = null,
    val hysteria2: DeployedHysteria2Payload? = null
)

@Serializable
data class DeployedRealityPayload(
    val enabled: Boolean = false,
    val uuid: String = "",
    @SerialName("public_key") val publicKey: String = "",
    @SerialName("short_id") val shortId: String = "",
    val sni: String = ""
)

@Serializable
data class DeployedHysteria2Payload(
    val enabled: Boolean = false,
    val password: String = "",
    @SerialName("tls_mode") val tlsMode: String = "",
    @SerialName("certificate_sha256") val certificateSha256: String = ""
)

object DeployProtocolParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseLine(line: String): DeployEvent? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        return runCatching { json.decodeFromString(DeployEvent.serializer(), trimmed) }.getOrNull()
    }
}

object HostValidator {
    private val ipv4Regex = Regex(
        "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    )
    private val domainRegex = Regex(
        "^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$"
    )

    fun isValidHost(input: String): Boolean {
        val h = input.trim()
        return ipv4Regex.matches(h) || domainRegex.matches(h)
    }

    fun isIpv4(input: String): Boolean = ipv4Regex.matches(input.trim())
}

object DeployErrorMapper {
    fun toUserMessage(code: String, fallback: String): String = when (code) {
        "ERR_AUTH_FAILED" ->
            "SSH 用户名或密码/私钥不正确。部分云厂商默认用户名是 ubuntu 或 debian 而非 root。"
        "ERR_PORT_IN_USE" ->
            "443 端口已被服务器上的其他服务占用，请改用其他端口后重试。"
        "ERR_CLOUD_FIREWALL" ->
            "服务端已启动，但手机无法连入。请检查 VPS 控制台防火墙/安全组是否已放行对应端口。"
        "ERR_DOMAIN_DNS_MISMATCH" ->
            "该域名尚未解析到此服务器 IP，请先配置 A 记录或改用纯 IP 模式。"
        "ERR_UNSUPPORTED_OS" ->
            "当前服务器系统暂不支持自动部署，推荐使用 Ubuntu 22.04/24.04 或 Debian 12。"
        "ERR_NO_PRIVILEGE" ->
            "需要 root 权限或免密 sudo 权限才能自动安装服务。"
        else -> fallback.ifBlank { "部署遇到异常（$code），请检查服务器网络与凭据后重试。" }
    }
}
