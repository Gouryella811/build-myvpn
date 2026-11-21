package com.buildmyvpn.vpn

import java.util.Locale

object FormatUtils {

    fun formatSpeed(bytesPerSec: Long): String {
        val b = bytesPerSec.coerceAtLeast(0L)
        return when {
            b < 1024L -> "$b B/s"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB/s", b / 1024.0)
            b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB/s", b / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB/s", b / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
