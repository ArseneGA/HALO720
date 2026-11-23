package fr.halo720.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.halo720.app.Aids
import fr.halo720.app.BuildConfig
import fr.halo720.app.HaloApp
import fr.halo720.app.data.CamSettings
import fr.halo720.app.data.Steps
import fr.halo720.app.data.fr1
import fr.halo720.app.net.Link
import fr.halo720.app.sync.SyncService
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

private class Choice(val title: String, val options: List<Pair<String, Any>>, val current: Any?, val onPick: (Any) -> Unit)
private class Confirm(val title: String, val body: String, val action: String, val danger: Boolean, val onOk: () -> Unit)
private class Input(val title: String, val fields: List<Triple<String, String, Boolean>>, val action: String, val onOk: (List<String>) -> String?)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsPage(onClose: () -> Unit, landscape: Boolean, onLock: (OrientLock) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as HaloApp
    val link = app.link
    val s by link.settings.collectAsState()
    val aids by app.prefs.aids.collectAsState()
    val profiles by app.profiles.list.collectAsState()
    val active by app.profiles.active.collectAsState()
    val modified by app.profiles.modified.collectAsState()
    val pager = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var choice by remember { mutableStateOf<Choice?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var input by remember { mutableStateOf<Input?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var drag by remember { mutableFloatStateOf(0f) }
    val buzz = { if (aids.vibrations) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun cam(k: String, v: Any) { link.set(k to v); app.profiles.markModified(); buzz() }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2200); toast = null } }

    val ctx = Ctx(s, aids, ::cam, { f -> app.prefs.edit(f); buzz() },
        pick = { t, opts, cur, f -> choice = Choice(t, opts, cur, f) },
        ask = { t, b, a, d, f -> confirm = Confirm(t, b, a, d, f) },
        input = { t, fields, a, f -> input = Input(t, fields, a, f) },
        toast = { toast = it }, onLock = onLock)

    Box(Modifier.fillMaxSize().offset(y = with(androidx.compose.ui.platform.LocalDensity.current) { drag.toDp() }).background(Halo.Page)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            // en-tête (glisser vers le bas = fermer)
            Row(Modifier.fillMaxWidth().pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = { if (drag > 120) onClose(); drag = 0f }, onDragCancel = { drag = 0f }) { _, d -> drag = (drag + d).coerceAtLeast(0f) }
            }.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0x0DFFFFFF)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    HIcon(HaloIcons.Back, size = 14.dp)
                }
                Spacer(Modifier.width(8.dp))
                Text("Réglages", style = sans(17.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                app.profiles.get(active)?.let { p ->
                    Text("● ${p.name}${if (modified) " •" else ""}", style = label(color = Halo.Ir))
                }
            }
            // onglets
            val tabs = listOf("Image", "Aides", "Système", "Profils")
            BoxWithConstraints(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth().height(32.dp).clip(RoundedCornerShape(9.dp)).background(Color(0x0BFFFFFF)).padding(2.dp)) {
                val w = maxWidth / 4
                val pos = pager.currentPage + pager.currentPageOffsetFraction
                Box(Modifier.offset(x = w * pos).width(w).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(Color(0x16FFFFFF)))
                Row(Modifier.fillMaxSize()) {
                    tabs.forEachIndexed { i, t ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { scope.launch { pager.animateScrollToPage(i) } },
                            contentAlignment = Alignment.Center) {
                            Text(t, style = sans(11.5.sp, FontWeight.SemiBold, if (pager.currentPage == i) Halo.Text else Halo.Dim))
                        }
                    }
                }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { p ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)) {
                    when (p) {
                        0 -> ImageTab(ctx)
                        1 -> AidsTab(ctx)
                        2 -> SystemTab(ctx)
                        else -> ProfilesTab(ctx, profiles.map { it }, active, modified)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // feuilles et confirmations
        choice?.let { c -> ChoiceSheet(c) { choice = null } }
        confirm?.let { c -> ConfirmDialog(c) { confirm = null } }
        input?.let { c -> InputDialog(c) { input = null } }
        AnimatedVisibility(toast != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)) {
            Text(toast ?: "", style = sans(12.sp), modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Halo.GlassStrong).padding(horizontal = 14.dp, vertical = 9.dp))
        }
    }
}

