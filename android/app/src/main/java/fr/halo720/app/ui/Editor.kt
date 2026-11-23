package fr.halo720.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.halo720.app.HaloApp
import fr.halo720.app.data.Photo
import fr.halo720.app.data.fr1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Réglages de retouche. crop : rectangle normalisé (0..1) dans le cadre de l'image. */
data class EditParams(
    val ev: Float = 0f,          // −2..+2 IL
    val contrast: Float = 0f,    // −1..+1
    val temp: Float = 0f,        // −1..+1 (balance R/B de l'IR)
    val sat: Float = 0f,         // −1..+1
    val angle: Float = 0f,       // −10..+10°
    val crop: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
    val ratio: Float? = null,    // largeur / hauteur en pixels, null = libre
) {
    val isIdentity get() = ev == 0f && contrast == 0f && temp == 0f && sat == 0f && angle == 0f &&
        crop[0] == 0f && crop[1] == 0f && crop[2] == 1f && crop[3] == 1f

    /** Matrice couleur 4×5 (android.graphics.ColorMatrix) : exposition, température, saturation, contraste. */
    fun colorMatrix(): android.graphics.ColorMatrix {
        val m = android.graphics.ColorMatrix()
        val k = Math.pow(2.0, ev / 2.2).toFloat()
        m.setScale(k * (1f + 0.22f * temp), k, k * (1f - 0.22f * temp), 1f)
        m.postConcat(android.graphics.ColorMatrix().apply { setSaturation(1f + sat) })
        val c = 1f + 0.6f * contrast
        val t = 128f * (1f - c)
        m.postConcat(android.graphics.ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        return m
    }

    override fun equals(other: Any?) = other is EditParams && ev == other.ev && contrast == other.contrast && temp == other.temp &&
        sat == other.sat && angle == other.angle && crop.contentEquals(other.crop) && ratio == other.ratio
    override fun hashCode() = listOf(ev, contrast, temp, sat, angle, ratio).hashCode() * 31 + crop.contentHashCode()
}

/** Agrandissement pour que l'image tournée de `deg` couvre encore tout son cadre (pas de coins vides). */
internal fun coverScale(w: Float, h: Float, deg: Float): Float {
    val a = Math.toRadians(abs(deg).toDouble())
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    return max((w * c + h * s) / w, (w * s + h * c) / h)
}

private enum class Tool(val label: String) {
    EV("Exposition"), CONTRAST("Contraste"), TEMP("Température IR"), SAT("Saturation"), ANGLE("Redressement"), CROP("Recadrage")
}

/** Retouche simple : aperçu en direct sur une image réduite, rendu pleine définition à l'enregistrement. */
@Composable
fun EditorPage(photo: Photo, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HaloApp
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var src by remember { mutableStateOf<ImgSource?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var fullDims by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var params by remember { mutableStateOf(EditParams()) }
    var tool by remember { mutableStateOf(Tool.EV) }
    var dragging by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(photo.stem) {
        val s = GalleryImages.resolve(ctx, photo, allowNetwork = true)
        if (s == null) { error = "Image indisponible"; return@LaunchedEffect }
        val b = withContext(Dispatchers.IO) { GalleryImages.decode(ctx, s, 1600) }
        val raw = withContext(Dispatchers.IO) { GalleryImages.bounds(ctx, s) }
        src = s
        preview = b
        fullDims = raw?.let { if (s.rotation % 180 != 0) it.second to it.first else it }
    }

    fun save() {
        val s = src ?: return
        val dims = fullDims ?: return
        val p = params
        saving = "Rendu pleine définition"
        scope.launch {
            try {
                val uri = withContext(Dispatchers.Default) {
                    val out = renderFull(ctx, s, dims, p)
                    saving = "Enregistrement"
                    val u = app.store.publishBitmap(out, "${photo.stem}_edit.jpg", 94,
                        GalleryImages.exifWriter(photo, s.look, "retouche"))
                    out.recycle()
                    u
                }
                GalleryImages.addVersion(ctx, photo.stem, uri, s.look, true)
                onClose()
            } catch (e: Throwable) {
                error = if (e is OutOfMemoryError) "Mémoire insuffisante" else "Échec · ${e.message ?: "erreur"}"
            } finally { saving = null }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Halo.Page)) {
        val landscape = maxWidth > maxHeight
        val top: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Annuler", style = sans(13.sp, FontWeight.Medium, Halo.Dim), modifier = Modifier.clickable(onClick = onClose).padding(vertical = 8.dp, horizontal = 4.dp))
                Text("Retouche", style = sans(15.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f).padding(start = 12.dp))
                if (!params.isIdentity) {
                    Text("Réinitialiser", style = sans(11.sp, color = Halo.Dim),
                        modifier = Modifier.clickable { params = EditParams() }.padding(8.dp))
                }
                ActionButton("Enregistrer", ::save, primary = true, enabled = src != null && saving == null && !params.isIdentity)
            }
        }
        val image: @Composable (Modifier) -> Unit = { m ->
            Box(m.background(Color.Black).clipToBounds(), contentAlignment = Alignment.Center) {
                val b = preview
                if (b == null) {
                    if (error == null) CircularProgressIndicator(Modifier.size(18.dp), color = Halo.Dim, strokeWidth = 1.5.dp)
                } else {
                    EditCanvas(b, params, tool == Tool.CROP, dragging, fullDims) { params = it }
                }
            }
        }
        val controls: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    for (t in Tool.entries) {
                        val on = t == tool
                        val changed = when (t) {
                            Tool.EV -> params.ev != 0f; Tool.CONTRAST -> params.contrast != 0f; Tool.TEMP -> params.temp != 0f
                            Tool.SAT -> params.sat != 0f; Tool.ANGLE -> params.angle != 0f
                            Tool.CROP -> !(params.crop[0] == 0f && params.crop[1] == 0f && params.crop[2] == 1f && params.crop[3] == 1f)
                        }
                        Column(Modifier.clickable { tool = t; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            .padding(horizontal = 9.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(t.label, style = sans(11.5.sp, if (on) FontWeight.SemiBold else FontWeight.Normal, if (on) Halo.Text else Halo.Dim))
                            Box(Modifier.padding(top = 4.dp).size(4.dp).background(if (changed) (if (on) Halo.Ir else Halo.Dim) else Color.Transparent, RoundedCornerShape(2.dp)))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    when (tool) {
                        Tool.EV -> ParamSlider("EXPOSITION", params.ev, -2f, 2f, { ev(it) }, { params = params.copy(ev = it) }) { dragging = it }
                        Tool.CONTRAST -> ParamSlider("CONTRASTE", params.contrast, -1f, 1f, { pct(it) }, { params = params.copy(contrast = it) }) { dragging = it }
                        Tool.TEMP -> ParamSlider("TEMPÉRATURE IR", params.temp, -1f, 1f, { pct(it) }, { params = params.copy(temp = it) }) { dragging = it }
                        Tool.SAT -> ParamSlider("SATURATION", params.sat, -1f, 1f, { pct(it) }, { params = params.copy(sat = it) }) { dragging = it }
                        Tool.ANGLE -> ParamSlider("REDRESSEMENT", params.angle, -10f, 10f, { deg(it) }, { params = params.copy(angle = it) }) { dragging = it }
                        Tool.CROP -> RatioRow(params.ratio) { r -> params = params.copy(ratio = r, crop = fitRatio(r, fullDims)) }
                    }
                }
            }
        }

        if (landscape) {
            Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                image(Modifier.weight(1.4f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    top()
                    Spacer(Modifier.height(8.dp))
                    controls()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                top()
                image(Modifier.weight(1f).fillMaxWidth())
                controls()
                Spacer(Modifier.height(6.dp))
            }
        }
        saving?.let { BusyPill(it, Modifier.align(Alignment.BottomCenter)) }
        error?.let { ErrorPill(it, Modifier.align(Alignment.BottomCenter)) }
    }
}

private fun ev(v: Float): String = if (abs(v) < 0.005f) "0 IL" else (if (v > 0) "+" else "−") + fr1(abs(v).toDouble()) + " IL"
private fun pct(v: Float): String = (v * 100).roundToInt().let { if (it == 0) "0" else if (it > 0) "+$it" else "−${-it}" }
private fun deg(v: Float): String = (if (v < -0.04f) "−" else if (v > 0.04f) "+" else "") + String.format(Locale.FRANCE, "%.1f°", abs(v))

/** Rectangle normalisé le plus grand possible au rapport demandé, centré. */
private fun fitRatio(r: Float?, dims: Pair<Int, Int>?): FloatArray {
    if (r == null || dims == null) return floatArrayOf(0f, 0f, 1f, 1f)
    val img = dims.first.toFloat() / dims.second
    return if (r > img) {
        val h = img / r
        floatArrayOf(0f, (1 - h) / 2, 1f, (1 + h) / 2)
    } else {
        val w = r / img
        floatArrayOf((1 - w) / 2, 0f, (1 + w) / 2, 1f)
    }
}

// ---------------------------------------------------------------------------- aperçu + cadre de recadrage

@Composable
private fun EditCanvas(b: Bitmap, p: EditParams, cropMode: Boolean, dragging: Boolean, dims: Pair<Int, Int>?, onChange: (EditParams) -> Unit) {
    val density = LocalDensity.current
    val img = remember(b) { b.asImageBitmap() }
    val cm = remember(p.ev, p.contrast, p.temp, p.sat) { ColorFilter.colorMatrix(ColorMatrix(p.colorMatrix().array)) }
    val cur by rememberUpdatedState(p)
    val change by rememberUpdatedState(onChange)
    BoxWithConstraints(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
        val aw = constraints.maxWidth.toFloat()
        val ah = constraints.maxHeight.toFloat()
        val ia = b.width.toFloat() / b.height
        val fw = min(aw, ah * ia)
        val fh = fw / ia
        val wDp = with(density) { fw.toDp() }
        val hDp = with(density) { fh.toDp() }
        val s = coverScale(fw, fh, p.angle)
        Box(Modifier.size(wDp, hDp).clipToBounds()) {
            Image(img, null, contentScale = ContentScale.FillBounds, colorFilter = cm,
                modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = p.angle; scaleX = s; scaleY = s })
            val handle = with(density) { 26.dp.toPx() }
            var grab by remember { mutableStateOf(-1) }
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(cropMode, fw, fh) {
                        if (!cropMode) return@pointerInput
                        detectDragGestures(
                            onDragStart = { o ->
                                val c = cur.crop
                                val l = c[0] * fw; val t = c[1] * fh; val r = c[2] * fw; val bt = c[3] * fh
                                fun near(x: Float, y: Float) = abs(o.x - x) < handle && abs(o.y - y) < handle
                                grab = when {
                                    near(l, t) -> 0; near(r, t) -> 1; near(r, bt) -> 2; near(l, bt) -> 3
                                    cur.ratio == null && abs(o.x - l) < handle && o.y in t..bt -> 4
                                    cur.ratio == null && abs(o.x - r) < handle && o.y in t..bt -> 5
                                    cur.ratio == null && abs(o.y - t) < handle && o.x in l..r -> 6
                                    cur.ratio == null && abs(o.y - bt) < handle && o.x in l..r -> 7
                                    o.x in l..r && o.y in t..bt -> 8
                                    else -> -1
                                }
                            },
                            onDragEnd = { grab = -1 },
                            onDragCancel = { grab = -1 },
                        ) { ch, d ->
                            ch.consume()
                            if (grab < 0) return@detectDragGestures
                            change(cur.copy(crop = dragCrop(cur.crop, grab, d.x / fw, d.y / fh, cur.ratio, dims)))
                        }
                    }
                    .pointerInput(cropMode) {
                        if (!cropMode) return@pointerInput
                        detectTapGestures(onDoubleTap = { change(cur.copy(crop = fitRatio(cur.ratio, dims))) })
                    },
            ) {
                val c = p.crop
                val l = c[0] * size.width; val t = c[1] * size.height; val r = c[2] * size.width; val bt = c[3] * size.height
                val full = c[0] == 0f && c[1] == 0f && c[2] == 1f && c[3] == 1f
                if (!full || cropMode) {
                    val shade = Color(0x99000000)
                    drawRect(shade, Offset.Zero, Size(size.width, t))
                    drawRect(shade, Offset(0f, bt), Size(size.width, size.height - bt))
                    drawRect(shade, Offset(0f, t), Size(l, bt - t))
                    drawRect(shade, Offset(r, t), Size(size.width - r, bt - t))
                }
                if (cropMode) {
                    drawRect(Color.White.copy(alpha = 0.9f), Offset(l, t), Size(r - l, bt - t), style = Stroke(1.2f * density.density))
                    val cl = 14f * density.density
                    val sw = 2.5f * density.density
                    for ((x, y, dx, dy) in listOf(
                        floatArrayOf(l, t, 1f, 1f), floatArrayOf(r, t, -1f, 1f), floatArrayOf(r, bt, -1f, -1f), floatArrayOf(l, bt, 1f, -1f),
                    ).map { listOf(it[0], it[1], it[2], it[3]) }) {
                        drawLine(Color.White, Offset(x, y), Offset(x + dx * cl, y), sw)
                        drawLine(Color.White, Offset(x, y), Offset(x, y + dy * cl), sw)
                    }
                }
                if (dragging || cropMode || grab >= 0) {
                    val gc = Color.White.copy(alpha = if (dragging) 0.45f else 0.25f)
                    for (i in 1..2) {
                        val x = l + (r - l) * i / 3f
                        val y = t + (bt - t) * i / 3f
                        drawLine(gc, Offset(x, t), Offset(x, bt), 1f)
                        drawLine(gc, Offset(l, y), Offset(r, y), 1f)
                    }
                    if (dragging && !cropMode) {
                        // grille fine pour le redressement
                        val fine = Color.White.copy(alpha = 0.14f)
                        for (i in 1..8) {
                            if (i % 3 == 0) continue
                            drawLine(fine, Offset(size.width * i / 9f, 0f), Offset(size.width * i / 9f, size.height), 1f)
                            drawLine(fine, Offset(0f, size.height * i / 9f), Offset(size.width, size.height * i / 9f), 1f)
                        }
                    }
                }
            }
        }
    }
}

