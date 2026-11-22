package com.buildmyvpn

import com.buildmyvpn.data.UpdateManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {
    @Test
    fun comparesSemanticVersionsCorrectly() {
        assertTrue(UpdateManager.isNewerVersion("1.1.2", "1.1.1"))
        assertTrue(UpdateManager.isNewerVersion("v1.2.0", "1.1.9"))
        assertTrue(UpdateManager.isNewerVersion("2.0.0", "1.9.9"))
        assertTrue(UpdateManager.isNewerVersion("1.1.0", "1.1.0-beta"))
        assertFalse(UpdateManager.isNewerVersion("1.1.2", "1.1.2"))
        assertFalse(UpdateManager.isNewerVersion("1.1.0", "1.1.2"))
        assertFalse(UpdateManager.isNewerVersion("1.1.2-beta", "1.1.2"))
    }
}