private class Ctx(
    val s: CamSettings, val aids: Aids, val cam: (String, Any) -> Unit, val aid: ((Aids) -> Aids) -> Unit,
    val pick: (String, List<Pair<String, Any>>, Any?, (Any) -> Unit) -> Unit,
    val ask: (String, String, String, Boolean, () -> Unit) -> Unit,
    val input: (String, List<Triple<String, String, Boolean>>, String, (List<String>) -> String?) -> Unit,
    val toast: (String) -> Unit, val onLock: (OrientLock) -> Unit,
)

// ==================================================================== onglets

@Composable
private fun ImageTab(c: Ctx) {
    val s = c.s
    val app = LocalContext.current.applicationContext as HaloApp
    val scope = rememberCoroutineScope()
    var calibrating by remember { mutableStateOf(false) }
    Group("Fichiers") {
        Minis(listOf("JPEG" to "jpg", "JPEG + RAW" to "jpg+raw", "RAW" to "raw"), s.format) { c.cam("format", it) }
        ValueRow("Qualité JPEG", when { s.jpegQuality >= 97 -> "Extra · ${s.jpegQuality}"; s.jpegQuality >= 90 -> "Fine · ${s.jpegQuality}"; else -> "Normale · ${s.jpegQuality}" }) {
            c.pick("Qualité JPEG", listOf("Normale · 85" to 85, "Fine · 92" to 92, "Extra · 98" to 98), s.jpegQuality) { c.cam("jpeg_quality", it) }
        }
        SwitchRow("Look enregistré dans le JPEG", "Le RAW reste toujours brut", s.lookInJpeg) { c.cam("look_in_jpeg", it) }
    }
    Group("Exposition") {
        val mes = mapOf("matrix" to "Matricielle", "centre" to "Pondérée centrale", "spot" to "Spot")
        ValueRow("Mesure", mes[s.metering] ?: "") { c.pick("Mesure", mes.map { it.value to it.key }, s.metering) { c.cam("metering", it) } }
        ValueRow("ISO auto max", "${s.isoMax}") { c.pick("ISO auto maximum", listOf(400, 800, 1600).map { "$it" to it }, s.isoMax) { c.cam("iso_max", it) } }
        ValueRow("Pose auto la plus lente", Steps.shutterLabel(s.slowestAutoUs)) {
            c.pick("Pose auto la plus lente", listOf(4000, 8000, 16667, 33333, 66667, 125000, 250000, 500000, 1000000).map { Steps.shutterLabel(it) to it }, s.slowestAutoUs) { c.cam("slowest_auto_us", it) }
        }
        val wb = mapOf("auto" to "Auto", "daylight" to "Lumière du jour", "ir" to "IR perso")
        ValueRow("Balance des blancs", (wb[s.awb] ?: "") + if (s.awb == "ir" && s.awbGains == null) " · non calibrée" else "") {
            c.pick("Balance des blancs", wb.map { it.value to it.key }, s.awb) { c.cam("awb", it) }
        }
        ActionRow(if (calibrating) "Calibration…" else "Calibrer sur l'herbe au soleil",
            s.awbGains?.let { "Gains R ${fr1(it[0])} · B ${fr1(it[1])}" } ?: "Vise de l'herbe au soleil, plein cadre") {
            if (calibrating) return@ActionRow
            c.ask("Calibrer la balance IR", "Vise de l'herbe bien éclairée par le soleil, qui remplit le centre de l'image. Ça prend 3 secondes.", "Calibrer", false) {
                calibrating = true
                scope.launch {
                    try { app.link.calibrateWb(); c.toast("Balance IR calibrée") } catch (e: Exception) { c.toast("Calibration impossible : ${e.message}") }
                    calibrating = false
                }
            }
        }
    }
    Group("Mise au point") {
        Minis(listOf("Continue" to "continuous", "Unique" to "single", "Manuelle" to "manual"), s.af) { c.cam("af", it) }
        SwitchRow("Toucher pour viser", null, c.aids.touchAf) { v -> c.aid { it.copy(touchAf = v) } }
        ValueRow("Zone d'AF", if (s.afZone == "point") "Point" else "Large") { c.pick("Zone d'AF", listOf("Point" to "point", "Large" to "large"), s.afZone) { c.cam("af_zone", it) } }
    }
    Group("Traitement") {
        val nr = mapOf("off" to "Aucune", "light" to "Légère", "strong" to "Forte")
        ValueRow("Réduction du bruit", nr[s.nr] ?: "") { c.pick("Réduction du bruit", nr.map { it.value to it.key }, s.nr) { c.cam("nr", it) } }
        ValueRow("Netteté", if (s.sharpness > 0) "+${s.sharpness}" else "${s.sharpness}") {
            c.pick("Netteté", (-2..2).map { (if (it > 0) "+$it" else "$it") to it }, s.sharpness) { c.cam("sharpness", it) }
        }
    }
    Group("Modes") {
        ValueRow("Rafale · limite", "${s.rafaleLimit} images") { c.pick("Limite de la rafale", listOf(20, 50, 100, 200, 500).map { "$it images" to it }, s.rafaleLimit) { c.cam("rafale_limit", it) } }
        SwitchRow("RAW en rafale", "23 Mo par image : sature vite la mémoire", s.rafaleRaw) { c.cam("rafale_raw", it) }
        ValueRow("Intervalles · intervalle", fmtS(s.intervS)) {
            c.pick("Intervalle", listOf(1, 2, 5, 10, 30, 60, 300, 900, 3600).map { fmtS(it) to it }, s.intervS) { c.cam("interv_s", it) }
        }
        ValueRow("Intervalles · nombre", if (s.intervCount == 0) "Sans limite" else "${s.intervCount} images" + " · " + fmtS(s.intervCount * s.intervS)) {
            c.pick("Nombre d'images", listOf(10, 50, 100, 300, 600, 1000, 0).map { (if (it == 0) "Sans limite" else "$it images") to it }, s.intervCount) { c.cam("interv_count", it) }
        }
        ValueRow("Intervalles · départ différé", if (s.intervDelayS == 0) "Non" else fmtS(s.intervDelayS)) {
            c.pick("Départ différé", listOf(0, 10, 60, 300, 900, 1800, 3600).map { (if (it == 0) "Non" else fmtS(it)) to it }, s.intervDelayS) { c.cam("interv_delay_s", it) }
        }
        ValueRow("Bracketing · ordre", if (s.bracketOrder == "0-+") "0 · − · +" else "− · 0 · +") {
            c.pick("Ordre du bracketing", listOf("0 · − · +" to "0-+", "− · 0 · +" to "-0+"), s.bracketOrder) { c.cam("bracket_order", it) }
        }
    }
}

