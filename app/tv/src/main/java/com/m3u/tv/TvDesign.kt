package com.m3u.tv

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Layout, type and motion tokens of the "Gece" TV design system. Colours live in [TvColors].
 * Sizes assume the 960 × 540 dp canvas every 1080p Android TV box reports; see
 * docs/fork/DESIGN_SYSTEM.md for the rationale and usage rules.
 */
object TvSpacing {
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
    val Xxl = 32.dp
    val Xxxl = 48.dp

    // overscan-safe margins (5% of the canvas), the shared leading edge of every screen
    val SafeHorizontal = 48.dp
    val SafeVertical = 28.dp
}

object TvShapes {
    val Xs = RoundedCornerShape(4.dp)
    val Sm = RoundedCornerShape(8.dp)
    val Md = RoundedCornerShape(12.dp)
    val Lg = RoundedCornerShape(16.dp)
    val Xl = RoundedCornerShape(24.dp)
    val Pill = RoundedCornerShape(percent = 50)
}

object TvType {
    // figures that must be read from across the room: number entry, big clocks
    val Numeral = TextStyle(fontFamily = TvFonts.Accent, fontWeight = FontWeight.Medium, fontSize = 64.sp, lineHeight = 72.sp)
    val Display = TextStyle(fontFamily = TvFonts.Body, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 48.sp)
    val Headline = TextStyle(fontFamily = TvFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp)
    val Title = TextStyle(fontFamily = TvFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
    val TitleSmall = TextStyle(fontFamily = TvFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
    val Body = TextStyle(fontFamily = TvFonts.Body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val Label = TextStyle(fontFamily = TvFonts.Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp)
    val Caption = TextStyle(
        fontFamily = TvFonts.Body,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp,
    )
    // channel numbers inside badges
    val Number = TextStyle(fontFamily = TvFonts.Accent, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 18.sp)
}

object TvMotion {
    const val FocusMillis = 150
    const val OverlayMillis = 200
    const val BannerHoldMillis = 4_000L
    const val ToastHoldMillis = 2_500L
    const val TimeshiftHoldMillis = 5_000L

    // list rows grow less than cards so neighbours stay readable
    const val RowFocusScale = 1.03f
    const val CardFocusScale = 1.08f
}
