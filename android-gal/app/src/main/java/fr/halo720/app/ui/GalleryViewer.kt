package fr.halo720.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.halo720.app.HaloApp
import fr.halo720.app.data.Looks
import fr.halo720.app.data.Photo
import fr.halo720.app.data.RawState
import fr.halo720.app.data.Steps
import fr.halo720.app.data.fr1
import fr.halo720.app.image.LookMath
import fr.halo720.app.net.Link
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Image d'une page du visualiseur. */
@Stable
internal class PageImg {
    var src by mutableStateOf<ImgSource?>(null)
    var base by mutableStateOf<Bitmap?>(null)
    var thumb by mutableStateOf<Bitmap?>(null)
    var rawW by mutableIntStateOf(0)
    var rawH by mutableIntStateOf(0)
    var progress by mutableStateOf<Float?>(null)
    var note by mutableStateOf<String?>(null)
    val fullW get() = src?.let { if (it.rotation % 180 != 0) rawH else rawW } ?: 0
    val fullH get() = src?.let { if (it.rotation % 180 != 0) rawW else rawH } ?: 0
}

/** Travail en cours sur une photo (enregistrement d'une version). Survit à la fermeture du visualiseur. */
internal object SaveJobs {
    val stage = mutableStateMapOf<String, Pair<String, Float?>>()
    val errors = mutableStateMapOf<String, String>()
}

