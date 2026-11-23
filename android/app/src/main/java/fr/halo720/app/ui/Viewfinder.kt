package fr.halo720.app.ui

import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaActionSound
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.halo720.app.HaloApp
import fr.halo720.app.data.CamSettings
import fr.halo720.app.data.CamStatus
import fr.halo720.app.data.Looks
import fr.halo720.app.data.Steps
import fr.halo720.app.data.fr1
import fr.halo720.app.net.Link
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Verrou d'orientation : AUTO, ou forcé. */
enum class OrientLock { AUTO, LANDSCAPE, PORTRAIT }

/**
 * Géométrie de l'image dans la zone de visée.
 * L'image du capteur (16:9, paysage) est tournée de [rot]° (sens horaire) pour l'affichage,
 * puis recadrée (plein écran) ou ajustée (image entière).
 */
class Geom(val boxW: Float, val boxH: Float, val imgW: Int, val imgH: Int, val rot: Int, val crop: Boolean) {
    private val turned = rot % 180 != 0
    val vw = (if (turned) imgH else imgW).toFloat()
    val vh = (if (turned) imgW else imgH).toFloat()
    val scale = if (crop) max(boxW / vw, boxH / vh) else min(boxW / vw, boxH / vh)
    val dispW = vw * scale
    val dispH = vh * scale
    val left = (boxW - dispW) / 2f
    val top = (boxH - dispH) / 2f

    fun matrix(): Matrix = Matrix().apply {
        postTranslate(-imgW / 2f, -imgH / 2f)
        postRotate(rot.toFloat())
        postScale(scale, scale)
        postTranslate(boxW / 2f, boxH / 2f)
    }

    /** Point écran -> coordonnées capteur normalisées (0..1). */
    fun toSensor(x: Float, y: Float): Pair<Double, Double> {
        val u = ((x - left) / dispW).coerceIn(0f, 1f).toDouble()
        val v = ((y - top) / dispH).coerceIn(0f, 1f).toDouble()
        return when (rot) {
            90 -> v to 1 - u
            180 -> 1 - u to 1 - v
            270 -> 1 - v to u
            else -> u to v
        }
    }

    /** Coordonnées capteur -> point écran. */
    fun toScreen(xs: Double, ys: Double): Offset {
        val (u, v) = when (rot) {
            90 -> 1 - ys to xs
            180 -> 1 - xs to 1 - ys
            270 -> ys to 1 - xs
            else -> xs to ys
        }
        return Offset(left + (u * dispW).toFloat(), top + (v * dispH).toFloat())
    }

    /** Zone réellement enregistrée (visible) à l'écran. */
    fun visibleRect(): androidx.compose.ui.geometry.Rect =
        androidx.compose.ui.geometry.Rect(max(0f, left), max(0f, top), min(boxW, left + dispW), min(boxH, top + dispH))
}

private val MODES = listOf("pose" to "Pose longue", "rafale" to "Rafale", "photo" to "Photo", "interv" to "Intervalles", "bracket" to "Bracketing")

private class Hint(val text: String, val id: Long)

