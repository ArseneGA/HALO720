package fr.halo720.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Icônes au trait fin (1,5 px), reprises des tracés SVG de la maquette (viewBox 24). */
object HaloIcons {
    private fun icon(name: String, vararg paths: String, filled: Set<Int> = emptySet(), width: Float = 1.6f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEachIndexed { i, d ->
            val nodes = PathParser().parsePathString(d).toNodes()
            if (i in filled) b.addPath(nodes, fill = SolidColor(Color.White))
            else b.addPath(nodes, stroke = SolidColor(Color.White), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        return b.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

    val Led = icon("led", "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z")
    val Flash = icon("flash", "M13 2L4 14h7l-1 8 9-12h-7l1-8z")
    val FlashOff = icon("flashoff", "M13 2L4 14h7l-1 8 9-12h-7l1-8z", "M4 4l16 16")
    val Torch = icon("torch", "M13 2L4 14h7l-1 8 9-12h-7l1-8z", filled = setOf(0))
    val LedOff = icon("ledoff", "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z", "M4 4l16 16")
    val Timer = icon("timer", circle(12f, 13f, 8f), "M12 9v4l2.5 2M10 2h4")
    val Chevron = icon("chev", "M6 9l6 6 6-6", width = 2.4f)
    val Gear = icon("gear", circle(12f, 12f, 4f), circle(12f, 12f, 9f))
    val Rotate = icon("rot", "M6 5h12v14H6z", "M3 9l3-4 3 4M21 15l-3 4-3-4")
    val LockLand = icon("lockl", "M3 7h18v10H3z", "M10 12h4")
    val LockPort = icon("lockp", "M7 3h10v18H7z", "M12 10v4")
    val Correction = icon("ev", circle(12f, 12f, 9f), "M8 12h3M9.5 10.5v3M13.5 12h3")
    val Grid = icon("grid", "M3 5h18v14H3z", "M9 5v14M15 5v14M3 10h18M3 14h18")
    val Level = icon("level", "M3 12h6M15 12h6", circle(12f, 12f, 2f))
    val Histo = icon("histo", "M3 14h2v6H3zM6 10h2v10H6zM9 6h2v14H9zM12 9h2v11h-2zM15 12h2v8h-2zM18 15h2v5h-2z", filled = setOf(0))
    val Focus = icon("focus", "M5 5h14v14H5z", circle(12f, 12f, 1.6f), filled = setOf(1))
    val Metering = icon("meter", circle(12f, 12f, 9f), circle(12f, 12f, 3f))
    val Zebra = icon("zebra", "M4 18l5-6 4 4 7-9", "M15 7h5v5")
    val Sound = icon("sound", "M11 5L6 9H3v6h3l5 4z", "M15.5 9a4 4 0 0 1 0 6")
    val Back = icon("back", "M6 9l6 6 6-6", width = 2.2f)
    val Share = icon("share", "M12 3v12M7 8l5-5 5 5M5 14v5h14v-5", width = 2.2f)
    val Thermo = icon("thermo", "M10 4a2 2 0 0 1 4 0v10a4 4 0 1 1-4 0z", "M12 9v7")
    val Bolt = icon("bolt", "M13 2L4 14h7l-1 8 9-12h-7z")
    val Wifi = icon("wifi", "M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0", circle(12f, 19.5f, 1f), filled = setOf(1))
    val Plus = icon("plus", "M12 5v14M5 12h14", width = 2f)
    val Close = icon("close", "M6 6l12 12M18 6L6 18", width = 2f)
}

@Composable
fun HIcon(v: ImageVector, tint: Color = Halo.Text, size: Dp = 19.dp, modifier: Modifier = Modifier) =
    Icon(v, contentDescription = null, tint = tint, modifier = modifier.size(size))