private fun fmtS(s: Int) = when {
    s < 60 -> "$s s"
    s < 3600 -> if (s % 60 == 0) "${s / 60} min" else "${s / 60} min ${s % 60} s"
    else -> if (s % 3600 == 0) "${s / 3600} h" else "${s / 3600} h ${(s % 3600) / 60} min"
}

@Composable
private fun AidsTab(c: Ctx) {
    val a = c.aids
    Group("Superpositions") {
        val grids = mapOf("aucune" to "Aucune", "tiers" to "Tiers", "carre" to "Carré", "diagonales" to "Diagonales")
        ValueRow("Grille", grids[a.grid] ?: "") { c.pick("Grille", grids.map { it.value to it.key }, a.grid) { v -> c.aid { it.copy(grid = v as String) } } }
        SwitchRow("Niveau électronique", null, a.level) { v -> c.aid { it.copy(level = v) } }
        SwitchRow("Histogramme", null, a.histogram) { v -> c.aid { it.copy(histogram = v) } }
        SwitchRow("Zones brûlées", "Clignotent en rouge", a.clipping) { v -> c.aid { it.copy(clipping = v) } }
        SwitchRow("Surlignage de netteté", "En mise au point manuelle", a.peaking) { v -> c.aid { it.copy(peaking = v) } }
    }
    Group("Cadrage") {
        Minis(listOf("Plein écran recadré" to "crop", "Image entière" to "fit"), a.framing) { v -> c.aid { it.copy(framing = v as String) } }
        val o = mapOf("auto" to "Auto", "paysage" to "Paysage", "portrait" to "Portrait")
        ValueRow("Orientation", o[a.orientation] ?: "Auto") {
            c.pick("Orientation", o.map { it.value to it.key }, a.orientation) { v ->
                c.aid { it.copy(orientation = v as String) }
                c.onLock(when (v) { "paysage" -> OrientLock.LANDSCAPE; "portrait" -> OrientLock.PORTRAIT; else -> OrientLock.AUTO })
            }
        }
        SwitchRow("Commandes à gauche (paysage)", null, a.leftHanded) { v -> c.aid { it.copy(leftHanded = v) } }
    }
    Group("Retours") {
        SwitchRow("Vibrations", null, a.vibrations) { v -> c.aid { it.copy(vibrations = v) } }
        SwitchRow("Son du déclencheur", null, a.sound) { v -> c.aid { it.copy(sound = v) } }
        SwitchRow("Touches volume = déclencheur", null, a.volumeKeys) { v -> c.aid { it.copy(volumeKeys = v) } }
        ValueRow("Revoir après la prise", if (a.review) "2 s" else "Non") { c.pick("Revoir après la prise", listOf("Non" to false, "2 s" to true), a.review) { v -> c.aid { it.copy(review = v as Boolean) } } }
    }
}

