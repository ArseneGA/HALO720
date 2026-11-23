package fr.halo720.app.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Réglages de la caméra (miroir de DEFAULT_SETTINGS côté Pi). */
data class CamSettings(
    val iso: Int = 0,
    val shutterUs: Int = 0,
    val ev: Double = 0.0,
    val look: String = "bleu",
    val lookInJpeg: Boolean = true,
    val format: String = "jpg+raw",
    val jpegQuality: Int = 92,
    val metering: String = "matrix",
    val isoMax: Int = 1600,
    val slowestAutoUs: Int = 66667,
    val awb: String = "ir",
    val awbGains: List<Double>? = null,
    val af: String = "continuous",
    val lensPosition: Double = 1.0,
    val afZone: String = "point",
    val nr: String = "light",
    val sharpness: Int = 0,
    val led: Boolean = true,
    val flash: String = "off",          // off | on | torche (flash IR 850 nm, boîtier v2)
    val flashAe: Boolean = true,
    val timerS: Int = 0,
    val mode: String = "photo",
    val poseS: Int = 4,
    val rafaleFps: Int = 0,
    val rafaleLimit: Int = 100,
    val rafaleRaw: Boolean = false,
    val intervS: Int = 10,
    val intervCount: Int = 300,
    val intervDelayS: Int = 0,
    val bracketN: Int = 3,
    val bracketStep: Double = 1.0,
    val bracketOrder: String = "0-+",
    val zoom: Double = 1.0,
    val rotation: Int = 0,
    val name: String = "HALO 720",
) {
    companion object {
        fun from(o: JSONObject) = CamSettings(
            iso = o.optInt("iso", 0), shutterUs = o.optInt("shutter_us", 0), ev = o.optDouble("ev", 0.0),
            look = o.optString("look", "bleu"), lookInJpeg = o.optBoolean("look_in_jpeg", true),
            format = o.optString("format", "jpg+raw"), jpegQuality = o.optInt("jpeg_quality", 92),
            metering = o.optString("metering", "matrix"), isoMax = o.optInt("iso_max", 1600),
            slowestAutoUs = o.optInt("slowest_auto_us", 66667), awb = o.optString("awb", "ir"),
            awbGains = o.optJSONArray("awb_gains")?.let { a -> List(a.length()) { a.getDouble(it) } },
            af = o.optString("af", "continuous"), lensPosition = o.optDouble("lens_position", 1.0),
            afZone = o.optString("af_zone", "point"), nr = o.optString("nr", "light"),
            sharpness = o.optInt("sharpness", 0), led = o.optBoolean("led", true),
            flash = o.optString("flash", "off"), flashAe = o.optBoolean("flash_ae", true), timerS = o.optInt("timer_s", 0),
            mode = o.optString("mode", "photo"), poseS = o.optInt("pose_s", 4), rafaleFps = o.optInt("rafale_fps", 0),
            rafaleLimit = o.optInt("rafale_limit", 100), rafaleRaw = o.optBoolean("rafale_raw", false),
            intervS = o.optInt("interv_s", 10), intervCount = o.optInt("interv_count", 300),
            intervDelayS = o.optInt("interv_delay_s", 0), bracketN = o.optInt("bracket_n", 3),
            bracketStep = o.optDouble("bracket_step", 1.0), bracketOrder = o.optString("bracket_order", "0-+"),
            zoom = o.optDouble("zoom", 1.0), rotation = o.optInt("rotation", 0), name = o.optString("name", "HALO 720"),
        )
    }

    /** Applique localement un changement (clé de l'API -> valeur), pour un affichage immédiat. */
    fun with(key: String, v: Any?): CamSettings = when (key) {
        "iso" -> copy(iso = (v as Number).toInt())
        "shutter_us" -> copy(shutterUs = (v as Number).toInt())
        "ev" -> copy(ev = (v as Number).toDouble())
        "look" -> copy(look = v as String)
        "look_in_jpeg" -> copy(lookInJpeg = v as Boolean)
        "format" -> copy(format = v as String)
        "jpeg_quality" -> copy(jpegQuality = (v as Number).toInt())
        "metering" -> copy(metering = v as String)
        "iso_max" -> copy(isoMax = (v as Number).toInt())
        "slowest_auto_us" -> copy(slowestAutoUs = (v as Number).toInt())
        "awb" -> copy(awb = v as String)
        "af" -> copy(af = v as String)
        "lens_position" -> copy(lensPosition = (v as Number).toDouble())
        "af_zone" -> copy(afZone = v as String)
        "nr" -> copy(nr = v as String)
        "sharpness" -> copy(sharpness = (v as Number).toInt())
        "led" -> copy(led = v as Boolean)
        "flash" -> copy(flash = v as String)
        "flash_ae" -> copy(flashAe = v as Boolean)
        "timer_s" -> copy(timerS = (v as Number).toInt())
        "mode" -> copy(mode = v as String)
        "pose_s" -> copy(poseS = (v as Number).toInt())
        "rafale_fps" -> copy(rafaleFps = (v as Number).toInt())
        "rafale_limit" -> copy(rafaleLimit = (v as Number).toInt())
        "rafale_raw" -> copy(rafaleRaw = v as Boolean)
        "interv_s" -> copy(intervS = (v as Number).toInt())
        "interv_count" -> copy(intervCount = (v as Number).toInt())
        "interv_delay_s" -> copy(intervDelayS = (v as Number).toInt())
        "bracket_n" -> copy(bracketN = (v as Number).toInt())
        "bracket_step" -> copy(bracketStep = (v as Number).toDouble())
        "bracket_order" -> copy(bracketOrder = v as String)
        "zoom" -> copy(zoom = (v as Number).toDouble())
        "rotation" -> copy(rotation = (v as Number).toInt())
        "name" -> copy(name = v as String)
        else -> this
    }

    /** Réglages qui entrent dans un profil (tout sauf l'identité de la caméra). */
    fun profileJson(): JSONObject = JSONObject().apply {
        put("iso", iso); put("shutter_us", shutterUs); put("ev", ev); put("look", look); put("format", format)
        put("timer_s", timerS); put("mode", mode); put("pose_s", poseS); put("rafale_fps", rafaleFps)
        put("rafale_limit", rafaleLimit); put("interv_s", intervS); put("interv_count", intervCount)
        put("interv_delay_s", intervDelayS); put("bracket_n", bracketN); put("bracket_step", bracketStep)
        put("bracket_order", bracketOrder); put("zoom", zoom); put("metering", metering); put("af", af)
    }
}