@Composable
internal fun GalleryViewer(
    photos: List<Photo>,
    startStem: String,
    onClose: () -> Unit,
    onCurrent: (String) -> Unit,
    onEdit: (String) -> Unit,
    onShare: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HaloApp
    val link by app.link.link.collectAsState()
    val online = link == Link.ONLINE
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    if (photos.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }

    val startIdx = remember { photos.indexOfFirst { it.stem == startStem }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIdx) { photos.size }
    val imgs = remember { HashMap<String, PageImg>() }
    fun img(stem: String) = imgs.getOrPut(stem) { PageImg() }
    val zoomState = remember { mutableStateMapOf<String, Boolean>() }
    var previewLook by remember { mutableStateOf<String?>(null) }
    var previewNote by remember { mutableStateOf<String?>(null) }
    var showMeta by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var infos by remember { mutableStateOf(false) }
    val dismiss = remember { Animatable(0f) }

    val cur = photos[pager.currentPage.coerceIn(0, photos.size - 1)]
    val curImg = img(cur.stem)
    val zoomed = zoomState[cur.stem] == true
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { i ->
            photos.getOrNull(i)?.let { onCurrent(it.stem) }
            previewLook = null
        }
    }

    // aperçu instantané du look choisi (page courante)
    val preview by produceState<Bitmap?>(null, cur.stem, previewLook, curImg.base, curImg.thumb) {
        value = null
        previewNote = null
        val look = previewLook ?: return@produceState
        val base = curImg.base ?: curImg.thumb ?: return@produceState
        val srcLook = curImg.src?.look ?: cur.info.look
        val conv = LookMath.conversionFor(cur.info, srcLook, look)
        if (conv != null) {
            value = withContext(Dispatchers.Default) { LookMath.apply(base, conv) }
        } else {
            previewNote = "Aperçu depuis le RAW…"
            val b = GalleryImages.rawPreview(ctx, cur, look, max(base.width, base.height).coerceAtMost(2048), true) { f ->
                previewNote = "Copie du RAW ${(f * 100).roundToInt()} %"
            }
            previewNote = if (b == null) "Ce look demande le RAW" else null
            value = b
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Halo.Page)) {
        val landscape = maxWidth > maxHeight
        val screenLong = with(density) { max(maxWidth.toPx(), maxHeight.toPx()).roundToInt() }
        val dismissPx = with(density) { 110.dp.toPx() }

        val pagerBox: @Composable (Modifier) -> Unit = { m ->
            Box(m.background(Color.Black.copy(alpha = (1f - dismiss.value / (dismissPx * 3f)).coerceIn(0.3f, 1f)))) {
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = !zoomed,
                    beyondViewportPageCount = 1,
                    key = { photos.getOrNull(it)?.stem ?: it.toString() },
                    modifier = Modifier.fillMaxSize().graphicsLayer { translationY = dismiss.value },
                ) { i ->
                    val p = photos[i]
                    val im = img(p.stem)
                    PageLoader(p, im, online, screenLong)
                    val showPreview = p.stem == cur.stem && previewLook != null
                    val region = remember(im.src?.key, im.rawW, im.rawH) { im.src?.let { RegionSource(ctx, it, im.rawW, im.rawH) } }
                    DisposableEffect(region) { onDispose { region?.close() } }
                    ZoomImage(
                        bitmap = if (showPreview) preview ?: im.base ?: im.thumb else im.base ?: im.thumb,
                        fullW = im.fullW, fullH = im.fullH,
                        detail = if (!showPreview) region else null,
                        onZoom = { z -> zoomState[p.stem] = z },
                        onTap = { showMeta = !showMeta },
                        onDismissDrag = { dy -> scope.launch { dismiss.snapTo((dismiss.value + dy).coerceAtLeast(0f)) } },
                        onDismissEnd = {
                            scope.launch {
                                if (dismiss.value > dismissPx) onClose() else dismiss.animateTo(0f, tween(180))
                            }
                        },
                    )
                    // état de chargement / indisponible
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        im.progress?.let { f ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xAA000000)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text("Copie depuis la caméra", style = sans(10.5.sp, color = Halo.Text))
                                ThinBar(f, Halo.Text, Modifier.padding(top = 6.dp).width(120.dp))
                            }
                        }
                        im.note?.let { n ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xAA000000)).padding(horizontal = 10.dp, vertical = 7.dp)) {
                                HIcon(Ic.Camera, 13.dp, Halo.Dim)
                                Text(n, style = sans(11.sp, color = Halo.Text), modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
                if (showMeta && !zoomed) {
                    MetaTags(cur, curImg, previewLook, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp, end = 12.dp))
                }
                previewNote?.let {
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xAA000000))
                        .padding(horizontal = 10.dp, vertical = 5.dp)) { Text(it, style = sans(10.5.sp, color = Halo.Text)) }
                }
            }
        }

        val header: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().padding(start = 7.dp, end = 12.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                BackRound(onClose)
                Column(Modifier.weight(1f).padding(start = 2.dp)) {
                    Text(whenLabel(cur.time), style = sans(14.sp, FontWeight.SemiBold), maxLines = 1)
                    Text(cur.stem, style = mono(8.5.sp, color = Halo.Dim), maxLines = 1)
                }
                Text("${pager.currentPage + 1} / ${photos.size}", style = mono(9.sp, color = Halo.Dim))
            }
        }

        val panel: @Composable () -> Unit = {
            Panel(
                p = cur, im = curImg, previewLook = previewLook, online = online,
                onLook = { k ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val shown = curImg.src?.look ?: cur.info.look
                    val idx = VersionIndex.get(ctx)
                    when {
                        k == shown && (previewLook == null || previewLook == k) -> previewLook = null
                        k == shown -> previewLook = null
                        k == cur.info.look && cur.lookShown != null -> {
                            previewLook = null
                            app.store.update(cur.stem) { it?.copy(lookShown = null) }
                        }
                        cur.versions.any { idx.look(it) == k } -> {
                            previewLook = null
                            app.store.update(cur.stem) { it?.copy(lookShown = k) }
                        }
                        else -> previewLook = k
                    }
                },
                onSave = {
                    val look = previewLook ?: return@Panel
                    startSave(ctx, cur, look) { previewLook = null }
                },
                onShare = {
                    val look = previewLook
                    if (look != null) startSave(ctx, cur, look) { previewLook = null; onShare(cur.stem) } else onShare(cur.stem)
                },
                onEdit = { onEdit(cur.stem) },
                onMore = { menu = true },
            )
        }

        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                pagerBox(Modifier.weight(1.4f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
                    header()
                    panel()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                header()
                pagerBox(Modifier.weight(1f).fillMaxWidth())
                panel()
            }
        }
    }

    if (menu) {
        HaloDialog(onDismiss = { menu = false }, title = cur.stem) {
            DialogRow("Infos", { menu = false; infos = true })
            DialogRow("Supprimer", { menu = false; onDelete(cur.stem) }, danger = true)
            DialogRow("Annuler", { menu = false }, bold = true)
        }
    }
    if (infos) InfoDialog(cur, curImg) { infos = false }
}