@Composable
private fun SystemTab(c: Ctx) {
    val app = LocalContext.current.applicationContext as HaloApp
    val ctx = LocalContext.current
    val st by app.link.status.collectAsState()
    val linkState by app.link.link.collectAsState()
    val ver by app.link.version.collectAsState()
    val sync by app.sync.state.collectAsState()
    val scope = rememberCoroutineScope()
    val online = linkState == Link.ONLINE
    var autoSave by remember { mutableStateOf(app.sync.autoSave) }
    var copyRaw by remember { mutableStateOf(app.sync.copyRaw) }
    var delAfter by remember { mutableStateOf(app.sync.deleteAfterCopy) }
    var wifiName by remember { mutableStateOf(app.link.ssid) }
    var rssi by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(online) { rssi = wifiSignal(ctx) }
    Group("Caméra") {
        if (!online) InfoRow("Caméra", "non connectée", Halo.Warn)
        val pw = when { st.undervoltageNow -> "Tension faible" to Halo.Ir; st.undervoltageSeen -> "Baisse de tension vue" to Halo.Warn; else -> "OK" to Halo.Dim }
        InfoRow("Alimentation", pw.first, pw.second, sub = "La batterie USB ne donne pas son niveau à la caméra")
        val total = st.diskTotal ?: 0; val free = st.diskFree ?: 0
        BarRow("microSD", if (total > 0) (total - free).toFloat() / total else 0f,
            if (total > 0) "${((total - free) / 1e9).roundToInt()} / ${(total / 1e9).roundToInt()} Go" else "—",
            st.photosLeft?.let { "≈ $it photos restantes" })
        InfoRow("Wi-Fi", rssi?.let { if (it > -55) "Fort" else if (it > -70) "Moyen" else "Faible" } ?: "—", sub = "${wifiName} · 2,4 GHz")
        InfoRow("Température", st.tempC?.let { "${it.roundToInt()} °C" } ?: "—", if (st.hot) Halo.Warn else Halo.Dim)
    }
    Group("Sauvegarde sur le téléphone") {
        SwitchRow("Sauvegarde auto", "JPEG tout de suite, RAW en file", autoSave) { autoSave = it; app.sync.autoSave = it }
        InfoRow("Dossier", "Images/HALO720")
        SwitchRow("Copier aussi les RAW", "≈ 15 Mo par photo", copyRaw) { copyRaw = it; app.sync.copyRaw = it }
        InfoRow("File RAW", if (sync.rawPending == 0) "Vide" else "En cours",
            sub = if (sync.rawPending > 0) "${sync.rawPending} fichiers · ${(sync.rawBytesLeft / 1e6).roundToInt()} Mo" + (sync.etaS?.let { " · ≈ ${SyncService.fmtEta(it)}" } ?: "") else null,
            color = if (sync.rawPending > 0) Halo.Warn else Halo.Dim)
        if (sync.phoneFull) InfoRow("Téléphone plein", "copie arrêtée", Halo.Ir)
        SwitchRow("Effacer de la caméra après copie", null, delAfter) { delAfter = it; app.sync.deleteAfterCopy = it }
    }
    Group("Appareil") {
        ValueRow("Nom de la caméra", c.s.name) {
            c.input("Nom de la caméra", listOf(Triple("Nom", c.s.name, false)), "Enregistrer") { v ->
                if (v[0].isBlank() || v[0].length > 24) "1 à 24 caractères" else { c.cam("name", v[0].trim()); null }
            }
        }
        ValueRow("Wi-Fi de la caméra", wifiName) {
            c.input("Wi-Fi de la caméra", listOf(Triple("Nom du réseau", wifiName, false), Triple("Mot de passe (8 caractères min.)", "", true)), "Enregistrer") { v ->
                val n = v[0].trim(); val p = v[1]
                when {
                    n.isEmpty() || n.length > 32 -> "Nom : 1 à 32 caractères"
                    p.length < 8 || p.length > 63 -> "Mot de passe : 8 à 63 caractères"
                    !online -> "Caméra non connectée"
                    else -> {
                        scope.launch {
                            try {
                                app.link.wifiSet(n, p); app.link.updateWifiCredentials(n, p); wifiName = n
                                c.toast("Appliqué au prochain démarrage de la caméra")
                            } catch (e: Exception) { c.toast("Refusé : ${e.message}") }
                        }
                        null
                    }
                }
            }
        }
        SwitchRow("Caméra montée à l'envers", "Retourne l'image de 180°", c.s.rotation == 180) { c.cam("rotation", if (it) 180 else 0) }
        val bundled = remember { CameraUpdate.bundledVersion(ctx) }
        val needs = ver != null && bundled != null && CameraUpdate.newer(bundled, ver!!)
        ValueRow("Logiciel", "caméra ${ver ?: "—"} · app ${BuildConfig.VERSION_NAME}") {}
        if (needs && online) ActionRow("Mettre à jour la caméra", "Version $bundled incluse dans l'app") {
            c.ask("Mettre à jour la caméra", "La caméra installe la version $bundled puis redémarre son logiciel (≈ 15 s). Les photos ne sont pas touchées.", "Mettre à jour", false) {
                scope.launch {
                    try { val r = app.link.update(CameraUpdate.archive(ctx)); c.toast("Caméra mise à jour : ${r.optString("version")}") }
                    catch (e: Exception) { c.toast("Mise à jour refusée : ${e.message}") }
                }
            }
        }
        ActionRow("Formater la microSD", "Efface toutes les photos de la caméra") {
            c.ask("Formater la microSD", "Toutes les photos de la caméra seront effacées. Les copies déjà sur le téléphone restent.", "Continuer", true) {
                c.ask("Vraiment tout effacer ?", "Cette action est définitive.", "Tout effacer", true) {
                    scope.launch {
                        try { app.link.system("erase_all", JSONObject().put("confirm", "EFFACER")); c.toast("microSD effacée") } catch (e: Exception) { c.toast("Impossible : ${e.message}") }
                    }
                }
            }
        }
        ActionRow("Redémarrer la caméra", null) {
            c.ask("Redémarrer la caméra", "Elle revient d'elle-même en ≈ 40 s.", "Redémarrer", false) { scope.launch { try { app.link.system("reboot") } catch (_: Exception) {} } }
        }
        ActionRow("Éteindre la caméra", null, danger = true) {
            c.ask("Éteindre la caméra", "Attends que la LED s'éteigne avant de débrancher la batterie.", "Éteindre", true) {
                scope.launch { try { app.link.system("shutdown"); c.toast("Extinction en cours") } catch (_: Exception) {} }
            }
        }
    }
    Group("Avancé") {
        ValueRow("Adresse de la caméra", app.link.host) {
            c.input("Adresse de la caméra", listOf(Triple("hôte:port", app.link.host, false)), "Enregistrer") { v ->
                if (!Regex("^[A-Za-z0-9.\\-]+:\\d{2,5}$").matches(v[0].trim())) "Format : 10.42.0.1:8000" else { app.link.setHost(v[0].trim()); null }
            }
        }
    }
}

