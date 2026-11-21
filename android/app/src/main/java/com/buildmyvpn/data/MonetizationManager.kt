package com.buildmyvpn.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class BannerSlot(
    val enabled: Boolean = true,
    val badge: String = "推荐",
    val title: String = "",
    val subtitle: String = "",
    val url: String = ""
)

@Serializable
data class RecommendedVpsItem(
    val name: String,
    val price: String,
    val highlights: String,
    val url: String
)

@Serializable
data class CryptoAddress(
    val network: String,
    val address: String
)

@Serializable
data class DonationConfig(
    val note: String = "Build MyVpn 永久免费开源，所有捐赠完全自愿，不解锁任何付费功能。",
    @SerialName("paypal_url") val paypalUrl: String = "",
    @SerialName("kofi_url") val kofiUrl: String = "",
    @SerialName("afdian_url") val afdianUrl: String = "",
    @SerialName("alipay_tip") val alipayTip: String = "",
    @SerialName("wechat_tip") val wechatTip: String = "",
    val crypto: List<CryptoAddress> = emptyList()
)

@Serializable
data class MonetizationConfig(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("website_url") val websiteUrl: String = "https://build-myvpn.pages.dev",
    @SerialName("privacy_url") val privacyUrl: String = "https://build-myvpn.pages.dev/privacy.html",
    @SerialName("vps_guide_url") val vpsGuideUrl: String = "https://build-myvpn.pages.dev/#vps-guide",
    @SerialName("connect_banner") val connectBanner: BannerSlot = BannerSlot(
        enabled = true,
        badge = "推荐",
        title = "还没有高性价比 VPS？查看新手精选服务器",
        subtitle = "最低约 $10/年 · 支持支付宝/信用卡 · 一键自动部署",
        url = "https://build-myvpn.pages.dev/#vps-guide"
    ),
    @SerialName("add_vps_banner") val addVpsBanner: BannerSlot = BannerSlot(
        enabled = true,
        badge = "VPS 推荐",
        title = "新手 3 分钟选购 VPS 指南（实测可用）",
        subtitle = "购买后输入 IP 和密码即可自动部署与开启 BBR",
        url = "https://build-myvpn.pages.dev/#vps-guide"
    ),
    @SerialName("recommended_vps") val recommendedVps: List<RecommendedVpsItem> = listOf(
        RecommendedVpsItem(
            name = "RackNerd",
            price = "约 $10.98 / 年起",
            highlights = "入门性价比极高 · 支持支付宝/PayPal · 洛杉矶机房",
            url = "https://build-myvpn.pages.dev/#vps-guide"
        ),
        RecommendedVpsItem(
            name = "BandwagonHost (搬瓦工)",
            price = "约 $49.99 / 年起",
            highlights = "CN2 GIA 优质直连线路 · 支持支付宝 · 晚高峰稳定",
            url = "https://build-myvpn.pages.dev/#vps-guide"
        ),
        RecommendedVpsItem(
            name = "Vultr",
            price = "约 $5 / 月起（按小时计费）",
            highlights = "全球 30+ 机房随时换 IP · 东京/新加坡/法兰克福",
            url = "https://build-myvpn.pages.dev/#vps-guide"
        )
    ),
    val donations: DonationConfig = DonationConfig(
        crypto = listOf(
            CryptoAddress("USDT (TRC20)", "待在 monetization.json 中配置你的收款地址")
        )
    )
)

/**
 * Fetches hot-updatable sponsor banners, VPS affiliate cards, and donation channels
 * from Cloudflare Pages (primary) with GitHub Raw fallback + local cache.
 * Zero proprietary ad SDKs, zero tracking, never blocks VPN usage.
 */
class MonetizationManager(private val context: Context) {

    companion object {
        private val CONFIG_URLS = listOf(
            "https://build-myvpn.pages.dev/config/monetization.json",
            "https://raw.githubusercontent.com/Gouryella811/build-myvpn/main/website/config/monetization.json"
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cacheFile: File
        get() = File(context.filesDir, "monetization_cache.json")

    private val _config = MutableStateFlow(MonetizationConfig())
    val config: StateFlow<MonetizationConfig> = _config.asStateFlow()

    suspend fun loadAndRefresh() = withContext(Dispatchers.IO) {
        // 1. Load local cache immediately if available
        runCatching {
            if (cacheFile.exists()) {
                val cached = json.decodeFromString(MonetizationConfig.serializer(), cacheFile.readText())
                _config.value = cached
            }
        }

        // 2. Refresh silently from remote endpoints
        for (url in CONFIG_URLS) {
            val fetched = runCatching {
                val req = Request.Builder().url(url).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val body = resp.body?.string().orEmpty()
                    val parsed = json.decodeFromString(MonetizationConfig.serializer(), body)
                    cacheFile.writeText(body)
                    parsed
                }
            }.getOrNull()

            if (fetched != null) {
                _config.value = fetched
                return@withContext
            }
        }
    }
}