private fun startSave(ctx: android.content.Context, p: Photo, look: String, done: () -> Unit) {
    if (SaveJobs.stage.containsKey(p.stem)) return
    SaveJobs.errors.remove(p.stem)
    SaveJobs.stage[p.stem] = "Préparation" to null
    GalleryImages.bg.launch {
        try {
            var last: Pair<String, Float?>? = null
            GalleryImages.saveLook(ctx.applicationContext, p, look) { t, f ->
                synchronized(SaveJobs) {
                    val l = last
                    if (l == null || l.first != t || f == null || l.second == null || abs(f - l.second!!) >= 0.01f) {
                        last = t to f
                        SaveJobs.stage[p.stem] = t to f
                    }
                }
            }
            withContext(Dispatchers.Main) { done() }
        } catch (e: Throwable) {
            SaveJobs.errors[p.stem] = if (e is OutOfMemoryError) "Mémoire insuffisante" else (e.message ?: "erreur")
        } finally {
            SaveJobs.stage.remove(p.stem)
        }
    }
}

private fun whenLabel(t: Double): String {
    val d = Date((t * 1000).toLong())
    val day = SimpleDateFormat("yyyyMMdd", Locale.FRANCE)
    val now = Date()
    val y = Date(now.time - 86_400_000L)
    val hm = SimpleDateFormat("HH:mm", Locale.FRANCE).format(d)
    return when (day.format(d)) {
        day.format(now) -> "Aujourd'hui · $hm"
        day.format(y) -> "Hier · $hm"
        else -> SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(d) + " · " + hm
    }
}

// ---------------------------------------------------------------------------- chargement d'une page

@Composable
private fun PageLoader(p: Photo, im: PageImg, online: Boolean, screenLong: Int) {
    val ctx = LocalContext.current
    LaunchedEffect(p.stem, p.jpgUri, p.lookShown, p.versions.size, online) {
        if (im.thumb == null) im.thumb = withContext(Dispatchers.IO) { GalleryImages.tile(ctx, p) }
        im.note = null
        val src = try {
            GalleryImages.resolve(ctx, p, allowNetwork = true) { f -> im.progress = f }
        } catch (_: Exception) { null }
        im.progress = null
        if (src == null) {
            im.note = when {
                p.cameraOnly && !online -> "Sur la caméra · hors ligne"
                p.cameraOnly -> "Copie impossible"
                else -> "Image indisponible"
            }
            return@LaunchedEffect
        }
        if (im.src?.key == src.key && im.base != null) return@LaunchedEffect
        val key = "${src.key}|$screenLong"
        val base = GalleryImages.views.get(key) ?: withContext(Dispatchers.IO) {
            try { GalleryImages.decode(ctx, src, screenLong) } catch (_: Throwable) { null }
        }?.also { GalleryImages.views.put(key, it) }
        val b = withContext(Dispatchers.IO) { GalleryImages.bounds(ctx, src) }
        im.rawW = b?.first ?: 0
        im.rawH = b?.second ?: 0
        im.src = src
        if (base != null) im.base = base else im.note = "Image illisible"
    }
}

// ---------------------------------------------------------------------------- zoom

/** Décodage de zones à pleine définition (BitmapRegionDecoder) pour le zoom jusqu'à 100 %. */
internal class RegionSource(private val ctx: android.content.Context, val src: ImgSource, private val rw: Int, private val rh: Int) {
    private var dec: BitmapRegionDecoder? = null

    /** r : zone en coordonnées de l'image orientée. Renvoie un bitmap orienté. Bloquant. */
    fun decode(r: android.graphics.Rect, sample: Int): Bitmap? {
        if (rw <= 0 || rh <= 0) return null
        val d = synchronized(this) { dec ?: GalleryImages.regionDecoder(ctx, src).also { dec = it } } ?: return null
        val raw = when (src.rotation) {
            90 -> android.graphics.Rect(r.top, rh - r.right, r.bottom, rh - r.left)
            180 -> android.graphics.Rect(rw - r.right, rh - r.bottom, rw - r.left, rh - r.top)
            270 -> android.graphics.Rect(rw - r.bottom, r.left, rw - r.top, r.right)
            else -> android.graphics.Rect(r)
        }
        if (!raw.intersect(0, 0, rw, rh) || raw.width() < 2 || raw.height() < 2) return null
        val b = try {
            synchronized(d) { d.decodeRegion(raw, BitmapFactory.Options().apply { inSampleSize = sample }) }
        } catch (_: Throwable) { null } ?: return null
        return fr.halo720.app.data.PhotoStore.rotateFor(b, src.rotation)
    }

    fun close() = synchronized(this) { dec?.recycle(); dec = null }
}

private class Detail(val bmp: ImageBitmap, val rect: Rect)