/** Puissance du signal Wi-Fi de la caméra (dBm), vue par le téléphone. */
private fun wifiSignal(ctx: Context): Int? = try {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
        .firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        ?.signalStrength?.takeIf { it != Int.MIN_VALUE && it < 0 }
} catch (_: Exception) { null }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfilesTab(c: Ctx, list: List<fr.halo720.app.data.Profile>, active: String?, modified: Boolean) {
    val app = LocalContext.current.applicationContext as HaloApp
    Group("Profils enregistrés") {
        list.forEach { p ->
            val on = p.id == active
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = {
                app.link.setProfile(p.cam); app.profiles.setActive(p.id, false); c.toast("Profil ${p.name}")
            }, onLongClick = { profileMenu(c, app, p) }).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Halo.Ir else Color(0x0AFFFFFF)), contentAlignment = Alignment.Center) {
                    Text(p.icon, style = mono(10.sp, FontWeight.Bold))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name + if (on && modified) " •" else "", style = sans(13.sp, FontWeight.Medium))
                    Text(p.summary(), style = mono(9.5.sp, color = Halo.Dim), maxLines = 1)
                }
                Text(if (on) "Actif" else "Charger", style = sans(10.5.sp, FontWeight.SemiBold, if (on) Halo.Ir else Halo.Dim),
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(if (on) Halo.IrSoft else Color(0x0AFFFFFF)).padding(horizontal = 9.dp, vertical = 5.dp))
            }
            Divider()
        }
    }
    Card {
        Box(Modifier.fillMaxWidth().height(40.dp).clickable {
            c.input("Enregistrer les réglages actuels", listOf(Triple("Nom du profil", "", false)), "Enregistrer") { v ->
                if (v[0].isBlank()) "Donne-lui un nom" else {
                    val p = app.profiles.add(v[0].trim(), c.s.profileJson(), null); app.profiles.setActive(p.id); null
                }
            }
        }, contentAlignment = Alignment.Center) { Text("＋ Enregistrer les réglages actuels", style = sans(12.sp, FontWeight.SemiBold, Halo.Dim)) }
    }
    Text("Appui long sur un profil : renommer, réordonner, supprimer.", style = sans(10.5.sp, color = Halo.Faint), modifier = Modifier.padding(8.dp))
}

