package com.buildmyvpn

import com.buildmyvpn.data.model.ActiveTransport
import com.buildmyvpn.data.model.DeployedProtocolMode
import com.buildmyvpn.data.model.Hysteria2Credentials
import com.buildmyvpn.data.model.ProxyMode
import com.buildmyvpn.data.model.RealityCredentials
import com.buildmyvpn.data.model.RoutingRule
import com.buildmyvpn.data.model.ServerProfile
import com.buildmyvpn.vpn.LocalRuleFiles
import com.buildmyvpn.vpn.SingBoxConfigBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigBuilderTest {

    private val realityProfile = ServerProfile(
        id = "srv-1",
        name = "My VPS",
        host = "203.0.113.10",
        port = 443,
        deployedMode = DeployedProtocolMode.REALITY,
        activeTransport = ActiveTransport.REALITY,
        reality = RealityCredentials(
            uuid = "11111111-2222-3333-4444-555555555555",
            publicKey = "mock_pub_key",
            shortId = "a1b2c3d4e5f60718",
            sni = "www.microsoft.com"
        )
    )

    @Test
    fun `builds sing-box 1_12 config with typed dns, sniff action, and fail-closed fallback`() {
        val raw = SingBoxConfigBuilder.build(
            profile = realityProfile,
            proxyMode = ProxyMode.SMART,
            resolvedRule = RoutingRule.CN,
            localRuleFiles = LocalRuleFiles("/nonexistent/geosite-cn.srs", "/nonexistent/geoip-cn.srs"),
            clashApiSecret = "secret123"
        )
        val root = Json.parseToJsonElement(raw).jsonObject

        // Verify typed DNS servers
        val dnsServers = root["dns"]!!.jsonObject["servers"]!!.jsonArray
        assertEquals("local", dnsServers[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("https", dnsServers[1].jsonObject["type"]!!.jsonPrimitive.content)

        // Verify route uses sniff + hijack-dns + fail-closed final=proxy (no rule_set since files missing)
        val route = root["route"]!!.jsonObject
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)
        assertEquals("dns-local", route["default_domain_resolver"]!!.jsonPrimitive.content)
        assertFalse(route.containsKey("rule_set"))
    }

    @Test
    fun `hysteria2 pure ipv4 enforces certificate_sha256 pinning and never sets insecure`() {
        val hy2Profile = realityProfile.copy(
            deployedMode = DeployedProtocolMode.DUAL,
            activeTransport = ActiveTransport.HYSTERIA2,
            hysteria2 = Hysteria2Credentials(
                password = "strong-password",
                tlsMode = "self_signed_pinned",
                certificateSha256 = "Base64CertHashValue=="
            )
        )
        val raw = SingBoxConfigBuilder.build(
            profile = hy2Profile,
            proxyMode = ProxyMode.GLOBAL,
            resolvedRule = RoutingRule.GENERAL,
            clashApiSecret = "secret456"
        )
        val root = Json.parseToJsonElement(raw).jsonObject
        val proxyOut = root["outbounds"]!!.jsonArray[0].jsonObject
        val tls = proxyOut["tls"]!!.jsonObject

        assertEquals("hysteria2", proxyOut["type"]!!.jsonPrimitive.content)
        assertFalse("insecure must NEVER be set", tls.containsKey("insecure"))
        assertEquals(
            "Base64CertHashValue==",
            tls["certificate_sha256"]!!.jsonArray[0].jsonPrimitive.content
        )
    }

    @Test
    fun `exports valid vless share uri`() {
        val uri = realityProfile.toShareUri()
        assertNotNull(uri)
        assertTrue(uri!!.startsWith("vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443"))
        assertTrue(uri.contains("security=reality"))
        assertTrue(uri.contains("pbk=mock_pub_key"))
    }
}