@Composable
fun ViewfinderScreen(
    imageRot: Int,
    landscape: Boolean,
    lock: OrientLock,
    onLockTap: () -> Unit,
    onLockLong: () -> Unit,
    onOpenGallery: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    shutterBus: ShutterBus,
    pagesOpen: Boolean,
) {
    val app = LocalContext.current.applicationContext as HaloApp
    val link = app.link
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val s by link.settings.collectAsState()
    val st by link.status.collectAsState()
    val linkState by link.link.collectAsState()
    val aids by app.prefs.aids.collectAsState()
    val frameState = app.stream.frame.collectAsState()
    val fps by app.stream.fps.collectAsState()
    val stalled by app.stream.stalled.collectAsState()
    val photos by app.store.photos.collectAsState()
    val sync by app.sync.state.collectAsState()
    val activeProfile by app.profiles.active.collectAsState()
    val profileMod by app.profiles.modified.collectAsState()

    // visée : flux uniquement quand l'écran est visible (batterie de la caméra)
    DisposableEffect(pagesOpen) {
        if (!pagesOpen) app.stream.start(scope)
        onDispose { app.stream.stop() }
    }
    val analyzer = remember { FrameAnalyzer(scope) }
    val analysis by analyzer.result.collectAsState()

    var quickOpen by remember { mutableStateOf(false) }
    var dialKey by remember { mutableStateOf<String?>(null) }
    var dialTouch by remember { mutableLongStateOf(0L) }
    var zooming by remember { mutableStateOf(false) }
    var zoomLive by remember { mutableDoubleStateOf(s.zoom) }
    var hint by remember { mutableStateOf<Hint?>(null) }
    val flash = remember { Animatable(0f) }
    var focusUi by remember { mutableStateOf<Offset?>(null) }       // capteur (x,y)
    var focusLockUi by remember { mutableStateOf(false) }
    var focusTapAt by remember { mutableLongStateOf(0L) }
    var thumbPop by remember { mutableLongStateOf(0L) }
    var reviewStem by remember { mutableStateOf<String?>(null) }
    var clockSkew by remember { mutableDoubleStateOf(0.0) }
    var shutterDown by remember { mutableStateOf(false) }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) { onDispose { sound.release() } }

    fun buzz(c: Int = HapticFeedbackConstants.CLOCK_TICK) { if (aids.vibrations) view.performHapticFeedback(c) }
    fun showHint(t: String) { hint = Hint(t, System.nanoTime()) }
    fun changed() { app.profiles.markModified() }

    LaunchedEffect(st.now) { if (st.now > 0) clockSkew = st.now - System.currentTimeMillis() / 1000.0 }
    LaunchedEffect(hint) { val h = hint ?: return@LaunchedEffect; delay(1100); if (hint === h) hint = null }
    LaunchedEffect(dialKey, dialTouch) {
        if (dialKey != null) { delay(2600); dialKey = null; link.loupe(false) }
    }
    LaunchedEffect(s.zoom) { if (!zooming) zoomLive = s.zoom }
    LaunchedEffect(activeProfile) {
        val p = app.profiles.get(activeProfile)
        if (p != null && !profileMod) showHint("Profil ${p.name}")
    }

    // retour immédiat au déclenchement : flash 80 ms, vibration 20 ms, son optionnel — local, sans attendre
    fun feedback() {
        scope.launch { flash.snapTo(0.75f); flash.animateTo(0f, tween(160)) }
        if (aids.vibrations) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (aids.sound) sound.play(MediaActionSound.SHUTTER_CLICK)
        thumbPop = System.currentTimeMillis()
    }

    fun press() {
        if (st.sdFull && !st.busy) { showHint("microSD pleine"); return }
        if (linkState != Link.ONLINE) { showHint("Caméra introuvable"); return }
        val stopping = st.busy
        if (!stopping) feedback() else buzz()
        link.shoot(imageRot, "press")
        if (stopping) showHint("Série arrêtée")
    }
    fun release() { link.shoot(imageRot, "release") }

    // déclencheurs extérieurs (touches volume)
    LaunchedEffect(shutterBus) {
        shutterBus.events.collect { down -> if (down) press() else release() }
    }

    // événements caméra : nouvelle photo, erreur
    LaunchedEffect(Unit) {
        link.events.collect { e ->
            when (e.kind) {
                "thumb" -> if (aids.review) reviewStem = e.data.optString("stem")
                "error" -> showHint(e.data.optString("message", "Erreur"))
                "shot" -> if (System.currentTimeMillis() - thumbPop > 400) thumbPop = System.currentTimeMillis()
            }
        }
    }
    LaunchedEffect(reviewStem) { if (reviewStem != null) { delay(2000); reviewStem = null } }

    // analyse des images (histogramme, zones brûlées, netteté)
    val wantPeak = aids.peaking && (s.af == "manual" || dialKey == "lens")
    LaunchedEffect(aids.histogram, aids.clipping, wantPeak) {
        app.stream.frame.filter { it != null }.collect { f ->
            if (aids.histogram || aids.clipping || wantPeak) analyzer.submit(f!!.bitmap, aids.clipping, wantPeak)
        }
    }

    val latest = remember(photos) { app.store.sorted().firstOrNull() }
    val online = linkState == Link.ONLINE
    val hasFrame by remember { derivedStateOf { frameState.value != null } }
    val frameW by remember { derivedStateOf { frameState.value?.bitmap?.width ?: 768 } }
    val frameH by remember { derivedStateOf { frameState.value?.bitmap?.height ?: 432 } }

    // ---------------------------------------------------------------- mise en page
    BoxWithConstraints(Modifier.fillMaxSize().background(Halo.Bg)) {
        val W = constraints.maxWidth.toFloat()
        val H = constraints.maxHeight.toFloat()
        val bandPx = with(density) { 46.dp.toPx() }
        val insets = WindowInsets.safeDrawing
        val topInset = with(density) { insets.getTop(this).toFloat() }
        val botInset = with(density) { insets.getBottom(this).toFloat() }
        val leftInset = with(density) { insets.getLeft(this, androidx.compose.ui.unit.LayoutDirection.Ltr).toFloat() }
        val rightInset = with(density) { insets.getRight(this, androidx.compose.ui.unit.LayoutDirection.Ltr).toFloat() }
        val mirror = landscape && aids.leftHanded

        // zone image
        val stageLeft = if (landscape) (if (mirror) 0f else leftInset + bandPx) else 0f
        val stageTop = if (landscape) 0f else topInset + bandPx
        val stageRight = if (landscape) (if (mirror) W - rightInset - bandPx else W) else W
        val stageBottom = if (landscape) H else H - botInset - with(density) { 18.dp.toPx() }
        val stageW = stageRight - stageLeft
        val stageH = stageBottom - stageTop
        val geom = remember(stageW, stageH, imageRot, aids.framing, frameW, frameH) {
            Geom(stageW, stageH, frameW, frameH, imageRot, aids.framing == "crop")
        }
        val dp = { px: Float -> with(density) { px.toDp() } }

        // ---------------- image
        Box(Modifier.offset(dp(stageLeft), dp(stageTop)).size(dp(stageW), dp(stageH)).clip(RoundedCornerShape(0.dp))
            .pointerInput(geom, dialKey, quickOpen, aids.touchAf, s.af) {
                detectTapGestures(
                    onTap = { p ->
                        when {
                            quickOpen -> quickOpen = false
                            dialKey != null -> { dialKey = null; link.loupe(false) }
                            aids.touchAf && s.af != "manual" -> {
                                val (x, y) = geom.toSensor(p.x, p.y)
                                focusUi = Offset(x.toFloat(), y.toFloat()); focusLockUi = false; focusTapAt = System.currentTimeMillis()
                                link.focus(x, y, false); buzz()
                            }
                        }
                    },
                    onDoubleTap = { if (aids.touchAf) { focusUi = null; focusLockUi = false; link.focusReset(); showHint("Mise au point centrée") } },
                    onLongPress = { p ->
                        if (s.af == "manual") return@detectTapGestures
                        val (x, y) = geom.toSensor(p.x, p.y)
                        focusUi = Offset(x.toFloat(), y.toFloat()); focusLockUi = true; focusTapAt = System.currentTimeMillis()
                        link.focus(x, y, true)
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    },
                )
            }) {
            val lost = !online || stalled
            Canvas(Modifier.fillMaxSize()) {
                val f = frameState.value ?: return@Canvas
                drawIntoCanvas { c ->
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    if (lost) paint.colorFilter = android.graphics.PorterDuffColorFilter(0x99000000.toInt(), android.graphics.PorterDuff.Mode.SRC_ATOP)
                    synchronized(f.bitmap) { if (!f.bitmap.isRecycled) c.nativeCanvas.drawBitmap(f.bitmap, geom.matrix(), paint) }
                }
            }
            // aides
            val vis = geom.visibleRect()
            Overlays(geom, vis, aids, analysis, wantPeak, s, st, imageRot)
            // cadre de la zone enregistrée (coins)
            Corners(vis)
            // mise au point
            FocusSquare(geom, focusUi, st, focusLockUi, focusTapAt)
            // flash
            Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.White))
        }

        // ---------------- bandeau du haut / colonne de gauche
        TopBand(
            landscape = landscape, mirror = mirror, s = s, quickOpen = quickOpen,
            modifier = if (landscape) Modifier.align(if (mirror) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight()
                .padding(start = if (mirror) 0.dp else dp(leftInset), end = if (mirror) dp(rightInset) else 0.dp).width(46.dp).background(Halo.Bg)
            else Modifier.fillMaxWidth().background(Halo.Bg).padding(top = dp(topInset)).height(46.dp),
            onLed = { link.set("led" to !s.led); buzz(); showHint(if (!s.led) "LED de la caméra : oui" else "LED de la caméra : non") },
            onTimer = {
                val n = when (s.timerS) { 0 -> 2; 2 -> 10; else -> 0 }
                link.set("timer_s" to n); buzz(); changed(); showHint("Retardateur · " + if (n == 0) "non" else "$n s")
            },
            onFormat = {
                val n = if (s.format == "jpg") "jpg+raw" else "jpg"
                link.set("format" to n); buzz(); changed(); showHint(if (n == "jpg") "JPEG seul" else "JPEG + RAW")
            },
            onChevron = { quickOpen = !quickOpen; dialKey = null; buzz() },
            onSettings = { quickOpen = false; onOpenSettings() },
            onLook = {
                val n = Looks.next(s.look); link.set("look" to n); buzz(); changed(); showHint("Look · ${Looks.names[n]}")
            },
        )

        // ---------------- état (coin haut gauche) + histogramme
        val chipTop = dp(stageTop) + 8.dp
        Row(Modifier.offset(dp(stageLeft) + 10.dp, chipTop), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusChips(st, sync, online)
        }
        if (aids.histogram && analysis != null && hasFrame) {
            Histogram(analysis!!.histo, Modifier.offset(dp(stageLeft + stageW) - (if (landscape) 200.dp else 10.dp) - 54.dp, chipTop).size(54.dp, 22.dp))
        }

        // ---------------- dégradé du bas
        if (landscape) {
            Box(Modifier.align(if (mirror) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().width(300.dp)
                .background(Brush.horizontalGradient(if (mirror) listOf(Color(0xCC000000), Color(0x99000000), Color.Transparent) else listOf(Color.Transparent, Color(0x99000000), Color(0xCC000000)))))
        } else {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(250.dp + dp(botInset))
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x77000000), Color(0xBB000000)))))
        }

        // ---------------- commandes
        val sideW = if (landscape) 200.dp else 0.dp
        val ctlBottom = dp(botInset)
        // rangée réglages + zoom
        val zrowMod = if (landscape) Modifier.align(if (mirror) Alignment.BottomEnd else Alignment.BottomStart)
            .padding(start = if (mirror) sideW else dp(stageLeft) + 14.dp, end = if (mirror) dp(W - stageRight) + 14.dp else sideW, bottom = 12.dp + ctlBottom)
        else Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 150.dp + ctlBottom)
        var midBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
        Row(zrowMod.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            SideButton("ISO", isoText(s, st), auto = s.iso == 0, on = dialKey == "iso", faded = zooming, rot = 0f) {
                dialKey = if (dialKey == "iso") null else "iso"; quickOpen = false; dialTouch = System.currentTimeMillis(); buzz()
            }
            Box(Modifier.weight(1f).padding(horizontal = 8.dp).fillMaxHeight().onGloballyPositioned {
                val p = it.positionInRoot(); midBounds = androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + it.size.width, p.y + it.size.height)
            }, contentAlignment = Alignment.Center) {
                val dk = dialKey
                if (dk != null) {
                    DialFor(dk, s, st, onTouch = { dialTouch = System.currentTimeMillis() }) { k, v -> link.set(k to v); changed() }
                } else {
                    ZoomPill(s, zoomLive, zooming, onTap = { z ->
                        zoomLive = z; link.set("zoom" to z); changed(); buzz(); showHint("${fmtZ(z)}× · ${ZoomArc.mpx(z)}")
                    }, onDrag = { start, dx ->
                        if (!zooming) { zooming = true }
                        val R = arcRadius(landscape, midBounds.width, W)
                        val z = ZoomArc.zoomFor(start, dx, R)
                        if (z != zoomLive) {
                            view.performHapticFeedback(if (abs(z - z.roundToInt()) < 1e-6) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                            zoomLive = z
                            link.set("zoom" to z)
                        }
                    }, onDragEnd = { zooming = false; changed() })
                }
            }
            SideButton("POSE", poseText(s, st), auto = s.shutterUs == 0 && s.mode != "pose", on = dialKey == "ss", faded = zooming, rot = 0f) {
                dialKey = if (dialKey == "ss") null else "ss"; quickOpen = false; dialTouch = System.currentTimeMillis(); buzz()
            }
        }
        // paramètres du mode
        val mpMod = if (landscape) Modifier.align(if (mirror) Alignment.BottomEnd else Alignment.BottomStart)
            .padding(start = if (mirror) sideW else dp(stageLeft) + 14.dp, end = if (mirror) dp(W - stageRight) + 14.dp else sideW, bottom = 60.dp + ctlBottom)
        else Modifier.align(Alignment.BottomCenter).padding(bottom = 198.dp + ctlBottom)
        ModeParams(s, mpMod.alpha(if (zooming) 0f else 1f)) { k, v -> link.set(k to v); changed(); buzz() }

        // modes
        val modesMod = if (landscape) Modifier.align(if (mirror) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(start = if (mirror) 88.dp + dp(leftInset) else 0.dp, end = if (mirror) 0.dp else 88.dp + dp(rightInset)).width(100.dp).fillMaxHeight()
        else Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp + ctlBottom).fillMaxWidth().height(30.dp)
        ModeCarousel(s.mode, landscape, mirror, modesMod.alpha(if (zooming) 0f else 1f)) { m ->
            if (m != s.mode) { link.set("mode" to m); changed(); buzz(HapticFeedbackConstants.KEYBOARD_TAP) }
        }

        // déclencheur
        val srowMod = if (landscape) Modifier.align(if (mirror) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(start = if (mirror) dp(leftInset) else 0.dp, end = if (mirror) 0.dp else dp(rightInset)).width(92.dp).fillMaxHeight().padding(vertical = 16.dp)
        else Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp + ctlBottom).fillMaxWidth().height(70.dp).padding(horizontal = 30.dp)
        ShutterRow(
            landscape = landscape, modifier = srowMod,
            thumb = latest?.let { app.store.thumb(it.stem) }, count = photos.size, pop = thumbPop,
            onThumb = { onOpenGallery(latest?.stem) },
            st = st, s = s, disabled = (st.sdFull && !st.busy) || !online, skew = clockSkew,
            onDown = { shutterDown = true; press() }, onUp = { shutterDown = false; if (s.mode == "rafale" || (s.mode == "pose" && s.poseS == 0)) release() },
            lock = lock, onLock = onLockTap, onLockLong = onLockLong,
        )

        // arc du zoom
        if (zooming) {
            val R = arcRadius(landscape, midBounds.width, W)
            val topArc = midBounds.top - with(density) { (if (landscape) 58.dp else 66.dp).toPx() }
            ZoomArcOverlay(zoomLive, midBounds.center.x, topArc, R, 28, Modifier.fillMaxSize())
        }

        // compte à rebours, séries, pose
        CenterInfo(st, s, clockSkew, Modifier.offset(dp(stageLeft), dp(stageTop)).size(dp(stageW), dp(stageH)), fps)

        // revoir après la prise
        reviewStem?.let { stem ->
            app.store.thumb(stem)?.let { b ->
                Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit,
                    modifier = Modifier.offset(dp(stageLeft), dp(stageTop)).size(dp(stageW), dp(stageH)).background(Color(0xE6000000)).clickable { reviewStem = null })
            }
        }

        // indication centrale
        val h = hint
        AnimatedVisibility(h != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(250)),
            modifier = Modifier.offset(dp(stageLeft), dp(stageTop) + dp(stageH) * 0.38f - 14.dp).width(dp(stageW))) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(h?.text ?: "", style = cam(12.sp), modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Halo.GlassStrong).padding(horizontal = 11.dp, vertical = 6.dp))
            }
        }

        // reconnexion
        if (hasFrame && (!online || stalled)) {
            Box(Modifier.offset(dp(stageLeft), dp(stageTop) + 40.dp).width(dp(stageW)), contentAlignment = Alignment.Center) {
                Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Halo.GlassStrong).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(Halo.Warn); Spacer(Modifier.width(6.dp)); Text("Reconnexion…", style = mono(10.sp))
                }
            }
        }

        // panneau rapide
        QuickPanel(
            open = quickOpen, landscape = landscape, mirror = mirror, s = s, aids = aids,
            modifier = if (landscape) Modifier.offset(dp(stageLeft), 0.dp) else Modifier.offset(0.dp, dp(stageTop)),
            onClose = { quickOpen = false },
            onEv = { quickOpen = false; dialKey = "ev"; dialTouch = System.currentTimeMillis() },
            onFocusMode = { m ->
                link.set("af" to m); changed()
                if (m == "manual") { quickOpen = false; dialKey = "lens"; dialTouch = System.currentTimeMillis(); link.loupe(true) }
            },
            onCam = { k, v -> link.set(k to v); changed() },
            onAids = { f -> app.prefs.edit(f) },
            buzz = { buzz() },
        )

        // caméra introuvable
        if (!online && !hasFrame) NotFound(linkState, link.wifiRequested.collectAsState().value,
            onConnect = { link.requestWifi() }, onGallery = { onOpenGallery(null) })
    }
}

