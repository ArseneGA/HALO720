package fr.halo720.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/** Description d'une molette : libellés, graduations majeures, position AUTO. */
class DialSpec(
    val key: String,
    val title: String,
    val labels: List<String>,
    val tickDp: Float,
    val major: (Int) -> Boolean = { true },
    val half: (Int) -> Boolean = { false },
    val autoIndex: Int = -1,
)

/**
 * Règle horizontale graduée qu'on fait défiler au pouce, avec inertie et crans.
 * La valeur sous l'aiguille rouge est appliquée à chaque cran (vibration courte).
 */
@Composable
fun RulerDial(spec: DialSpec, index: Int, onIndex: (Int) -> Unit, onTouch: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val view = LocalView.current
    val tw = with(density) { spec.tickDp.dp.toPx() }
    val maxOff = (spec.labels.size - 1) * tw
    val offset = remember(spec.key) { Animatable(index * tw) }
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val onIndexU = rememberUpdatedState(onIndex)
    val onTouchU = rememberUpdatedState(onTouch)
    val last = remember(spec.key) { intArrayOf(index) }

    fun report(off: Float) {
        val i = (off / tw).roundToInt().coerceIn(0, spec.labels.size - 1)
        if (i != last[0]) {
            last[0] = i
            view.performHapticFeedback(if (spec.major(i)) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            onIndexU.value(i)
        }
    }

    // valeur changée de l'extérieur (profil, autre appareil)
    LaunchedEffect(spec.key, index) {
        if (!offset.isRunning && index != last[0]) {
            last[0] = index
            offset.animateTo(index * tw, tween(180))
        }
    }

    Box(modifier
        .clip(RoundedCornerShape(20.dp))
        .background(Color(0x8C141416))
        .pointerInput(spec.key) {
            val vt = VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = { scope.launch { offset.stop() }; vt.resetTracking(); onTouchU.value() },
                onDragEnd = {
                    val v = -vt.calculateVelocity().x
                    scope.launch {
                        offset.updateBounds(0f, maxOff)
                        offset.animateDecay(v, exponentialDecay(frictionMultiplier = 2.2f)) { report(value) }
                        val snap = ((offset.value / tw).roundToInt().coerceIn(0, spec.labels.size - 1)) * tw
                        offset.animateTo(snap, tween(120)) { report(value) }
                        onTouchU.value()
                    }
                },
                onDragCancel = {},
            ) { change, dx ->
                vt.addPosition(change.uptimeMillis, change.position)
                change.consume()
                scope.launch {
                    offset.snapTo((offset.value - dx).coerceIn(0f, maxOff))
                    report(offset.value)
                }
                onTouchU.value()
            }
        }
        .pointerInput(spec.key) {
            detectTapGestures { p ->
                // toucher une graduation y saute
                val i = ((offset.value + p.x - size.width / 2f) / tw).roundToInt().coerceIn(0, spec.labels.size - 1)
                scope.launch { offset.animateTo(i * tw, tween(160)); report(offset.value) }
                onTouchU.value()
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val off = offset.value
            val first = ((off - cx) / tw).toInt() - 1
            val last2 = ((off + cx) / tw).toInt() + 1
            for (i in first.coerceAtLeast(0)..last2.coerceAtMost(spec.labels.size - 1)) {
                val x = cx + i * tw - off
                val maj = spec.major(i)
                val half = !maj && spec.half(i)
                val (top, h, w, a) = when {
                    maj -> listOf(6.dp.toPx(), 11.dp.toPx(), 1.3.dp.toPx(), 1f)
                    half -> listOf(8.dp.toPx(), 8.dp.toPx(), 1.dp.toPx(), 0.66f)
                    else -> listOf(9.dp.toPx(), 6.dp.toPx(), 1.dp.toPx(), 0.4f)
                }
                drawLine(Color.White.copy(alpha = a), Offset(x, top), Offset(x, top + h), w)
                if (maj) {
                    val auto = i == spec.autoIndex
                    val t = measurer.measure(spec.labels[i], cam(8.sp, color = if (auto) Halo.Ir else Color(0xDDFFFFFF)))
                    drawText(t, topLeft = Offset(x - t.size.width / 2f, 20.dp.toPx()))
                }
            }
            // estompage des bords + aiguille
            drawRect(Brush.horizontalGradient(0f to Color(0xD90A0A0C), 0.25f to Color.Transparent,
                0.75f to Color.Transparent, 1f to Color(0xD90A0A0C)))
            drawLine(Halo.Ir.copy(alpha = 0.35f), Offset(cx, 4.dp.toPx()), Offset(cx, size.height - 4.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
            drawLine(Halo.Ir, Offset(cx, 5.dp.toPx()), Offset(cx, size.height - 5.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Géométrie de l'arc de zoom (grand disque translucide qui monte du bas). */
object ZoomArc {
    const val ZMIN = 1.0
    const val ZMAX = 5.0
    const val K = 36.0               // degrés par unité de ln(zoom)

    fun quant(z: Double) = (z.coerceIn(ZMIN, ZMAX) * 10).roundToInt() / 10.0

    /** Nouveau zoom pour un glissement horizontal dx (px) depuis z0, rayon R. Gauche = zoome. */
    fun zoomFor(z0: Double, dx: Float, radius: Float): Double {
        val pxPerLn = radius * K * PI / 180.0
        return quant(exp(ln(z0) - dx / pxPerLn))
    }

    fun mpx(z: Double): String {
        val m = 11.9 / (z * z)
        return (if (m >= 1) fr1(m) else fr2(m)) + " Mpx"
    }

    private fun fr1(x: Double) = String.format(java.util.Locale.FRANCE, "%.1f", x)
    private fun fr2(x: Double) = String.format(java.util.Locale.FRANCE, "%.2f", x)
}

/**
 * Dessin de l'arc : graduations au pas de 0,1, repères 1 à 5 avec la focale équivalente,
 * aiguille rouge en haut, bande claire sur la zone de faible définition (3,5× à 5×).
 * [cx], [top] : centre horizontal et haut de l'arc (px) ; [radius] : rayon.
 */
@Composable
fun ZoomArcOverlay(z: Double, cx: Float, top: Float, radius: Float, focal1x: Int, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val cy = top + radius
        fun ang(v: Double) = ((ln(v) - ln(z)) * ZoomArc.K).toFloat()
        fun p(a: Float, r: Float): Offset {
            val t = a * PI.toFloat() / 180f
            return Offset(cx + r * sin(t), cy - r * cos(t))
        }
        drawCircle(Color(0x94060608), radius, Offset(cx, cy))
        drawCircle(Color(0x24FFFFFF), radius, Offset(cx, cy), style = Stroke(1f))
        // zone < 1 Mpx
        val b1 = ang(3.5).coerceAtLeast(-80f)
        val b2 = ang(5.0).coerceAtMost(80f)
        if (b1 < b2) {
            val r = radius - 3.dp.toPx()
            drawArc(Color(0x52FFFFFF), -90f + b1, b2 - b1, false, Offset(cx - r, cy - r),
                androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(4.dp.toPx()))
        }
        for (i in 10..50) {
            val v = i / 10.0
            val a = ang(v)
            if (abs(a) > 78) continue
            val maj = i % 10 == 0
            val half = i % 5 == 0
            val len = (if (maj) 12 else if (half) 8 else 5).dp.toPx()
            drawLine(Color.White.copy(alpha = if (maj) 1f else if (half) 0.7f else 0.38f),
                p(a, radius - 7.dp.toPx()), p(a, radius - 7.dp.toPx() - len), if (maj) 1.5.dp.toPx() else 1.dp.toPx(), StrokeCap.Round)
            if (maj) {
                val c = p(a, radius - 31.dp.toPx())
                rotate(a, c) {
                    val t1 = measurer.measure("${i / 10}", cam(12.sp))
                    drawText(t1, topLeft = Offset(c.x - t1.size.width / 2f, c.y - t1.size.height / 2f))
                    val t2 = measurer.measure("${focal1x * i / 10}mm", cam(7.sp, color = Color(0xA6FFFFFF)))
                    drawText(t2, topLeft = Offset(c.x - t2.size.width / 2f, c.y + t1.size.height / 2f - 3.dp.toPx()))
                }
            }
        }
        drawLine(Halo.Ir, p(0f, radius - 4.dp.toPx()), p(0f, radius - 24.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        val big = measurer.measure("${String.format(java.util.Locale.FRANCE, "%.1f", z).replace(",0", "")}×",
            cam(26.sp, androidx.compose.ui.text.font.FontWeight.Medium, shadow = true))
        drawText(big, topLeft = Offset(cx - big.size.width / 2f, top - 30.dp.toPx() - big.size.height / 2f))
        val small = measurer.measure("${ZoomArc.mpx(z)} · ${(focal1x * z).roundToInt()} mm", mono(9.sp, color = Color(0xBFFFFFFF)))
        drawText(small, topLeft = Offset(cx - small.size.width / 2f, top - 12.dp.toPx() - small.size.height / 2f))
    }
}
