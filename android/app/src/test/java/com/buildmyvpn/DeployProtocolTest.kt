package com.buildmyvpn

import com.buildmyvpn.deploy.DeployErrorMapper
import com.buildmyvpn.deploy.DeployProtocolParser
import com.buildmyvpn.deploy.HostValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployProtocolTest {

    @Test
    fun `validates ipv4 and domain names strictly`() {
        assertTrue(HostValidator.isValidHost("203.0.113.10"))
        assertTrue(HostValidator.isIpv4("203.0.113.10"))
        assertTrue(HostValidator.isValidHost("vpn.example.com"))
        assertFalse(HostValidator.isIpv4("vpn.example.com"))

        assertFalse(HostValidator.isValidHost("999.0.0.1"))
        assertFalse(HostValidator.isValidHost("not a host"))
        assertFalse(HostValidator.isValidHost("http://203.0.113.10"))
        assertFalse(HostValidator.isValidHost(""))
    }

    @Test
    fun `parses deploy json lines and ignores non-json stdout noise`() {
        assertNull(DeployProtocolParser.parseLine("some ssh banner line"))

        val line = """{"version":"1.1.0","step":"sys_tune","status":"ok","code":"OK","message":"System tuning completed","bbr_status":"enabled"}"""
        val ev = DeployProtocolParser.parseLine(line)
        assertNotNull(ev)
        assertEquals("sys_tune", ev!!.step)
        assertEquals("enabled", ev.bbrStatus)
    }

    @Test
    fun `maps known error codes to beginner-friendly hints`() {
        val msg = DeployErrorMapper.toUserMessage("ERR_PORT_IN_USE", "raw")
        assertTrue(msg.contains("443"))
    }
}