data class Series(val id: String, val kind: String, val done: Int, val total: Int, val nextAt: Double?,
                  val endAt: Double?, val started: Double)

data class CamStatus(
    val phase: String = "idle",
    val timerLeft: Double? = null,
    val exposureStart: Double? = null,
    val exposureEnd: Double? = null,
    val series: Series? = null,
    val error: String? = null,
    val errorAt: Double? = null,
    val last: String? = null,
    val exposureUs: Double? = null,
    val isoNow: Int? = null,
    val lens: Double? = null,
    val fps: Double? = null,
    val af: String = "idle",
    val focusX: Double = 0.5,
    val focusY: Double = 0.5,
    val focusLocked: Boolean = false,
    val focusCustom: Boolean = false,
    val tempC: Double? = null,
    val hot: Boolean = false,
    val diskFree: Long? = null,
    val diskTotal: Long? = null,
    val photosLeft: Int? = null,
    val sdFull: Boolean = false,
    val undervoltageNow: Boolean = false,
    val undervoltageSeen: Boolean = false,
    val powerWarnings: List<String> = emptyList(),
    val queueJpg: Int = 0,
    val queueRaw: Int = 0,
    val now: Double = 0.0,
    val uptime: Int = 0,
    val flashOn: Boolean = false,
    val torchLeft: Double? = null,
    val flashCooldown: Double? = null,
) {
    val busy get() = phase != "idle" || series != null

    companion object {
        fun from(o: JSONObject): CamStatus {
            val f = o.optJSONObject("focus") ?: JSONObject()
            val q = o.optJSONObject("queue") ?: JSONObject()
            val s = o.optJSONObject("series")
            val fl = o.optJSONObject("flash") ?: JSONObject()
            return CamStatus(
                phase = o.optString("phase", "idle"), timerLeft = o.optDoubleOrNull("timer_left"),
                exposureStart = o.optDoubleOrNull("exposure_start"), exposureEnd = o.optDoubleOrNull("exposure_end"),
                series = s?.let {
                    Series(it.optString("id"), it.optString("kind"), it.optInt("done"), it.optInt("total"),
                        it.optDoubleOrNull("next_at"), it.optDoubleOrNull("end_at"), it.optDouble("started", 0.0))
                },
                error = o.optStringOrNull("error"), errorAt = o.optDoubleOrNull("error_at"), last = o.optStringOrNull("last"),
                exposureUs = o.optDoubleOrNull("exposure_us"), isoNow = o.optIntOrNull("iso_now"),
                lens = o.optDoubleOrNull("lens"), fps = o.optDoubleOrNull("fps"), af = o.optString("af", "idle"),
                focusX = f.optDouble("x", 0.5), focusY = f.optDouble("y", 0.5), focusLocked = f.optBoolean("locked"),
                focusCustom = f.optBoolean("custom"), tempC = o.optDoubleOrNull("temp_c"), hot = o.optBoolean("hot"),
                diskFree = o.optLongOrNull("disk_free"), diskTotal = o.optLongOrNull("disk_total"),
                photosLeft = o.optIntOrNull("photos_left"), sdFull = o.optBoolean("sd_full"),
                undervoltageNow = o.optBoolean("undervoltage_now"), undervoltageSeen = o.optBoolean("undervoltage_seen"),
                powerWarnings = o.optJSONArray("power_warnings")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                queueJpg = q.optInt("jpg"), queueRaw = q.optInt("raw"), now = o.optDouble("now", 0.0),
                uptime = o.optInt("uptime"),
                flashOn = fl.optBoolean("on"), torchLeft = fl.optDoubleOrNull("torch_left"),
                flashCooldown = fl.optDoubleOrNull("cooldown"),
            )
        }
    }
}