/** Déplace un coin (0..3), un bord (4..7) ou tout le cadre (8). dx, dy normalisés. */
private fun dragCrop(c: FloatArray, grab: Int, dx: Float, dy: Float, ratio: Float?, dims: Pair<Int, Int>?): FloatArray {
    val min = 0.08f
    var l = c[0]; var t = c[1]; var r = c[2]; var b = c[3]
    when (grab) {
        8 -> {
            val w = r - l; val h = b - t
            l = (l + dx).coerceIn(0f, 1f - w); t = (t + dy).coerceIn(0f, 1f - h); r = l + w; b = t + h
        }
        4 -> l = (l + dx).coerceIn(0f, r - min)
        5 -> r = (r + dx).coerceIn(l + min, 1f)
        6 -> t = (t + dy).coerceIn(0f, b - min)
        7 -> b = (b + dy).coerceIn(t + min, 1f)
        else -> {
            if (ratio == null || dims == null) {
                if (grab == 0 || grab == 3) l = (l + dx).coerceIn(0f, r - min) else r = (r + dx).coerceIn(l + min, 1f)
                if (grab == 0 || grab == 1) t = (t + dy).coerceIn(0f, b - min) else b = (b + dy).coerceIn(t + min, 1f)
            } else {
                // rapport fixe : la largeur suit le doigt, la hauteur suit le rapport ; coin opposé fixe
                val ia = dims.first.toFloat() / dims.second
                val k = ia / ratio   // hauteur normalisée par unité de largeur normalisée
                val sx = if (grab == 0 || grab == 3) -1f else 1f
                val sy = if (grab == 0 || grab == 1) -1f else 1f
                var w = (r - l) + sx * dx
                val ax = if (sx < 0) r else l
                val ay = if (sy < 0) b else t
                val maxW = min(if (sx < 0) ax else 1f - ax, (if (sy < 0) ay else 1f - ay) / k)
                w = w.coerceIn(min, maxW)
                val h = w * k
                if (sx < 0) { l = ax - w; r = ax } else { l = ax; r = ax + w }
                if (sy < 0) { t = ay - h; b = ay } else { t = ay; b = ay + h }
            }
        }
    }
    return floatArrayOf(l, t, r, b)
}