@Composable
private fun ZoomImage(
    bitmap: Bitmap?,
    fullW: Int,
    fullH: Int,
    detail: RegionSource?,
    onZoom: (Boolean) -> Unit,
    onTap: () -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bw = constraints.maxWidth.toFloat()
        val bh = constraints.maxHeight.toFloat()
        val iw = (if (fullW > 0) fullW else bitmap?.width ?: 1).toFloat()
        val ih = (if (fullH > 0) fullH else bitmap?.height ?: 1).toFloat()
        val f = min(bw / iw, bh / ih)
        val fw = iw * f
        val fh = ih * f
        val left = (bw - fw) / 2
        val top = (bh - fh) / 2
        val maxScale = if (fullW > 0) max(1f / f, 1f) else 2.5f
        var scale by remember { mutableFloatStateOf(1f) }
        var tx by remember { mutableFloatStateOf(0f) }
        var ty by remember { mutableFloatStateOf(0f) }
        var det by remember { mutableStateOf<Detail?>(null) }
        val scope = rememberCoroutineScope()
        val img = remember(bitmap) { bitmap?.asImageBitmap() }

        LaunchedEffect(bitmap) { det = null }
        LaunchedEffect(scale > 1.01f) { onZoom(scale > 1.01f) }

        fun clampT(s: Float, x: Float, y: Float): Offset {
            val mx = max(0f, (fw * s - bw) / 2)
            val my = max(0f, (fh * s - bh) / 2)
            return Offset(x.coerceIn(-mx, mx), y.coerceIn(-my, my))
        }

        fun animateTo(s1: Float, t1: Offset) {
            val s0 = scale; val x0 = tx; val y0 = ty
            scope.launch {
                animate(0f, 1f, animationSpec = tween(220)) { v, _ ->
                    scale = s0 + (s1 - s0) * v
                    tx = x0 + (t1.x - x0) * v
                    ty = y0 + (t1.y - y0) * v
                }
            }
        }

        // zone à pleine définition quand l'image de base ne suffit plus
        LaunchedEffect(detail, bitmap, fullW, fullH, bw, bh) {
            snapshotFlow { Triple(scale, tx, ty) }.collectLatest { (s, x, y) ->
                delay(160)
                val b = bitmap ?: return@collectLatest
                if (detail == null || fullW <= 0 || fw * s <= b.width * 1.15f) { det = null; return@collectLatest }
                val l = (-bw / 2 - x) / s + bw / 2
                val r = (bw / 2 - x) / s + bw / 2
                val t = (-bh / 2 - y) / s + bh / 2
                val bt = (bh / 2 - y) / s + bh / 2
                val x0 = ((l - left) / fw * fullW).coerceIn(0f, fullW.toFloat())
                val x1 = ((r - left) / fw * fullW).coerceIn(0f, fullW.toFloat())
                val y0 = ((t - top) / fh * fullH).coerceIn(0f, fullH.toFloat())
                val y1 = ((bt - top) / fh * fullH).coerceIn(0f, fullH.toFloat())
                if (x1 - x0 < 4 || y1 - y0 < 4) return@collectLatest
                var sample = 1
                val ratio = fullW / (fw * s)   // pixels image par pixel écran
                while (sample * 2 <= ratio) sample *= 2
                val rr = android.graphics.Rect(x0.toInt(), y0.toInt(), x1.roundToInt(), y1.roundToInt())
                val bmp = withContext(Dispatchers.IO) { detail.decode(rr, sample) } ?: return@collectLatest
                det = Detail(
                    bmp.asImageBitmap(),
                    Rect(left + rr.left / iw * fw, top + rr.top / ih * fh, left + rr.right / iw * fw, top + rr.bottom / ih * fh),
                )
            }
        }

        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(bw, bh, fw, fh, maxScale) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { pt ->
                            if (scale > 1.05f) animateTo(1f, Offset.Zero)
                            else {
                                val s1 = if (maxScale < 1.5f) 2f else maxScale
                                val t1 = Offset((pt.x - bw / 2) * (1 - s1), (pt.y - bh / 2) * (1 - s1))
                                animateTo(s1, clampT(s1, t1.x, t1.y))
                            }
                        },
                    )
                }
                .pointerInput(bw, bh, fw, fh, maxScale) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var mode = 0 // 0 indécis, 1 zoom/déplacement, 2 fermeture, 3 laissé au pager
                        var acc = Offset.Zero
                        val slop = viewConfiguration.touchSlop
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.count { it.pressed }
                            if (pressed == 0) break
                            if (pressed >= 2 && mode != 2) mode = 1
                            when (mode) {
                                0 -> {
                                    acc += ev.changes[0].positionChange()
                                    if (acc.getDistance() > slop) {
                                        mode = when {
                                            scale > 1.01f -> 1
                                            acc.y > 0 && abs(acc.y) > abs(acc.x) * 1.4f -> 2
                                            else -> 3
                                        }
                                    }
                                }
                                1 -> {
                                    val z = ev.calculateZoom()
                                    val pan = ev.calculatePan()
                                    val c = ev.calculateCentroid(useCurrent = true)
                                    val s0 = scale
                                    val s1 = (s0 * z).coerceIn(0.8f, maxScale * 1.25f)
                                    val qx = (c.x - bw / 2 - tx) / s0
                                    val qy = (c.y - bh / 2 - ty) / s0
                                    var nx = c.x - bw / 2 - qx * s1 + pan.x
                                    var ny = c.y - bh / 2 - qy * s1 + pan.y
                                    if (pressed < 2) { val cl = clampT(s1, nx, ny); nx = cl.x; ny = cl.y }
                                    scale = s1; tx = nx; ty = ny
                                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                                2 -> {
                                    val ch = ev.changes[0]
                                    onDismissDrag(ch.positionChange().y)
                                    ch.consume()
                                }
                                else -> {}
                            }
                        }
                        when (mode) {
                            1 -> {
                                val s1 = scale.coerceIn(1f, maxScale)
                                val t1 = if (s1 <= 1f) Offset.Zero else clampT(s1, tx, ty)
                                if (s1 != scale || t1.x != tx || t1.y != ty) animateTo(s1, t1)
                            }
                            2 -> onDismissEnd()
                        }
                    }
                }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = tx; translationY = ty },
        ) {
            val i = img ?: return@Canvas
            drawImage(
                i, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(fw.roundToInt(), fh.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            det?.let { d ->
                drawImage(
                    d.bmp,
                    dstOffset = IntOffset(d.rect.left.roundToInt(), d.rect.top.roundToInt()),
                    dstSize = IntSize(d.rect.width.roundToInt().coerceAtLeast(1), d.rect.height.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- étiquettes

@Composable
private fun MetaTags(p: Photo, im: PageImg, previewLook: String?, modifier: Modifier) {
    val ctx = LocalContext.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tag("ISO ${Steps.isoFromMeta(p.info.iso)} · ${Steps.shutterFromMeta(p.info.exposureUs)}")
        when {
            p.rawState == RawState.COPIED -> Tag("RAW ✓", Halo.Ok)
            p.cameraOnly -> Tag("Sur la caméra seulement", Halo.Dim)
            p.rawState == RawState.WAITING -> Tag("RAW en attente", Halo.Warn)
        }
        val look = previewLook ?: im.src?.look ?: p.info.look
        val edit = im.src?.uri?.let { VersionIndex.get(ctx).isEdit(it) } == true
        Tag((Looks.badge[look] ?: look.uppercase()) + when {
            previewLook != null -> " · APERÇU"
            edit -> " · RETOUCHE"
            else -> ""
        }, if (previewLook != null) Halo.Ir else Halo.Text)
    }
}

// ---------------------------------------------------------------------------- panneau : looks + actions

@Composable
private fun Panel(
    p: Photo, im: PageImg, previewLook: String?, online: Boolean,
    onLook: (String) -> Unit, onSave: () -> Unit, onShare: () -> Unit, onEdit: () -> Unit, onMore: () -> Unit,
) {
    val ctx = LocalContext.current
    val shown = im.src?.look ?: p.info.look
    val selectedLook = previewLook ?: shown
    val source = im.src ?: ImgSource("thumb:${p.stem}", null, null, p.info.look, true, 0)
    val baseBmp = im.base ?: im.thumb
    val small = remember(baseBmp) { baseBmp?.let { LookMath.small(it, 150) } }
    val job = SaveJobs.stage[p.stem]
    val err = SaveJobs.errors[p.stem]
    val idx = VersionIndex.get(ctx)

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (k in Looks.order) {
                val thumb by produceState<Bitmap?>(null, small, k, source.key) {
                    val s = small ?: return@produceState
                    value = withContext(Dispatchers.Default) { GalleryImages.lookThumb(p, source, s, k) }
                        ?: GalleryImages.rawPreview(ctx, p, k, 150, allowDownload = false)
                }
                val saved = k == p.info.look || p.versions.any { idx.look(it) == k }
                LookTile(k, thumb, k == selectedLook, saved) { onLook(k) }
            }
        }
        // ligne d'enregistrement de la version
        when {
            job != null -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), color = Halo.Text, strokeWidth = 1.5.dp)
                Text(job.first + (job.second?.let { " ${(it * 100).roundToInt()} %" } ?: ""), style = sans(11.sp, color = Halo.Text),
                    modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1)
                ThinBar(job.second ?: 0f, Halo.Text, Modifier.width(80.dp))
            }
            previewLook != null -> {
                val fromRaw = GalleryImages.rawReachable(ctx, p)
                val possible = fromRaw || LookMath.canConvertFrom(p.info, p.info.look)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(GalleryImages.lookName(previewLook), style = sans(12.sp, FontWeight.SemiBold))
                        Text(
                            when {
                                fromRaw -> "Nouvelle version depuis le RAW"
                                possible -> "Nouvelle version depuis le JPEG"
                                p.rawState == RawState.WAITING && !online -> "RAW sur la caméra · hors ligne"
                                else -> "Ce look demande le RAW"
                            },
                            style = sans(10.sp, color = Halo.Dim),
                        )
                    }
                    ActionButton("Enregistrer cette version", onSave, primary = true, enabled = possible)
                }
            }
            err != null -> Text("Échec de la version · $err", style = sans(10.5.sp, color = Halo.Ir),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val canOpen = !(p.cameraOnly && !online) || im.src != null
            ActionButton("Partager", onShare, Modifier.weight(1f), primary = true, ic = Ic.Share, enabled = canOpen && job == null)
            ActionButton("Retoucher", onEdit, Modifier.weight(1f), ic = Ic.Sliders, enabled = im.src != null && job == null)
            ActionButton(null, onMore, Modifier.width(46.dp), ic = Ic.More)
        }
    }
}

