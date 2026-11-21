package com.buildmyvpn.rules

import android.content.Context
import android.telephony.TelephonyManager
import com.buildmyvpn.data.model.RoutingRule
import java.util.Locale

/**
 * Privacy-first region detector for the "General" smart routing rule.
 * Uses zero network requests: prefers TelephonyManager network/SIM ISO country code,
 * falling back to system Locale.
 */
object RegionDetector {

    fun detectEffectiveRule(context: Context, userRule: RoutingRule): RoutingRule {
        if (userRule != RoutingRule.GENERAL) return userRule

        val countryIso = resolveCountryIso(context).uppercase(Locale.US)
        return when (countryIso) {
            "CN" -> RoutingRule.CN
            "IR" -> RoutingRule.IR
            "RU" -> RoutingRule.RU
            else -> RoutingRule.GENERAL
        }
    }

    private fun resolveCountryIso(context: Context): String {
        val tm = runCatching {
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }.getOrNull()

        val networkIso = tm?.networkCountryIso?.trim().orEmpty()
        if (networkIso.length == 2) return networkIso

        val simIso = tm?.simCountryIso?.trim().orEmpty()
        if (simIso.length == 2) return simIso

        return Locale.getDefault().country.orEmpty()
    }
}