private fun fmtZ(z: Double) = fr1(z).replace(",0", "")

private fun arcRadius(landscape: Boolean, midW: Float, screenW: Float) =
    if (landscape) min(max(midW, 260f) * 0.8f + 60f * 2.5f, 1100f) else screenW * 0.95f

private fun isoText(s: CamSettings, st: CamStatus) = if (s.iso == 0) Steps.isoFromMeta(st.isoNow) else s.iso.toString()
private fun poseText(s: CamSettings, st: CamStatus) = when {
    s.mode == "pose" -> Steps.poseLabel(s.poseS)
    s.shutterUs == 0 -> Steps.shutterFromMeta(st.exposureUs)
    else -> Steps.shutterLabel(s.shutterUs)
}

// ==================================================================== éléments

@Composable
private fun TopBand(
    landscape: Boolean, mirror: Boolean, s: CamSettings, quickOpen: Boolean, modifier: Modifier,
    onLed: () -> Unit, onTimer: () -> Unit, onFormat: () -> Unit, onChevron: () -> Unit, onSettings: () -> Unit, onLook: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        BandButton(onLed) { HIcon(if (s.led) HaloIcons.Led else HaloIcons.LedOff, if (s.led) Halo.Ir else Halo.Text) }
        BandButton(onTimer) {
            HIcon(HaloIcons.Timer, if (s.timerS > 0) Halo.Ir else Halo.Text)
            Text(if (s.timerS == 0) "OFF" else "${s.timerS} s", style = cam(7.sp, FontWeight.Medium, Halo.Dim),
                modifier = Modifier.align(Alignment.BottomEnd).offset(2.dp, (-1).dp))
        }
        BandButton(onFormat) {
            val raw = s.format != "jpg"
            Box(Modifier.size(24.dp, 16.dp).border(1.4.dp, if (raw) Halo.Ir else Halo.Text, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                Text(if (s.format == "jpg") "JPG" else "RAW", style = cam(8.sp, FontWeight.SemiBold, if (raw) Halo.Ir else Halo.Text))
            }
        }
        val chevRot by animateFloatAsState(if (quickOpen) 180f else 0f, tween(250), label = "chev")
        Box(Modifier.size(if (landscape) 40.dp else 48.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onChevron), contentAlignment = Alignment.Center) {
            Box(Modifier.size(if (landscape) 24.dp else 44.dp, if (landscape) 40.dp else 24.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1E)), contentAlignment = Alignment.Center) {
                HIcon(HaloIcons.Chevron, size = 14.dp, modifier = Modifier.rotate(chevRot + if (landscape) -90f else 0f))
            }
        }
        BandButton(onSettings) { HIcon(HaloIcons.Gear, size = 21.dp) }
        Box(Modifier.size(44.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onLook), contentAlignment = Alignment.Center) {
            Column(Modifier.height(26.dp).widthIn(min = 36.dp).border(1.2.dp, Halo.Text, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("720", style = cam(8.sp, FontWeight.Medium).copy(lineHeight = 9.sp, letterSpacing = 0.06.sp))
                Text(Looks.badge[s.look] ?: "", style = cam(8.sp, FontWeight.Medium, Halo.Ir).copy(lineHeight = 9.sp, letterSpacing = 0.06.sp))
            }
        }
    }
    if (landscape) Column(modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) { content() }
    else Row(modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun BandButton(onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(44.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
private fun StatusChips(st: CamStatus, sync: fr.halo720.app.sync.SyncState, online: Boolean) {
    if (!online) return
    val powerColor = when { st.undervoltageNow -> Halo.Ir; st.undervoltageSeen -> Halo.Warn; else -> Color(0xCCFFFFFF) }
    Chip {
        HIcon(HaloIcons.Bolt, powerColor, 9.dp)
        Text(if (st.undervoltageNow) "faible" else "OK", style = mono(9.sp, color = powerColor))
    }
    st.photosLeft?.let { left ->
        val c = when { st.sdFull -> Halo.Ir; left < 30 -> Halo.Warn; else -> Color(0xCCFFFFFF) }
        Chip { Text(if (st.sdFull) "SD pleine" else "SD $left", style = mono(9.sp, color = c)) }
    }
    val raw = st.queueRaw + sync.rawPending
    if (raw > 0) Chip { PulseDot(Halo.Warn); Text("RAW $raw", style = mono(9.sp, color = Halo.Warn)) }
    if (st.hot) Chip { HIcon(HaloIcons.Thermo, Halo.Warn, 10.dp); Text("${st.tempC?.roundToInt()}°", style = mono(9.sp, color = Halo.Warn)) }
}

@Composable
private fun Chip(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.height(16.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x55000000)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
fun PulseDot(c: Color, size: androidx.compose.ui.unit.Dp = 5.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).alpha(a).clip(CircleShape).background(c))
}

@Composable
private fun Histogram(h: FloatArray, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x55000000)).padding(3.dp)) {
        val bw = size.width / h.size
        for (i in h.indices) {
            val hh = h[i] * size.height
            drawRect(Color(0x88FFFFFF), Offset(i * bw, size.height - hh), Size(bw * 0.8f, hh))
        }
    }
}