@Composable
private fun LookTile(k: String, bmp: Bitmap?, on: Boolean, saved: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(52.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(9.dp)).background(Halo.Surface)
                .border(1.5.dp, if (on) Halo.Ir else Color.Transparent, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(if (on) 1.5.dp else 0.dp).clip(RoundedCornerShape(7.5.dp)))
            } else {
                Text("RAW", style = mono(8.5.sp, FontWeight.Bold, Halo.Faint))
            }
            if (saved && !on) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(4.dp).clip(RoundedCornerShape(2.dp)).background(Halo.Text.copy(alpha = 0.8f)))
        }
        Text(Looks.names[k] ?: k, style = sans(9.5.sp, FontWeight.SemiBold, if (on) Halo.Text else Halo.Dim),
            modifier = Modifier.padding(top = 4.dp), maxLines = 1)
    }
}

// ---------------------------------------------------------------------------- infos

@Composable
private fun InfoDialog(p: Photo, im: PageImg, onDismiss: () -> Unit) {
    val i = p.info
    HaloDialog(onDismiss = onDismiss, title = "Infos", subtitle = p.stem) {
        Column(Modifier.padding(bottom = 4.dp)) {
            InfoRow("Date", SimpleDateFormat("d MMM yyyy · HH:mm:ss", Locale.FRANCE).format(Date((i.time * 1000).toLong())))
            InfoRow("ISO · pose", "${Steps.isoFromMeta(i.iso)} · ${Steps.shutterFromMeta(i.exposureUs)}")
            InfoRow("Look d'origine", Looks.names[i.look] ?: i.look)
            InfoRow("Zoom", fr1(i.zoom) + "×")
            InfoRow("Définition", if (im.fullW > 0) "${im.fullW} × ${im.fullH}" else "${i.w} × ${i.h}")
            InfoRow("Mode", i.mode + (i.series?.let { " · série" } ?: ""))
            InfoRow("JPEG", i.jpgSize?.let { mb(it) } ?: if (i.jpgPending) "en cours" else "—")
            InfoRow("RAW", i.dngSize?.let { mb(it) } ?: if (i.dngPending) "en cours" else "—")
            InfoRow("Sur le téléphone", listOfNotNull(if (p.jpgUri != null) "JPEG" else null, if (p.dngUri != null) "RAW" else null).joinToString(" + ").ifEmpty { "non" })
            InfoRow("Sur la caméra", if (p.onCamera) "oui" else "non")
            InfoRow("Versions", p.versions.size.toString())
        }
        DialogRow("Fermer", onDismiss, bold = true)
    }
}

private fun mb(b: Long) = fr1(b / 1_048_576.0) + " Mo"