// ---------------------------------------------------------------------------- curseurs

@Composable
private fun ParamSlider(
    name: String, value: Float, lo: Float, hi: Float, fmt: (Float) -> String,
    onValue: (Float) -> Unit, onDrag: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val v by rememberUpdatedState(value)
    val set by rememberUpdatedState(onValue)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = label(8.5.sp), modifier = Modifier.weight(1f))
            Text(fmt(value), style = mono(11.sp, color = if (value != 0f) Halo.Text else Halo.Dim),
                modifier = Modifier.clickable { set(0f) }.padding(horizontal = 4.dp, vertical = 2.dp))
        }
        Canvas(
            Modifier.fillMaxWidth().height(34.dp)
                .pointerInput(lo, hi) {
                    fun snap(n: Float) = if (abs(n) < (hi - lo) * 0.015f) 0f else n
                    detectDragGestures(
                        onDragStart = { onDrag(true) },
                        onDragEnd = { onDrag(false) },
                        onDragCancel = { onDrag(false) },
                    ) { ch, d ->
                        ch.consume()
                        val n = snap((v + d.x / size.width * (hi - lo)).coerceIn(lo, hi))
                        if ((n == 0f) != (v == 0f)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        set(n)
                    }
                }
                .pointerInput(lo, hi) {
                    detectTapGestures(onDoubleTap = { set(0f) })
                },
        ) {
            val cy = size.height / 2
            val zx = (0f - lo) / (hi - lo) * size.width
            val vx = (v - lo) / (hi - lo) * size.width
            drawLine(Halo.Faint, Offset(0f, cy), Offset(size.width, cy), 1.5f * density)
            drawLine(Halo.Text, Offset(zx, cy), Offset(vx, cy), 2f * density)
            drawLine(Halo.Dim, Offset(zx, cy - 5 * density), Offset(zx, cy + 5 * density), 1f * density)
            // graduations
            for (i in 0..20) {
                val x = size.width * i / 20f
                drawLine(Color(0x22FFFFFF), Offset(x, cy + 7 * density), Offset(x, cy + (if (i % 5 == 0) 11 else 9) * density), 1f)
            }
            drawCircle(Color.White, 7f * density, Offset(vx, cy))
            drawCircle(Halo.Ir, 2f * density, Offset(vx, cy))
        }
    }
}