@Composable
private fun Overlays(geom: Geom, vis: androidx.compose.ui.geometry.Rect, aids: fr.halo720.app.Aids, an: FrameAnalyzer.Result?,
                     wantPeak: Boolean, s: CamSettings, st: CamStatus, rot: Int) {
    val blink = rememberInfiniteTransition(label = "blink").animateFloat(1f, 0f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "b")
    val levelAngle = rememberLevel(aids.level)
    Canvas(Modifier.fillMaxSize()) {
        // zones brûlées et netteté : masques calculés sur l'image, dessinés à sa place
        fun drawMask(b: android.graphics.Bitmap, alpha: Float) {
            drawIntoCanvas { c ->
                val m = Matrix().apply {
                    postScale(geom.imgW / b.width.toFloat(), geom.imgH / b.height.toFloat())
                    postConcat(geom.matrix())
                }
                c.nativeCanvas.drawBitmap(b, m, Paint().apply { this.alpha = (alpha * 255).toInt() })
            }
        }
        if (aids.clipping) an?.clip?.let { drawMask(it, blink.value) }
        if (wantPeak) an?.peak?.let { drawMask(it, 1f) }
        val l = vis.left; val t = vis.top; val w = vis.width; val h = vis.height
        val gc = Color(0x48FFFFFF)
        when (aids.grid) {
            "tiers" -> for (k in 1..2) {
                drawLine(gc, Offset(l + w * k / 3, t), Offset(l + w * k / 3, t + h), 1f)
                drawLine(gc, Offset(l, t + h * k / 3), Offset(l + w, t + h * k / 3), 1f)
            }
            "carre" -> {
                val side = min(w, h); val x0 = l + (w - side) / 2; val y0 = t + (h - side) / 2
                drawRect(gc, Offset(x0, y0), Size(side, side), style = Stroke(1f))
            }
            "diagonales" -> {
                drawLine(gc, Offset(l, t), Offset(l + w, t + h), 1f); drawLine(gc, Offset(l + w, t), Offset(l, t + h), 1f)
                for (k in 1..2) { drawLine(gc, Offset(l + w * k / 3, t), Offset(l + w * k / 3, t + h), 1f); drawLine(gc, Offset(l, t + h * k / 3), Offset(l + w, t + h * k / 3), 1f) }
            }
        }
        // niveau électronique
        val a = levelAngle.value
        if (aids.level && a != null) {
            val cx = l + w / 2; val cy = t + h / 2
            val ok = abs(a) < 1f
            val c = if (ok) Halo.Ok else Color(0xCCFFFFFF)
            val len = w * 0.16f
            val r = Math.toRadians(a.toDouble())
            val dx = (kotlin.math.cos(r) * len).toFloat(); val dy = (kotlin.math.sin(r) * len).toFloat()
            drawLine(c, Offset(cx - dx - 14.dp.toPx() * kotlin.math.cos(r).toFloat(), cy - dy - 14.dp.toPx() * kotlin.math.sin(r).toFloat()), Offset(cx - 14.dp.toPx() * kotlin.math.cos(r).toFloat(), cy - 14.dp.toPx() * kotlin.math.sin(r).toFloat()), 1.5.dp.toPx(), StrokeCap.Round)
            drawLine(c, Offset(cx + 14.dp.toPx() * kotlin.math.cos(r).toFloat(), cy + 14.dp.toPx() * kotlin.math.sin(r).toFloat()), Offset(cx + dx + 14.dp.toPx() * kotlin.math.cos(r).toFloat(), cy + dy + 14.dp.toPx() * kotlin.math.sin(r).toFloat()), 1.5.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0x55FFFFFF), Offset(cx - len - 14.dp.toPx(), cy), Offset(cx - 18.dp.toPx(), cy), 1f)
            drawLine(Color(0x55FFFFFF), Offset(cx + 18.dp.toPx(), cy), Offset(cx + len + 14.dp.toPx(), cy), 1f)
        }
    }
}

