package com.buildmyvpn.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.buildmyvpn.R
import java.util.Calendar

enum class ThemeMode(val key: String) {
    AUTO("auto"),
    LIGHT("light"),
    DARK("dark");

    fun next(): ThemeMode = when (this) {
        AUTO -> LIGHT
        LIGHT -> DARK
        DARK -> AUTO
    }

    fun iconGlyph(): String = when (this) {
        AUTO -> "◐"
        LIGHT -> "☀"
        DARK -> "☾"
    }

    companion object {
        private const val PREFS = "bmv_ui_prefs"
        private const val KEY = "theme_mode"

        fun load(context: Context): ThemeMode {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "auto")
            return entries.firstOrNull { it.key == saved } ?: AUTO
        }

        fun save(context: Context, mode: ThemeMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.key).apply()
        }
    }
}

data class BmvPalette(
    val isDark: Boolean,
    val bg: Color,
    val chrome: Color,
    val surface: Color,
    val elevated: Color,
    val text: Color,
    val textSecondary: Color,
    val muted: Color,
    val border: Color,
    val accent: Color,
    val accentSoft: Color,
    val green: Color,
    val greenSoft: Color,
    val amber: Color,
    val amberSoft: Color,
    val red: Color,
    val redSoft: Color
)

private val LightPalette = BmvPalette(
    isDark = false,
    bg = Color(0xFFFFFFFF),
    chrome = Color(0xFFF7F7F9),
    surface = Color(0xFFF4F4F6),
    elevated = Color(0xFFFFFFFF),
    text = Color(0xFF09090B),
    textSecondary = Color(0xFF3F3F46),
    muted = Color(0xFF71717A),
    border = Color(0xFFE4E4E7),
    accent = Color(0xFF1874FA),
    accentSoft = Color(0x1A1874FA),
    green = Color(0xFF16A34A),
    greenSoft = Color(0x1F16A34A),
    amber = Color(0xFFD97706),
    amberSoft = Color(0x1FD97706),
    red = Color(0xFFDC2626),
    redSoft = Color(0x1ADC2626)
)

private val DarkPalette = BmvPalette(
    isDark = true,
    bg = Color(0xFF09090B),
    chrome = Color(0xFF0E0E11),
    surface = Color(0xFF141418),
    elevated = Color(0xFF18181D),
    text = Color(0xFFFAFAFA),
    textSecondary = Color(0xFFD4D4D8),
    muted = Color(0xFFA1A1AA),
    border = Color(0xFF27272A),
    accent = Color(0xFF3B82F6),
    accentSoft = Color(0x293B82F6),
    green = Color(0xFF22C55E),
    greenSoft = Color(0x2922C55E),
    amber = Color(0xFFF59E0B),
    amberSoft = Color(0x26F59E0B),
    red = Color(0xFFEF4444),
    redSoft = Color(0x26EF4444)
)

val LocalBmvColors = staticCompositionLocalOf { LightPalette }

fun resolveIsDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.AUTO -> {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        hour !in 6..18
    }
}

@Composable
fun BuildMyVpnTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit
) {
    val isDark = resolveIsDark(themeMode)
    val palette = if (isDark) DarkPalette else LightPalette

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            primaryContainer = palette.accentSoft,
            onPrimaryContainer = palette.accent,
            secondary = palette.textSecondary,
            onSecondary = palette.bg,
            secondaryContainer = palette.surface,
            onSecondaryContainer = palette.text,
            background = palette.bg,
            onBackground = palette.text,
            surface = palette.elevated,
            onSurface = palette.text,
            surfaceVariant = palette.surface,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
            outlineVariant = palette.border,
            error = palette.red,
            onError = Color.White,
            errorContainer = palette.redSoft,
            onErrorContainer = palette.red
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            primaryContainer = palette.accentSoft,
            onPrimaryContainer = palette.accent,
            secondary = palette.textSecondary,
            onSecondary = palette.bg,
            secondaryContainer = palette.surface,
            onSecondaryContainer = palette.text,
            background = palette.bg,
            onBackground = palette.text,
            surface = palette.elevated,
            onSurface = palette.text,
            surfaceVariant = palette.surface,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
            outlineVariant = palette.border,
            error = palette.red,
            onError = Color.White,
            errorContainer = palette.redSoft,
            onErrorContainer = palette.red
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val ctrl = WindowCompat.getInsetsController(window, view)
            ctrl.isAppearanceLightStatusBars = !isDark
            ctrl.isAppearanceLightNavigationBars = !isDark
        }
    }

    CompositionLocalProvider(LocalBmvColors provides palette) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

@Composable
fun BmvCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    radius: Dp = 14.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = LocalBmvColors.current
    val shape = RoundedCornerShape(radius)
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = if (selected) c.accentSoft else c.surface,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) c.accent else c.border)
    ) {
        Column(content = content)
    }
}

@Composable
fun BmvBadge(
    text: String,
    bg: Color,
    fg: Color,
    borderColor: Color = Color.Transparent
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
        border = if (borderColor != Color.Transparent) BorderStroke(1.dp, borderColor) else null
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun BmvBrandLogo(size: Dp = 30.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF1874FA), Color(0xFF1536A8))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_stat_brand),
            contentDescription = null,
            modifier = Modifier.size(size * 0.68f)
        )
    }
}
