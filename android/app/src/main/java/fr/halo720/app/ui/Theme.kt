package fr.halo720.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.halo720.app.R

/** Couleurs de la spec : noir, blanc cassé, rouge 720 nm comme seul accent. */
object Halo {
    val Bg = Color(0xFF000000)
    val Page = Color(0xFF0A0A0B)
    val Surface = Color(0xFF141416)
    val Glass = Color(0x8C141416)          // verre fumé ≈ 55 %
    val GlassStrong = Color(0xE6181819)
    val Line = Color(0x1FFFFFFF)
    val Hair = Color(0x0FFFFFFF)
    val Text = Color(0xFFF2F0EC)
    val Dim = Color(0xFF8D8A84)
    val Faint = Color(0xFF55534F)
    val Ir = Color(0xFFE5243B)
    val IrSoft = Color(0x29E5243B)
    val Ok = Color(0xFF3FD07A)
    val Warn = Color(0xFFF2B233)
}

val Saira = FontFamily(
    Font(R.font.saira_400, FontWeight.Normal),
    Font(R.font.saira_500, FontWeight.Medium),
    Font(R.font.saira_600, FontWeight.SemiBold),
)
val Inter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold),
)
val Mono = FontFamily(
    Font(R.font.jetbrains_500, FontWeight.Medium),
    Font(R.font.jetbrains_700, FontWeight.Bold),
)

/** Ombre légère sous les textes posés sur l'image (lisibles sur un ciel clair). */
val ImageShadow = Shadow(Color(0xAA000000), Offset(0f, 1f), 6f)

fun cam(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = Halo.Text, shadow: Boolean = false) =
    TextStyle(fontFamily = Saira, fontSize = size, fontWeight = weight, color = color,
        shadow = if (shadow) ImageShadow else null, letterSpacing = 0.02.em)

fun mono(size: TextUnit, weight: FontWeight = FontWeight.Medium, color: Color = Halo.Text) =
    TextStyle(fontFamily = Mono, fontSize = size, fontWeight = weight, color = color,
        fontFeatureSettings = "tnum")

fun sans(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = Halo.Text) =
    TextStyle(fontFamily = Inter, fontSize = size, fontWeight = weight, color = color)

/** Libellés : 7–9 px, majuscules, espacement large, gris. */
fun label(size: TextUnit = 8.5.sp, color: Color = Halo.Dim) =
    TextStyle(fontFamily = Inter, fontSize = size, fontWeight = FontWeight.SemiBold, color = color,
        letterSpacing = 0.14.em)

@Composable
fun HaloTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Halo.Ir, onPrimary = Halo.Text, background = Halo.Page, surface = Halo.Surface,
            onBackground = Halo.Text, onSurface = Halo.Text, secondary = Halo.Dim,
        ),
        content = content,
    )
}