@Composable
private fun Corners(vis: androidx.compose.ui.geometry.Rect) {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color(0x88FFFFFF); val L = 14.dp.toPx(); val w = 1.6.dp.toPx()
        val l = vis.left; val t = vis.top; val r = vis.right; val b = vis.bottom
        drawLine(c, Offset(l, t), Offset(l + L, t), w); drawLine(c, Offset(l, t), Offset(l, t + L), w)
        drawLine(c, Offset(r, t), Offset(r - L, t), w); drawLine(c, Offset(r, t), Offset(r, t + L), w)
        drawLine(c, Offset(l, b), Offset(l + L, b), w); drawLine(c, Offset(l, b), Offset(l, b - L), w)
        drawLine(c, Offset(r, b), Offset(r - L, b), w); drawLine(c, Offset(r, b), Offset(r, b - L), w)
    }
}

@Composable
private fun FocusSquare(geom: Geom, focusUi: Offset?, st: CamStatus, lockUi: Boolean, tapAt: Long) {
    val sensor = focusUi ?: if (st.focusCustom) Offset(st.focusX.toFloat(), st.focusY.toFloat()) else null
    sensor ?: return
    val locked = lockUi || st.focusLocked
    // blanc pendant la recherche, rouge 720 nm quand c'est net ; clignote une fois si impossible
    var showFail by remember(tapAt) { mutableStateOf(false) }
    val sharp = st.af == "focused" || locked
    LaunchedEffect(tapAt, st.af) { if (st.af == "failed" && System.currentTimeMillis() - tapAt < 4000) { showFail = true; delay(300); showFail = false } }
    val fade by animateFloatAsState(if (System.currentTimeMillis() - tapAt < 3500 || locked) 1f else 0.0f, tween(600), label = "fade")
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tapAt) { delay(3600); tick++ }
    val p = geom.toScreen(sensor.x.toDouble(), sensor.y.toDouble())
    val sizePx = with(LocalDensity.current) { 54.dp.toPx() }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize().alpha(if (showFail) 0.2f else fade)) {
        val c = if (sharp) Halo.Ir else Color.White
        val half = sizePx / 2; val L = 10.dp.toPx(); val w = 1.2.dp.toPx()
        val l = p.x - half; val t = p.y - half; val r = p.x + half; val b = p.y + half
        drawLine(c, Offset(l, t), Offset(l + L, t), w); drawLine(c, Offset(l, t), Offset(l, t + L), w)
        drawLine(c, Offset(r, t), Offset(r - L, t), w); drawLine(c, Offset(r, t), Offset(r, t + L), w)
        drawLine(c, Offset(l, b), Offset(l + L, b), w); drawLine(c, Offset(l, b), Offset(l, b - L), w)
        drawLine(c, Offset(r, b), Offset(r - L, b), w); drawLine(c, Offset(r, b), Offset(r, b - L), w)
        if (locked) {
            val tl = measurer.measure("AE-L · AF-L", mono(9.sp, FontWeight.Bold, Halo.Ir).copy(letterSpacing = 0.1.sp))
            drawText(tl, topLeft = Offset(p.x - tl.size.width / 2f, t - 18.dp.toPx()))
        }
    }
}

@Composable
private fun SideButton(lbl: String, value: String, auto: Boolean, on: Boolean, faded: Boolean, rot: Float, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).alpha(if (faded) 0.35f else 1f).clip(CircleShape).background(Color(0x80141416))
        .border(1.dp, if (on) Halo.Ir else Color.Transparent, CircleShape)
        .clickable(remember { MutableInteractionSource() }, null, onClick = onClick), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.rotate(rot)) {
            Text(lbl, style = cam(7.sp, FontWeight.Medium, Color(0x99FFFFFF)).copy(letterSpacing = 0.12.sp, lineHeight = 8.sp))
            Row(verticalAlignment = Alignment.Top) {
                Text(value, style = cam(11.sp, FontWeight.Medium, if (on) Halo.Ir else Halo.Text).copy(lineHeight = 12.sp, letterSpacing = (-0.1).sp), maxLines = 1)
                if (auto) Text("A", style = cam(7.sp, FontWeight.SemiBold, Halo.Ir).copy(lineHeight = 8.sp))
            }
        }
    }
}

@Composable
private fun ZoomPill(s: CamSettings, z: Double, zooming: Boolean, onTap: (Double) -> Unit, onDrag: (Double, Float) -> Unit, onDragEnd: () -> Unit) {
    val stops = listOf(1.0, 2.0, 5.0)
    val active = stops.indexOfLast { z >= it - 1e-9 }.coerceAtLeast(0)
    val start = remember { doubleArrayOf(z) }
    val zNow = rememberUpdatedState(z)
    Row(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(Color(0x80141416)).padding(3.dp)
        .pointerInput(Unit) {
            // poser le doigt sur les crans et balayer = zoom immédiat ; relâcher = la molette se ferme
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                start[0] = zNow.value
                var moved = false
                var total = 0f
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    val dx = c.position.x - c.previousPosition.x
                    total += dx
                    if (!moved && abs(total) > 5.dp.toPx()) moved = true
                    if (moved) { c.consume(); onDrag(start[0], total) }
                }
                if (moved) onDragEnd()
                else {
                    val w = size.width / 3f
                    val i = (down.position.x / w).toInt().coerceIn(0, 2)
                    onTap(stops[i])
                }
            }
        }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        stops.forEachIndexed { i, v ->
            val on = i == active
            val txt = if (on) (if (abs(z - v) < 1e-9) (if (i == 0) "1x" else "${v.roundToInt()}x") else fmtZ(z)) else "${v.roundToInt()}"
            Box(Modifier.size(28.dp).clip(CircleShape).background(if (on) Color(0xAA000000) else Color.Transparent), contentAlignment = Alignment.Center) {
                Text(txt, style = cam(if (txt.length > 3) 10.sp else 12.sp, if (on) FontWeight.Medium else FontWeight.Normal, if (on) Halo.Ir else Color(0xEEFFFFFF)))
            }
        }
    }
    if (z > 2.0 && !zooming) {
        // au-delà de 2× : on affiche la définition pour éviter les surprises
        Text("${fmtZ(z)}× · ${ZoomArc.mpx(z)}", style = mono(8.sp, color = Color(0xCCFFFFFF)), modifier = Modifier.offset(y = (-26).dp))
    }
}