private fun profileMenu(c: Ctx, app: HaloApp, p: fr.halo720.app.data.Profile) {
    val id = p.id
    c.pick(p.name, listOf("Renommer" to "rename", "Monter" to "up", "Descendre" to "down", "Remplacer par les réglages actuels" to "over", "Supprimer" to "del"), null) { a ->
        when (a) {
            "rename" -> c.input("Renommer", listOf(Triple("Nom", p.name, false)), "Enregistrer") { v -> if (v[0].isBlank()) "Nom vide" else { app.profiles.rename(id, v[0].trim()); null } }
            "up" -> app.profiles.move(id, -1)
            "down" -> app.profiles.move(id, 1)
            "over" -> app.profiles.overwrite(id, c.s.profileJson(), null)
            "del" -> c.ask("Supprimer « ${p.name} » ?", "Le profil est supprimé du téléphone.", "Supprimer", true) { app.profiles.delete(id) }
        }
    }
}

// ==================================================================== briques

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(title.uppercase(), style = label(), modifier = Modifier.padding(start = 4.dp, bottom = 5.dp))
        Card(content)
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Halo.Surface).border(1.dp, Color(0x08FFFFFF), RoundedCornerShape(12.dp)), content = content)
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x08FFFFFF)))

@Composable
private fun RowBase(onClick: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
    Divider()
}

@Composable
private fun RowTitle(t: String, sub: String?, modifier: Modifier, color: Color = Halo.Text) {
    Column(modifier) {
        Text(t, style = sans(13.sp, color = color))
        if (sub != null) Text(sub, style = sans(10.5.sp, color = Halo.Dim))
    }
}

@Composable
private fun ValueRow(t: String, v: String, sub: String? = null, onClick: () -> Unit) = RowBase(onClick) {
    RowTitle(t, sub, Modifier.weight(1f))
    Text(v, style = mono(11.sp, color = Halo.Dim), textAlign = TextAlign.End, maxLines = 1)
    Text("›", style = sans(16.sp, color = Halo.Faint))
}

@Composable
private fun InfoRow(t: String, v: String, color: Color = Halo.Dim, sub: String? = null) = RowBase(null) {
    RowTitle(t, sub, Modifier.weight(1f))
    Text(v, style = mono(11.sp, color = color))
}

@Composable
private fun ActionRow(t: String, sub: String?, danger: Boolean = false, onClick: () -> Unit) = RowBase(onClick) {
    RowTitle(t, sub, Modifier.weight(1f), if (danger) Halo.Ir else Halo.Text)
}

@Composable
private fun BarRow(t: String, frac: Float, v: String, sub: String?) = RowBase(null) {
    Column(Modifier.weight(1f)) {
        Text(t, style = sans(13.sp))
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x12FFFFFF))) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if (frac > 0.95f) Halo.Ir else Halo.Text))
        }
        if (sub != null) Text(sub, style = sans(10.5.sp, color = Halo.Dim), modifier = Modifier.padding(top = 3.dp))
    }
    Text(v, style = mono(11.sp, color = Halo.Dim))
}

@Composable
private fun SwitchRow(t: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) = RowBase({ onChange(!on) }) {
    RowTitle(t, sub, Modifier.weight(1f))
    ThinSwitch(on)
}