@Composable
private fun RatioRow(cur: Float?, onPick: (Float?) -> Unit) {
    val opts = listOf<Pair<String, Float?>>("Libre" to null, "16:9" to 16f / 9f, "3:2" to 1.5f, "1:1" to 1f, "9:16" to 9f / 16f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((n, r) in opts) {
            val on = r == cur
            Box(
                Modifier.weight(1f).height(26.dp).background(if (on) Halo.Text else Color(0x0AFFFFFF), RoundedCornerShape(7.dp))
                    .clickable { onPick(r) },
                contentAlignment = Alignment.Center,
            ) { Text(n, style = sans(11.sp, FontWeight.SemiBold, if (on) Color.Black else Halo.Dim)) }
        }
    }
}

// ---------------------------------------------------------------------------- rendu pleine définition

/** Rendu pleine définition (hors du fil principal) : orientation, redressement, recadrage, couleurs. */
internal fun renderFull(ctx: android.content.Context, s: ImgSource, dims: Pair<Int, Int>, p: EditParams): Bitmap {
    fun load(sample: Int): Bitmap? = s.open(ctx)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    var sample = 1
    val full: Bitmap = try { load(1) } catch (_: OutOfMemoryError) { sample = 2; load(2) } ?: throw java.io.IOException("image illisible")
    val rw = full.width.toFloat()
    val rh = full.height.toFloat()
    val wf = (dims.first / sample).toFloat()
    val hf = (dims.second / sample).toFloat()
    val c = p.crop
    val ow = max(1, ((c[2] - c[0]) * wf).roundToInt())
    val oh = max(1, ((c[3] - c[1]) * hf).roundToInt())
    val out = try { Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { full.recycle(); throw e }
    val m = android.graphics.Matrix()
    when (s.rotation) {
        90 -> { m.postRotate(90f); m.postTranslate(rh, 0f) }
        180 -> { m.postRotate(180f); m.postTranslate(rw, rh) }
        270 -> { m.postRotate(270f); m.postTranslate(0f, rw) }
    }
    val sc = coverScale(wf, hf, p.angle)
    m.postScale(sc, sc, wf / 2, hf / 2)
    m.postRotate(p.angle, wf / 2, hf / 2)
    m.postTranslate(-c[0] * wf, -c[1] * hf)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(p.colorMatrix()) }
    android.graphics.Canvas(out).drawBitmap(full, m, paint)
    full.recycle()
    return out
}