@Composable
private fun DialFor(key: String, s: CamSettings, st: CamStatus, onTouch: () -> Unit, onSet: (String, Any) -> Unit) {
    val spec = remember(key) {
        when (key) {
            "iso" -> DialSpec("iso", "ISO", Steps.iso.map { if (it == 0) "AUTO" else "$it" }, 40f, autoIndex = 0)
            "ss" -> DialSpec("ss", "TEMPS DE POSE", Steps.shutter.map { Steps.shutterLabel(it) }, 44f, autoIndex = 0)
            "ev" -> DialSpec("ev", "CORRECTION", Steps.ev.map { Steps.evLabel(it) }, 16f, major = { it % 3 == 0 })
            else -> DialSpec("lens", "MISE AU POINT", LENS.map { lensLabel(it) }, 14f, major = { LENS[it] in LENS_MAJ }, half = { false })
        }
    }
    val idx = when (key) {
        "iso" -> Steps.iso.indexOf(s.iso).coerceAtLeast(0)
        "ss" -> Steps.shutter.indexOf(s.shutterUs).coerceAtLeast(0)
        "ev" -> Steps.ev.indexOfFirst { abs(it - s.ev) < 0.1 }.coerceAtLeast(9)
        else -> LENS.indexOfFirst { abs(it - s.lensPosition) < 0.13 }.coerceAtLeast(0)
    }
    var cur by remember(key) { mutableIntStateOf(idx) }
    Box(Modifier.fillMaxSize()) {
        // valeur en grand au-dessus
        Column(Modifier.align(Alignment.TopCenter).offset(y = (-44).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(spec.title, style = mono(8.sp, color = Color(0xBBFFFFFF)).copy(letterSpacing = 0.08.sp))
            val v = spec.labels[cur]
            Text(if (key == "ev") "$v IL" else if (key == "ss" && v == "AUTO") "AUTO · ${Steps.shutterFromMeta(st.exposureUs)}"
                else if (key == "iso" && v == "AUTO") "AUTO · ${Steps.isoFromMeta(st.isoNow)}" else v,
                style = cam(15.sp, FontWeight.Medium, shadow = true))
        }
        RulerDial(spec, idx, onIndex = { i ->
            cur = i
            when (key) {
                "iso" -> onSet("iso", Steps.iso[i])
                "ss" -> onSet("shutter_us", Steps.shutter[i])
                "ev" -> onSet("ev", (Steps.ev[i] * 100).roundToInt() / 100.0)
                else -> onSet("lens_position", LENS[i])
            }
        }, onTouch = onTouch, modifier = Modifier.fillMaxSize())
    }
}

/** Mise au point manuelle : dioptries (0 = infini ; 10 = 10 cm). */
private val LENS = (0..40).map { it * 0.25 }
private val LENS_MAJ = setOf(0.0, 0.5, 1.0, 2.0, 4.0, 10.0)
private fun lensLabel(d: Double) = when {
    d <= 0.0 -> "∞"
    1 / d >= 1 -> "${fr1(1 / d).replace(",0", "")} m"
    else -> "${(100 / d).roundToInt()} cm"
}

@Composable
private fun ModeParams(s: CamSettings, modifier: Modifier, onSet: (String, Any) -> Unit) {
    data class P(val label: String, val on: Boolean, val k: String, val v: Any)
    val items: List<P> = when (s.mode) {
        "pose" -> Steps.pose.map { P(Steps.poseLabel(it), s.poseS == it, "pose_s", it) }
        "rafale" -> listOf(P("Max", s.rafaleFps == 0, "rafale_fps", 0), P("5 img/s", s.rafaleFps == 5, "rafale_fps", 5), P("2 img/s", s.rafaleFps == 2, "rafale_fps", 2))
        "interv" -> listOf(2, 5, 10, 30, 60).map { P(if (it >= 60) "1 min" else "$it s", s.intervS == it, "interv_s", it) } +
            listOf(P(if (s.intervCount == 0) "× ∞" else "× ${s.intervCount}", true, "interv_count",
                when (s.intervCount) { 50 -> 100; 100 -> 300; 300 -> 1000; 1000 -> 0; else -> 50 }))
        "bracket" -> listOf(P("3 × ±1", s.bracketN == 3 && s.bracketStep == 1.0, "bracket", 31), P("5 × ±1", s.bracketN == 5 && s.bracketStep == 1.0, "bracket", 51),
            P("3 × ±2", s.bracketN == 3 && s.bracketStep == 2.0, "bracket", 32))
        else -> emptyList()
    }
    AnimatedVisibility(items.isNotEmpty(), modifier = modifier, enter = fadeIn(tween(200)) + slideInVertically { it / 3 }, exit = fadeOut(tween(150))) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
            items.forEach { p ->
                Box(Modifier.height(22.dp).clip(RoundedCornerShape(11.dp)).background(if (p.on && p.k != "interv_count") Color.White else Color(0x8C141416))
                    .clickable {
                        if (p.k == "bracket") { val n = (p.v as Int) / 10; val st = (p.v as Int) % 10; onSet("bracket_n", n); onSet("bracket_step", st.toDouble()) }
                        else onSet(p.k, p.v)
                    }.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                    Text(p.label, style = cam(11.sp, color = if (p.on && p.k != "interv_count") Color.Black else Color(0xDDFFFFFF)))
                }
            }
        }
    }
}