/** Une photo telle que la caméra la décrit (/api/photos). */
data class PhotoInfo(
    val stem: String,
    val seq: Int,
    val time: Double,
    val look: String,
    val orientation: Int,
    val zoom: Double,
    val series: String?,
    val mode: String,
    val exposureUs: Double?,
    val iso: Int?,
    val w: Int,
    val h: Int,
    val jpgSize: Long?,
    val jpgSha: String?,
    val dngSize: Long?,
    val dngSha: String?,
    val dngPending: Boolean,
    val jpgPending: Boolean,
    val previewOnly: Boolean,
    val camera: JSONObject?,
    val raw: JSONObject,
) {
    companion object {
        fun from(o: JSONObject): PhotoInfo {
            val j = o.opt("jpg")
            val d = o.opt("dng")
            val series = o.opt("series")
            return PhotoInfo(
                stem = o.getString("stem"), seq = o.optInt("seq"), time = o.optDouble("time", 0.0),
                look = o.optString("look", "brut"), orientation = o.optInt("orientation", 0),
                zoom = o.optDouble("zoom", 1.0),
                series = if (series is String) series else null,
                mode = o.optString("mode", "photo"), exposureUs = o.optDoubleOrNull("exposure_us"),
                iso = o.optIntOrNull("iso"), w = o.optInt("w", 4608), h = o.optInt("h", 2592),
                jpgSize = (j as? JSONObject)?.optLong("size"), jpgSha = (j as? JSONObject)?.optStringOrNull("sha256"),
                dngSize = (d as? JSONObject)?.optLong("size"), dngSha = (d as? JSONObject)?.optStringOrNull("sha256"),
                dngPending = d == "pending", jpgPending = j == "pending",
                previewOnly = o.optBoolean("preview_only"), camera = o.optJSONObject("camera"), raw = o,
            )
        }
    }

    val hasDng get() = dngSize != null || dngPending
}

data class LookDef(val key: String, val name: String, val m: FloatArray, val sat: Float, val contrast: Float)

