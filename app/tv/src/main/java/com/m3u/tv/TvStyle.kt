package com.m3u.tv

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Colour tokens of the "Gece" TV design system (docs/fork/DESIGN_SYSTEM.md).
 *
 * Dark ink surfaces, a near-white focus container that reads from the couch, a crimson broadcast
 * accent reserved for live state and channel numbers, and three status hues.
 */
object TvColors {
    // ink scale, darkest first
    val Background = Color(0xFF07090D)
    val BackgroundSoft = Color(0xFF0D1118)
    val Surface = Color(0xFF141A24)
    val SurfaceRaised = Color(0xFF1D2533)
    val Outline = Color(0x1AFFFFFF)

    // focus is a state pair: container + content
    val Focus = Color(0xFFF4F6FA)
    val OnFocus = Color(0xFF0A0D12)

    // broadcast accent: live badges, channel numbers, the brand mark
    val Live = Color(0xFFD91F35)
    val OnLive = Color(0xFFFFFFFF)

    // status
    val Accent = Color(0xFFFFB547)
    val Success = Color(0xFF34D399)
    val Danger = Color(0xFFFF6B6B)

    val TextPrimary = Color(0xFFF4F6FA)
    val TextSecondary = Color(0xFFAAB4C3)
    val TextMuted = Color(0xFF7C8798)

    // controlled contrast behind text drawn over video
    val Scrim = Color(0xB8000000)
}

object TvFonts {
    val Body = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semi_bold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold)
    )
    // wide geometric face for channel numbers and big figures
    val Accent = FontFamily(
        Font(R.font.lexend_exa_medium, FontWeight.Medium)
    )
}

enum class TvDestination(
    val icon: ImageVector,
    // reachable from the navigation rail; the rest open from inside other screens
    val inRail: Boolean = true,
) {
    Live(Icons.Rounded.LiveTv),
    Home(Icons.Rounded.Home, inRail = false),
    Favorites(Icons.Rounded.Favorite),
    Library(Icons.AutoMirrored.Rounded.PlaylistPlay),
    Status(Icons.Rounded.Settings),
    Diagnostics(Icons.Rounded.MonitorHeart, inRail = false),
}

enum class TvSurface {
    Browse,
    Player
}
