package com.itube.tv.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.itube.tv.R

object C {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF1C1C1E)
    val Surface2 = Color(0xFF2C2C2E)
    val Surface3 = Color(0xFF3A3A3C)
    val Separator = Color(0x33FFFFFF)
    val Text = Color(0xFFFFFFFF)
    val Text2 = Color(0x99EBEBF5)
    val Text3 = Color(0x59EBEBF5)
    val Accent = Color(0xFF0A84FF)
    val Accent2 = Color(0xFF5E5CE6)
    val Red = Color(0xFFFF453A)
    /** YouTube red, used sparingly (live badge, logo). */
    val YouTube = Color(0xFFFF0033)
    val Green = Color(0xFF30D158)
    val Yellow = Color(0xFFFFD60A)
    val Orange = Color(0xFFFF9F0A)
    val Focus = Color(0xFFFFFFFF)
    val OnFocus = Color(0xFF000000)
    val Scrim = Color(0xCC000000)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

object T {
    private val base = TextStyle(fontFamily = Inter, color = Color.Unspecified, letterSpacing = 0.sp)
    val LargeTitle = base.copy(fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, lineHeight = 44.sp)
    val Title1 = base.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, lineHeight = 34.sp)
    val Title2 = base.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, lineHeight = 28.sp)
    val Title3 = base.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
    val Headline = base.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    val Body = base.copy(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp)
    val Callout = base.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp)
    val Subhead = base.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp)
    val Footnote = base.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
    val Caption = base.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp)
}

@Composable
fun ITubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent,
            background = C.Background,
            surface = C.Surface,
            onBackground = C.Text,
            onSurface = C.Text,
        ),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides C.Text,
            LocalTextStyle provides T.Body,
            content = content,
        )
    }
}
