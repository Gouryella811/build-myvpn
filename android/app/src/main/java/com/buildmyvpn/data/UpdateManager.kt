package com.buildmyvpn.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val hasUpdate: Boolean = false,
    val currentVersion: String = "",
    val latestVersion: String = "",
    val downloadUrl: String = "",
    val releaseUrl: String = "https://github.com/Gouryella811/build-myvpn/releases/latest",
    val notes: String = "",
    val error: String? = null
)

object UpdateManager {
    private const val GITHUB_LATEST_API =
        "https://api.github.com/repos/Gouryella811/build-myvpn/releases/latest"
    private const val FALLBACK_VERSION_URL =
        "https://build-myvpn.pages.dev/config/version.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getCurrentVersion(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.versionName ?: "1.1.2"
    }.getOrDefault("1.1.2")

    fun isNewerVersion(latest: String, current: String): Boolean {
        fun parse(v: String): List<Int> {
            val clean = v.trim().removePrefix("v").removePrefix("V")
            val pre = clean.contains('-')
            val parts = clean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val major = parts.getOrElse(0) { 0 }
            val minor = parts.getOrElse(1) { 0 }
            val patch = parts.getOrElse(2) { 0 }
            return listOf(major, minor, patch, if (pre) 0 else 1)
        }
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until 4) {
            if (l[i] != c[i]) return l[i] > c[i]
        }
        return false
    }

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        // 1. Primary: Official GitHub Releases Latest API
        runCatching {
            val req = Request.Builder()
                .url(GITHUB_LATEST_API)
                .header("User-Agent", "BuildMyVpn/$currentVersion")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val obj = json.parseToJsonElement(body).jsonObject
                    val tag = obj["tag_name"]?.jsonPrimitive?.content.orEmpty()
                    if (tag.isNotBlank()) {
                        val latest = tag.removePrefix("v").removePrefix("V")
                        val releaseUrl = obj["html_url"]?.jsonPrimitive?.content
                            ?: "https://github.com/Gouryella811/build-myvpn/releases/latest"
                        val notes = obj["body"]?.jsonPrimitive?.content.orEmpty().take(300)
                        var apkUrl = ""
                        obj["assets"]?.jsonArray?.forEach { elem ->
                            val a = elem.jsonObject
                            val name = a["name"]?.jsonPrimitive?.content.orEmpty()
                            if (name.endsWith("-android.apk") || name.endsWith(".apk")) {
                                apkUrl = a["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                            }
                        }
                        return@withContext UpdateInfo(
                            hasUpdate = isNewerVersion(latest, currentVersion),
                            currentVersion = currentVersion,
                            latestVersion = latest,
                            downloadUrl = apkUrl,
                            releaseUrl = releaseUrl,
                            notes = notes
                        )
                    }
                }
            }
        }

        // 2. Fallback: Cloudflare Pages static version.json (no API rate limits)
        runCatching {
            val req = Request.Builder()
                .url(FALLBACK_VERSION_URL)
                .header("User-Agent", "BuildMyVpn/$currentVersion")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val obj = json.parseToJsonElement(body).jsonObject
                    val latest = obj["version"]?.jsonPrimitive?.content.orEmpty()
                        .removePrefix("v").removePrefix("V")
                    if (latest.isNotBlank()) {
                        val apkUrl = obj["assets"]?.jsonObject?.get("android")?.jsonPrimitive?.content.orEmpty()
                        val releaseUrl = obj["release_url"]?.jsonPrimitive?.content
                            ?: "https://github.com/Gouryella811/build-myvpn/releases/latest"
                        val notes = obj["notes"]?.jsonPrimitive?.content.orEmpty()
                        return@withContext UpdateInfo(
                            hasUpdate = isNewerVersion(latest, currentVersion),
                            currentVersion = currentVersion,
                            latestVersion = latest,
                            downloadUrl = apkUrl,
                            releaseUrl = releaseUrl,
                            notes = notes
                        )
                    }
                }
            }
        }

        UpdateInfo(
            hasUpdate = false,
            currentVersion = currentVersion,
            latestVersion = currentVersion,
            error = "Unable to reach update server"
        )
    }

    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val tmp = File(dir, "Build-MyVpn-update.apk.part")
        val dest = File(dir, "Build-MyVpn-update.apk")

        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed (HTTP ${resp.code})")
            val body = resp.body ?: error("Empty download body")
            val total = body.contentLength()
            var downloaded = 0L
            var lastPct = -1

            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        val pct = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 50
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        onProgress(100)
        dest
    }

    fun launchApkInstaller(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