@Composable
private fun ModeCarousel(mode: String, landscape: Boolean, mirror: Boolean, modifier: Modifier, onMode: (String) -> Unit) {
    val centers = remember { mutableStateMapOf<String, Float>() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val target = centers[mode]?.let { (if (landscape) box.height else box.width) / 2f - it } ?: 0f
    val off by animateFloatAsState(target, tween(220), label = "modes")
    val curMode = rememberUpdatedState(mode)
    Box(modifier.onSizeChanged { box = it }.clip(RoundedCornerShape(0.dp))
        .pointerInput(landscape) {
            var acc = 0f
            if (landscape) detectVerticalDragGestures(onDragStart = { acc = 0f }, onDragEnd = {
                val i = MODES.indexOfFirst { it.first == curMode.value }
                if (acc < -30) onMode(MODES[(i + 1).coerceAtMost(4)].first) else if (acc > 30) onMode(MODES[(i - 1).coerceAtLeast(0)].first)
            }) { _, d -> acc += d }
            else detectHorizontalDragGestures(onDragStart = { acc = 0f }, onDragEnd = {
                val i = MODES.indexOfFirst { it.first == curMode.value }
                if (acc < -30) onMode(MODES[(i + 1).coerceAtMost(4)].first) else if (acc > 30) onMode(MODES[(i - 1).coerceAtLeast(0)].first)
            }) { _, d -> acc += d }
        }) {
        val items: @Composable () -> Unit = {
            MODES.forEach { (k, name) ->
                val on = k == mode
                Text(name, style = cam(if (landscape) 12.5.sp else 13.sp, if (on) FontWeight.Medium else FontWeight.Normal, if (on) Halo.Ir else Color(0xDDFFFFFF), shadow = true),
                    textAlign = if (landscape && !mirror) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.onGloballyPositioned { c -> centers[k] = if (landscape) c.positionInParent().y + c.size.height / 2f else c.positionInParent().x + c.size.width / 2f }
                        .clickable(remember { MutableInteractionSource() }, null) { onMode(k) }
                        .padding(horizontal = if (landscape) 6.dp else 11.dp, vertical = if (landscape) 7.dp else 6.dp))
            }
        }
        if (landscape) Column(Modifier.align(if (mirror) Alignment.TopStart else Alignment.TopEnd).offset { androidx.compose.ui.unit.IntOffset(0, off.roundToInt()) }, horizontalAlignment = if (mirror) Alignment.Start else Alignment.End) { items() }
        else Row(Modifier.align(Alignment.CenterStart).wrapContentWidth(unbounded = true, align = Alignment.Start).offset { androidx.compose.ui.unit.IntOffset(off.roundToInt(), 0) }, verticalAlignment = Alignment.CenterVertically) { items() }
    }
}

private fun androidx.compose.ui.layout.LayoutCoordinates.positionInParent(): Offset =
    parentLayoutCoordinates?.localPositionOf(this, Offset.Zero) ?: Offset.Zero

@Composable
private fun ShutterRow(
    landscape: Boolean, modifier: Modifier, thumb: android.graphics.Bitmap?, count: Int, pop: Long, onThumb: () -> Unit,
    st: CamStatus, s: CamSettings, disabled: Boolean, skew: Double, onDown: () -> Unit, onUp: () -> Unit,
    lock: OrientLock, onLock: () -> Unit, onLockLong: () -> Unit,
) {
    val popScale = remember { Animatable(1f) }
    LaunchedEffect(pop) { if (pop > 0) { popScale.animateTo(1.15f, tween(120)); popScale.animateTo(1f, tween(220)) } }
    val items: @Composable () -> Unit = {
        // vignette de la dernière photo
        Box(Modifier.size(40.dp).scale(popScale.value).clip(RoundedCornerShape(6.dp)).background(Color(0xFF111111))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp)).clickable(onClick = onThumb)) {
            if (thumb != null) Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (count > 0) Text("$count", style = mono(7.sp, FontWeight.Bold).copy(shadow = ImageShadow), modifier = Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 1.dp))
        }
        Shutter(st, s, disabled, skew, onDown, onUp)
        Box(Modifier.size(40.dp).clip(CircleShape).pointerInput(Unit) { detectTapGestures(onTap = { onLock() }, onLongPress = { onLockLong() }) }, contentAlignment = Alignment.Center) {
            when (lock) {
                OrientLock.AUTO -> HIcon(HaloIcons.Rotate, size = 22.dp)
                OrientLock.LANDSCAPE -> HIcon(HaloIcons.LockLand, Halo.Ir, 22.dp)
                OrientLock.PORTRAIT -> HIcon(HaloIcons.LockPort, Halo.Ir, 22.dp)
            }
        }
    }
    if (landscape) Column(modifier, verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
        // de haut en bas : verrou, déclencheur, vignette
        val l = @Composable { Box(Modifier.size(40.dp).clip(CircleShape).pointerInput(Unit) { detectTapGestures(onTap = { onLock() }, onLongPress = { onLockLong() }) }, contentAlignment = Alignment.Center) {
            when (lock) { OrientLock.AUTO -> HIcon(HaloIcons.Rotate, size = 22.dp); OrientLock.LANDSCAPE -> HIcon(HaloIcons.LockLand, Halo.Ir, 22.dp); OrientLock.PORTRAIT -> HIcon(HaloIcons.LockPort, Halo.Ir, 22.dp) } } }
        l()
        Shutter(st, s, disabled, skew, onDown, onUp)
        Box(Modifier.size(40.dp).scale(popScale.value).clip(RoundedCornerShape(6.dp)).background(Color(0xFF111111))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp)).clickable(onClick = onThumb)) {
            if (thumb != null) Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (count > 0) Text("$count", style = mono(7.sp, FontWeight.Bold).copy(shadow = ImageShadow), modifier = Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 1.dp))
        }
    }
    else Row(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { items() }
}

@Composable
private fun Shutter(st: CamStatus, s: CamSettings, disabled: Boolean, skew: Double, onDown: () -> Unit, onUp: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (pressed) 0.88f else 1f, tween(80), label = "sh")
    val busy = st.busy
    // anneau de progression (pose longue, série)
    var now by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(busy) { while (busy) { now = System.currentTimeMillis() / 1000.0 + skew; delay(50) } }
    val prog: Float? = when {
        st.exposureStart != null && st.exposureEnd != null -> (((now - st.exposureStart) / (st.exposureEnd - st.exposureStart)).toFloat()).coerceIn(0f, 1f)
        st.series != null && st.series.total > 0 -> st.series.done / st.series.total.toFloat()
        else -> null
    }
    val downU = rememberUpdatedState(onDown); val upU = rememberUpdatedState(onUp); val dis = rememberUpdatedState(disabled)
    Box(Modifier.size(66.dp).alpha(if (disabled && !busy) 0.4f else 1f).pointerInput(Unit) {
        // déclenchement à l'appui (pas au relâchement)
        awaitEachGesture {
            awaitFirstDown()
            pressed = true
            if (!dis.value || busy) downU.value()
            do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
            pressed = false
            upU.value()
        }
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White, size.minDimension / 2 - 1.25.dp.toPx(), style = Stroke(2.5.dp.toPx()))
            val inner = (size.minDimension / 2 - 6.5.dp.toPx()) * sc
            drawCircle(if (pressed || busy) Halo.Ir else Color.White, inner)
            if (prog != null) {
                val r = size.minDimension / 2 + 5.dp.toPx()
                drawArc(Halo.Ir, -90f, 360f * prog, false, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun CenterInfo(st: CamStatus, s: CamSettings, skew: Double, modifier: Modifier, fps: Double) {
    var now by remember { mutableDoubleStateOf(System.currentTimeMillis() / 1000.0 + skew) }
    LaunchedEffect(st.busy, st.timerLeft) { while (true) { now = System.currentTimeMillis() / 1000.0 + skew; delay(100); if (!st.busy) break } }
    Box(modifier) {
        // compte à rebours géant
        st.timerLeft?.let { tl ->
            val left = kotlin.math.ceil(tl).toInt().coerceAtLeast(1)
            Text("$left", style = cam(96.sp, FontWeight.Medium, shadow = true), modifier = Modifier.align(Alignment.Center))
        }
        // pose longue : temps restant, « ne bouge pas »
        if (st.exposureStart != null) {
            val left = st.exposureEnd?.let { (it - now).coerceAtLeast(0.0) }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (left != null) "${fr1(left)} s" else "${fr1(now - st.exposureStart)} s", style = cam(34.sp, FontWeight.Medium, shadow = true))
                Text("ne bouge pas", style = cam(12.sp, color = Color(0xCCFFFFFF), shadow = true))
            }
        }
        // série en cours
        st.series?.let { sr ->
            val txt = when (sr.kind) {
                "interv" -> {
                    val tot = if (sr.total == 0) "" else " / ${sr.total}"
                    val fin = sr.endAt?.let { " · fin " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(((it - skew) * 1000).toLong())) } ?: ""
                    val next = sr.nextAt?.let { n -> val d = (n - now).roundToInt(); if (d > 0) " · prochaine dans ${fmtDur(d)}" else "" } ?: ""
                    "${sr.done}$tot$fin$next"
                }
                "rafale" -> "${sr.done} images" + if (st.queueJpg > 0) " · tampon ${st.queueJpg}" else ""
                "bracket" -> "Bracketing ${sr.done} / ${sr.total}"
                else -> "${sr.done} / ${sr.total}"
            }
            Row(Modifier.align(Alignment.TopCenter).padding(top = 30.dp).clip(RoundedCornerShape(10.dp)).background(Halo.GlassStrong).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PulseDot(Halo.Ir); Spacer(Modifier.width(6.dp))
                Text(txt, style = mono(10.sp))
                if (sr.kind == "bracket") {
                    Spacer(Modifier.width(6.dp))
                    repeat(sr.total) { i -> Box(Modifier.padding(horizontal = 1.5.dp).size(6.dp).clip(CircleShape).background(if (i < sr.done) Halo.Ir else Color(0x44FFFFFF))) }
                }
            }
        }
        // aperçu ralenti (pose > 1 s)
        if (fps in 0.05..1.6 && st.exposureStart == null && st.series == null) {
            Text("aperçu ${if (fps < 1) "1 img / ${(1 / fps).roundToInt()} s" else "1 img/s"}", style = mono(9.sp, color = Color(0xCCFFFFFF)),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x55000000)).padding(horizontal = 7.dp, vertical = 2.dp))
        }
    }
}