@Composable
fun ThinSwitch(on: Boolean) {
    val x by animateFloatAsState(if (on) 14f else 0f, tween(200), label = "sw")
    Box(Modifier.size(34.dp, 20.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Halo.Ir else Color(0xFF2A2A2D))) {
        Box(Modifier.offset(x = (2 + x).dp, y = 2.dp).size(16.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun Minis(opts: List<Pair<String, Any>>, cur: Any, onPick: (Any) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        opts.forEach { (l, v) ->
            val on = v == cur
            Box(Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(7.dp)).background(if (on) Halo.Text else Color(0x0AFFFFFF)).clickable { onPick(v) },
                contentAlignment = Alignment.Center) {
                Text(l, style = sans(11.sp, FontWeight.SemiBold, if (on) Color.Black else Halo.Dim), maxLines = 1)
            }
        }
    }
    Divider()
}

// ==================================================================== fenêtres

@Composable
private fun Scrim(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xA6000000)).pointerInput(Unit) { detectTapGestures { onDismiss() } }, content = content)
}

@Composable
private fun ChoiceSheet(c: Choice, dismiss: () -> Unit) = Scrim(dismiss) {
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Halo.Surface)
        .pointerInput(Unit) { detectTapGestures { } }.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 8.dp)) {
        Text(c.title, style = sans(13.sp, FontWeight.SemiBold), modifier = Modifier.padding(16.dp))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            c.options.forEach { (l, v) ->
                Row(Modifier.fillMaxWidth().clickable { c.onPick(v); dismiss() }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l, style = sans(14.sp, color = if (v == c.current) Halo.Ir else Halo.Text), modifier = Modifier.weight(1f))
                    if (v == c.current) Text("●", style = sans(10.sp, color = Halo.Ir))
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(c: Confirm, dismiss: () -> Unit) = Scrim(dismiss) {
    Column(Modifier.align(Alignment.Center).padding(28.dp).clip(RoundedCornerShape(14.dp)).background(Halo.Surface).pointerInput(Unit) { detectTapGestures { } }.padding(18.dp)) {
        Text(c.title, style = sans(15.sp, FontWeight.SemiBold))
        Spacer(Modifier.height(8.dp))
        Text(c.body, style = sans(12.5.sp, color = Halo.Dim))
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton("Annuler", Color(0x14FFFFFF), Modifier.weight(1f)) { dismiss() }
            DialogButton(c.action, if (c.danger) Halo.Ir else Halo.Text, Modifier.weight(1f), if (c.danger) Halo.Text else Color.Black) { dismiss(); c.onOk() }
        }
    }
}

@Composable
private fun InputDialog(c: Input, dismiss: () -> Unit) = Scrim(dismiss) {
    val values = remember { c.fields.map { mutableStateOf(it.second) } }
    var err by remember { mutableStateOf<String?>(null) }
    Column(Modifier.align(Alignment.Center).padding(28.dp).clip(RoundedCornerShape(14.dp)).background(Halo.Surface).pointerInput(Unit) { detectTapGestures { } }.padding(18.dp).imePadding()) {
        Text(c.title, style = sans(15.sp, FontWeight.SemiBold))
        c.fields.forEachIndexed { i, (lbl, _, secret) ->
            Spacer(Modifier.height(12.dp))
            Text(lbl.uppercase(), style = label())
            Spacer(Modifier.height(4.dp))
            BasicTextField(values[i].value, { values[i].value = it }, singleLine = true, textStyle = sans(14.sp),
                cursorBrush = SolidColor(Halo.Ir),
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x0FFFFFFF)).padding(10.dp))
        }
        err?.let { Spacer(Modifier.height(8.dp)); Text(it, style = sans(11.sp, color = Halo.Ir)) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton("Annuler", Color(0x14FFFFFF), Modifier.weight(1f)) { dismiss() }
            DialogButton(c.action, Halo.Text, Modifier.weight(1f), Color.Black) {
                val e = c.onOk(values.map { it.value })
                if (e == null) dismiss() else err = e
            }
        }
    }
}

@Composable
private fun DialogButton(t: String, bg: Color, modifier: Modifier, fg: Color = Halo.Text, onClick: () -> Unit) {
    Box(modifier.height(38.dp).clip(RoundedCornerShape(9.dp)).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(t, style = sans(13.sp, FontWeight.SemiBold, fg))
    }
}