/** Définition des looks : publiée par la caméra (/api/info) ; valeurs par défaut pour le hors-ligne. */
object Looks {
    val order = listOf("bleu", "brut", "nb", "rose", "dore")
    val names = mapOf("bleu" to "Bleu IR", "brut" to "Brut 720", "nb" to "N&B", "rose" to "Rose", "dore" to "Doré")
    val badge = mapOf("bleu" to "BLEU", "brut" to "BRUT", "nb" to "N&B", "rose" to "ROSE", "dore" to "DORÉ")
    @Volatile var defs: Map<String, LookDef> = mapOf(
        "brut" to LookDef("brut", "Brut 720", floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), 1f, 1f),
        "bleu" to LookDef("bleu", "Bleu IR", floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, 1f, 0f, 0f), 1.25f, 1.05f),
        "nb" to LookDef("nb", "N&B", floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), 0f, 1.35f),
        "rose" to LookDef("rose", "Rose", floatArrayOf(1.25f, 0f, 0f, 0f, 0.78f, 0f, 0f, 0.05f, 1.02f), 1.3f, 1.05f),
        "dore" to LookDef("dore", "Doré", floatArrayOf(0f, 0.1f, 1.15f, 0f, 1f, 0f, 0.82f, 0f, 0f), 1.35f, 1.1f),
    )
    @Volatile var baseCcm: FloatArray = floatArrayOf(1.4086f, -0.348f, -0.0606f, -0.3225f, 1.5717f, -0.2492f, -0.0117f, -0.6308f, 1.6425f)

    fun next(cur: String) = order[(order.indexOf(cur).coerceAtLeast(0) + 1) % order.size]

    fun load(info: JSONObject) {
        val l = info.optJSONObject("looks") ?: return
        val out = HashMap<String, LookDef>()
        for (k in l.keys()) {
            val o = l.getJSONObject(k)
            val m = o.getJSONArray("m")
            out[k] = LookDef(k, names[k] ?: k, FloatArray(9) { m.getDouble(it).toFloat() },
                o.optDouble("sat", 1.0).toFloat(), o.optDouble("contrast", 1.0).toFloat())
        }
        if (out.isNotEmpty()) defs = out
        info.optJSONArray("base_ccm")?.let { a -> if (a.length() == 9) baseCcm = FloatArray(9) { a.getDouble(it).toFloat() } }
    }
}

/** Plages des molettes (spec : tiers de diaph). */
object Steps {
    val iso = listOf(0, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600)
    val shutter = listOf(0, 125, 156, 200, 250, 312, 400, 500, 625, 800, 1000, 1250, 1563, 2000, 2500, 3125,
        4000, 5000, 6250, 8000, 10000, 12500, 16667, 20000, 25000, 33333, 40000, 50000, 66667, 76923, 100000,
        125000, 166667, 200000, 250000, 300000, 400000, 500000, 600000, 800000, 1000000, 2000000, 4000000,
        8000000, 15000000, 30000000)
    val ev = (-9..9).map { it / 3.0 }
    val pose = listOf(1, 2, 4, 8, 15, 30, 60, 0)

    fun shutterLabel(us: Int): String = when {
        us <= 0 -> "AUTO"
        us >= 1_000_000 -> "${(us / 1_000_000.0).let { if (abs(it - it.roundToInt()) < 0.05) it.roundToInt().toString() else fr1(it) }}\""
        us >= 280_000 -> fr1(us / 1_000_000.0) + "\""
        else -> "1/${(1_000_000.0 / us).roundToInt().let { niceDen(it) }}"
    }

    private val dens = listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320,
        250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4)
    private fun niceDen(d: Int) = dens.minByOrNull { abs(it - d) } ?: d

    fun shutterFromMeta(us: Double?): String {
        if (us == null || us <= 0) return "—"
        return shutterLabel(Steps.shutter.drop(1).minByOrNull { abs(it - us) } ?: us.toInt())
    }

    fun evLabel(ev: Double): String {
        val t = (ev * 3).roundToInt()
        if (t == 0) return "0"
        val s = if (t < 0) "−" else "+"
        val a = abs(t)
        val whole = a / 3
        val frac = a % 3
        val txt = when (frac) { 0 -> "$whole"; 1 -> "$whole,3"; else -> "$whole,7" }
        return s + txt
    }

    fun isoFromMeta(iso: Int?): String {
        if (iso == null) return "—"
        return Steps.iso.drop(1).minByOrNull { abs(it - iso) }.toString()
    }

    fun poseLabel(s: Int) = if (s == 0) "B" else if (s >= 60) "${s / 60} min" else "$s s"
}

fun fr1(x: Double): String = String.format(java.util.Locale.FRANCE, "%.1f", x)
fun fr2(x: Double): String = String.format(java.util.Locale.FRANCE, "%.2f", x)

fun JSONObject.optDoubleOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }
fun JSONObject.optIntOrNull(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
fun JSONObject.optLongOrNull(k: String): Long? = if (isNull(k) || !has(k)) null else optLong(k)
fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
fun jsonOf(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