private fun fmtDur(s: Int) = when { s < 60 -> "$s s"; s < 3600 -> "${s / 60} min ${if (s % 60 > 0) "${s % 60} s" else ""}".trim(); else -> "${s / 3600} h ${(s % 3600) / 60} min" }

@Composable
private fun QuickPanel(
    open: Boolean, landscape: Boolean, mirror: Boolean, s: CamSettings, aids: fr.halo720.app.Aids, modifier: Modifier,
    onClose: () -> Unit, onEv: () -> Unit, onFocusMode: (String) -> Unit, onCam: (String, Any) -> Unit,
    onAids: ((fr.halo720.app.Aids) -> fr.halo720.app.Aids) -> Unit, buzz: () -> Unit,
) {
    AnimatedVisibility(open, modifier = modifier,
        enter = if (landscape) slideInHorizontally(tween(280)) { -it } else slideInVertically(tween(280)) { -it },
        exit = if (landscape) slideOutHorizontally(tween(220)) { -it } else slideOutVertically(tween(220)) { -it }) {
        val grid = when (aids.grid) { "tiers" -> "Tiers"; "carre" -> "Carré"; "diagonales" -> "Diag."; else -> "Aucune" }
        val af = when (s.af) { "continuous" -> "Continue"; "single" -> "Unique"; else -> "Manuelle" }
        val mes = when (s.metering) { "matrix" -> "Matricielle"; "centre" -> "Pondérée"; else -> "Spot" }
        val tiles = listOf(
            Triple(HaloIcons.Correction, "Correction", "${Steps.evLabel(s.ev)} IL") to (s.ev != 0.0),
            Triple(HaloIcons.Grid, "Grille", grid) to (aids.grid != "aucune"),
            Triple(HaloIcons.Level, "Niveau", if (aids.level) "Oui" else "Non") to aids.level,
            Triple(HaloIcons.Histo, "Histogramme", if (aids.histogram) "Oui" else "Non") to aids.histogram,
            Triple(HaloIcons.Focus, "Mise au point", af) to (s.af != "continuous"),
            Triple(HaloIcons.Metering, "Mesure", mes) to (s.metering != "matrix"),
            Triple(HaloIcons.Zebra, "Zones brûlées", if (aids.clipping) "Oui" else "Non") to aids.clipping,
            Triple(HaloIcons.Sound, "Son", if (aids.sound) "Oui" else "Non") to aids.sound,
        )
        val act: (Int) -> Unit = { i ->
            buzz()
            when (i) {
                0 -> onEv()
                1 -> onAids { it.copy(grid = when (it.grid) { "tiers" -> "carre"; "carre" -> "diagonales"; "diagonales" -> "aucune"; else -> "tiers" }) }
                2 -> onAids { it.copy(level = !it.level) }
                3 -> onAids { it.copy(histogram = !it.histogram) }
                4 -> onFocusMode(when (s.af) { "continuous" -> "single"; "single" -> "manual"; else -> "continuous" })
                5 -> onCam("metering", when (s.metering) { "matrix" -> "centre"; "centre" -> "spot"; else -> "matrix" })
                6 -> onAids { it.copy(clipping = !it.clipping) }
                7 -> onAids { it.copy(sound = !it.sound) }
            }
        }
        val cols = 4
        Column((if (landscape) Modifier.width(300.dp).fillMaxHeight() else Modifier.fillMaxWidth())
            .background(Color(0xEB000000)).padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tiles.chunked(cols).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { c, (t, on) ->
                        val i = r * cols + c
                        Column(Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x0BFFFFFF)).clickable { act(i) },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            HIcon(t.first, Color(0xE6FFFFFF), 17.dp)
                            Spacer(Modifier.height(3.dp))
                            Text(t.second, style = cam(9.5.sp, color = Color(0xCCFFFFFF)), maxLines = 1)
                            Text(t.third, style = cam(9.5.sp, FontWeight.Medium, if (on) Halo.Ir else Halo.Dim), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotFound(l: Link, requested: Boolean, onConnect: () -> Unit, onGallery: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Halo.Bg).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Canvas(Modifier.size(64.dp)) {
                drawCircle(Halo.Ir, size.minDimension / 2 - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
                drawCircle(Halo.Ir, size.minDimension / 5, style = Stroke(2.dp.toPx()))
            }
            Spacer(Modifier.height(22.dp))
            Text(if (l == Link.CONNECTING || requested) "Connexion à HALO-720…" else "Caméra introuvable · allume-la", style = sans(15.sp, FontWeight.Medium))
            Spacer(Modifier.height(6.dp))
            Text("La LED reste fixe quand le Wi-Fi HALO-720 est prêt (≈ 25 s).", style = sans(12.sp, color = Halo.Dim), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Halo.Ir).clickable(onClick = onConnect).padding(horizontal = 18.dp, vertical = 11.dp)) {
                Text("Se connecter à HALO-720", style = sans(13.sp, FontWeight.SemiBold))
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF)).clickable(onClick = onGallery).padding(horizontal = 18.dp, vertical = 11.dp)) {
                Text("Galerie", style = sans(13.sp, FontWeight.SemiBold))
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { PulseDot(Halo.Dim); Spacer(Modifier.width(6.dp)); Text("nouvel essai toutes les 2 s", style = mono(9.sp, color = Halo.Faint)) }
        }
    }
}

/** Bus des déclencheurs extérieurs (touches volume). */
class ShutterBus {
    val events = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
}
