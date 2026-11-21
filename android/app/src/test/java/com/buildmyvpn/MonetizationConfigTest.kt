package com.buildmyvpn

import com.buildmyvpn.data.MonetizationConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MonetizationConfigTest {

    @Test
    fun `website monetization json parses cleanly into Android model`() {
        val json = Json { ignoreUnknownKeys = true }
        val file = listOf(
            File("../../website/config/monetization.json"),
            File("../website/config/monetization.json"),
            File("website/config/monetization.json")
        ).firstOrNull { it.exists() }
        assertTrue("website/config/monetization.json must exist", file != null)
        val parsed = json.decodeFromString(MonetizationConfig.serializer(), file!!.readText())
        assertTrue(parsed.connectBanner.enabled)
        assertTrue(parsed.addVpsBanner.enabled)
        assertTrue(parsed.recommendedVps.isNotEmpty())
        assertEquals(1, parsed.schemaVersion)
    }
}
