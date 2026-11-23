package fr.halo720.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import fr.halo720.app.data.CamSettings
import fr.halo720.app.data.CamStatus
import fr.halo720.app.data.Looks
import fr.halo720.app.data.PhotoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class Link { SEARCHING, CONNECTING, ONLINE, LOST }

/** Événement ponctuel venu de la caméra (flux SSE). */
data class CamEvent(val kind: String, val data: JSONObject)

/**
 * Lien avec la caméra : réseau Wi-Fi HALO-720 (demandé à Android, sans passer par les réglages),
 * client HTTP lié à ce réseau, canal temps réel (SSE) et commandes.
 * Les données mobiles restent le réseau par défaut du téléphone pour les autres apps.
 */
class CameraLink(private val ctx: Context) {
    private val tag = "HALO.link"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val prefs = ctx.getSharedPreferences("halo", Context.MODE_PRIVATE)

    val host: String get() = prefs.getString("host", "10.42.0.1:8000")!!
    val ssid: String get() = prefs.getString("ssid", "HALO-720")!!
    private val pass: String get() = prefs.getString("wifi_pass", "halo7200")!!

    @Volatile private var network: Network? = null
    @Volatile var http: OkHttpClient = buildClient(null); private set
    @Volatile private var streamHttp: OkHttpClient = buildStreamClient(null)
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    val link = MutableStateFlow(Link.SEARCHING)
    val settings = MutableStateFlow(CamSettings())
    val status = MutableStateFlow(CamStatus())
    val version = MutableStateFlow<String?>(null)
    private val _events = MutableSharedFlow<CamEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<CamEvent> = _events
    /** Dernière fois que la caméra a parlé (ms) : sert à détecter une coupure. */
    val lastSeen = MutableStateFlow(0L)
    val wifiRequested = MutableStateFlow(false)

    private var loop: Job? = null
    private var sseCall: Call? = null
    @Volatile private var pendingLocal = HashMap<String, Long>()

    fun base() = "http://$host"

    private fun buildClient(net: Network?) = OkHttpClient.Builder()
        .apply { net?.let { socketFactory(it.socketFactory) } }
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    private fun buildStreamClient(net: Network?) = OkHttpClient.Builder()
        .apply { net?.let { socketFactory(it.socketFactory) } }
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .build()

    fun streamClient() = streamHttp

    /** Démarre la boucle de connexion (appel idempotent). */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    /** Demande à Android de rejoindre HALO-720 : une fenêtre système, un toucher. */
    fun requestWifi() {
        if (netCallback != null) {
            try { cm.unregisterNetworkCallback(netCallback!!) } catch (_: Exception) {}
            netCallback = null
        }
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(pass).build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                Log.i(tag, "réseau HALO-720 disponible")
                useNetwork(n)
            }
            override fun onLost(n: Network) {
                if (network == n) useNetwork(null)
            }
            override fun onUnavailable() {
                Log.i(tag, "réseau HALO-720 refusé ou introuvable")
                netCallback = null
                wifiRequested.value = false
            }
        }
        netCallback = cb
        wifiRequested.value = true
        try {
            cm.requestNetwork(req, cb)
        } catch (e: Exception) {
            Log.w(tag, "requestNetwork", e)
            netCallback = null
            wifiRequested.value = false
        }
    }

    fun updateWifiCredentials(newSsid: String, newPass: String) {
        prefs.edit().putString("ssid", newSsid).putString("wifi_pass", newPass).apply()
    }

    fun setHost(h: String) {
        prefs.edit().putString("host", h).apply()
        sseCall?.cancel()
    }

    private fun useNetwork(n: Network?) {
        network = n
        http = buildClient(n)
        streamHttp = buildStreamClient(n)
        sseCall?.cancel()
    }

    /**
     * Wi-Fi rejoint à la main (réglages Android) : HALO-720 n'a pas Internet, donc Android garde les
     * données mobiles comme réseau par défaut dès qu'une app en a besoin (musique en streaming…).
     * Un client HTTP non lié partait alors en 4G vers 10.42.0.1 et ne trouvait plus la caméra.
     * On lie donc toujours les connexions au réseau Wi-Fi présent, même quand ce n'est pas le défaut.
     */
    @Suppress("DEPRECATION")
    private fun currentWifi(): Network? = try {
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    } catch (_: Exception) { null }

    /** Sans demande HALO-720 en cours, suit le Wi-Fi courant du téléphone. */
    private fun followManualWifi() {
        if (netCallback != null) return          // réseau demandé par l'app : géré par son callback
        val w = currentWifi()
        if (w != network) {
            Log.i(tag, if (w != null) "liaison au Wi-Fi courant" else "plus de Wi-Fi : réseau par défaut")
            useNetwork(w)
        }
    }

    /** Le Wi-Fi courant du téléphone est-il HALO-720 (connecté à la main) ? */
    private fun isOnCameraWifiByDefault(): Boolean = try {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Exception) { false }

    private suspend fun runLoop() {
        var autoAsked = prefs.getBoolean("auto_wifi", true).not()
        var fails = 0
        while (scope.isActive) {
            followManualWifi()
            val ok = try { fetchInfo(); true } catch (e: Exception) { false }
            if (ok) {
                fails = 0
                link.value = Link.ONLINE
                try {
                    runSse()      // bloque tant que le canal tient
                } catch (e: Exception) {
                    Log.i(tag, "canal coupé : ${e.message}")
                }
                link.value = Link.LOST
                delay(500)
                continue
            }
            fails++
            if (link.value == Link.ONLINE) link.value = Link.LOST
            else if (link.value != Link.LOST || fails > 5) link.value = if (wifiRequested.value) Link.CONNECTING else Link.SEARCHING
            // Pas de caméra sur le réseau courant : on demande HALO-720 à Android (une fois, ensuite au bouton)
            if (!autoAsked && netCallback == null) {
                autoAsked = true
                withContext(Dispatchers.Main) { requestWifi() }
            }
            delay(2000)   // spec : nouvel essai toutes les 2 s
        }
    }

    private fun fetchInfo() {
        val req = Request.Builder().url("${base()}/api/info").build()
        http.newBuilder().callTimeout(5000, TimeUnit.MILLISECONDS).build().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val o = JSONObject(r.body!!.string())
            Looks.load(o)
            version.value = o.optString("version")
        }
        val st = getJson("/api/state")
        settings.value = CamSettings.from(st.getJSONObject("settings"))
        status.value = CamStatus.from(st.getJSONObject("status"))
        lastSeen.value = System.currentTimeMillis()
    }

    private fun runSse() {
        val req = Request.Builder().url("${base()}/api/events").header("Accept", "text/event-stream").build()
        val call = http.newBuilder().readTimeout(7, TimeUnit.SECONDS).build().newCall(req)
        sseCall = call
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("SSE ${resp.code}")
            val src = resp.body!!.source()
            var event = "message"
            val data = StringBuilder()
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (line.isEmpty()) {
                    if (data.isNotEmpty()) dispatch(event, data.toString())
                    event = "message"; data.setLength(0)
                    continue
                }
                when {
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> data.append(line.substring(5).trim())
                }
            }
        }
    }

    private fun dispatch(event: String, data: String) {
        lastSeen.value = System.currentTimeMillis()
        val o = try { JSONObject(data) } catch (_: Exception) { return }
        if (event == "state") {
            status.value = CamStatus.from(o.getJSONObject("status"))
            val s = CamSettings.from(o.getJSONObject("settings"))
            // un réglage que l'on vient de toucher n'est pas écrasé par un état plus ancien
            val now = System.currentTimeMillis()
            if (pendingLocal.values.none { now - it < 700 }) settings.value = s
        } else {
            _events.tryEmit(CamEvent(event, o))
        }
    }

    // ------------------------------------------------------------------ commandes

    private val JSON = "application/json".toMediaType()

    fun getJson(path: String): JSONObject {
        val req = Request.Builder().url(base() + path).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return JSONObject(r.body!!.string())
        }
    }

    fun postJson(path: String, body: JSONObject, timeoutMs: Long = 4000): JSONObject {
        val req = Request.Builder().url(base() + path).post(body.toString().toRequestBody(JSON)).build()
        http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: "{}"
            val o = try { JSONObject(txt) } catch (_: Exception) { JSONObject() }
            if (!r.isSuccessful) throw CamError(o.optString("error", "HTTP ${r.code}"))
            return o
        }
    }

    /** Change un ou plusieurs réglages : affichage immédiat, envoi en arrière-plan. */
    fun set(vararg kv: Pair<String, Any?>) {
        var s = settings.value
        val body = JSONObject()
        val now = System.currentTimeMillis()
        for ((k, v) in kv) { s = s.with(k, v); body.put(k, v ?: JSONObject.NULL); pendingLocal[k] = now }
        settings.value = s
        scope.launch {
            try {
                val r = postJson("/api/settings", body)
                r.optJSONObject("settings")?.let { settings.value = CamSettings.from(it) }
            } catch (e: Exception) {
                Log.w(tag, "réglage refusé : ${e.message}")
            }
        }
    }

    fun setProfile(p: JSONObject) {
        val keys = p.keys().asSequence().toList()
        set(*keys.map { it to p.get(it) }.toTypedArray())
    }

    fun shoot(orientation: Int, action: String = "press") = scope.launch {
        try { postJson("/api/shoot", JSONObject().put("orientation", orientation).put("action", action), 3000) }
        catch (e: Exception) { _events.tryEmit(CamEvent("error", JSONObject().put("message", e.message ?: "déclenchement impossible"))) }
    }

    fun stop() = scope.launch { try { postJson("/api/stop", JSONObject()) } catch (_: Exception) {} }

    fun focus(x: Double, y: Double, lock: Boolean) = scope.launch {
        try { postJson("/api/focus", JSONObject().put("x", x).put("y", y).put("lock", lock)) } catch (_: Exception) {}
    }

    fun focusReset() = scope.launch { try { postJson("/api/focus", JSONObject().put("reset", true)) } catch (_: Exception) {} }

    fun loupe(on: Boolean) = scope.launch { try { postJson("/api/loupe", JSONObject().put("on", on)) } catch (_: Exception) {} }

    suspend fun calibrateWb(): List<Double> = withContext(Dispatchers.IO) {
        val r = postJson("/api/wb/calibrate", JSONObject(), 15000)
        val a = r.getJSONArray("gains"); listOf(a.getDouble(0), a.getDouble(1))
    }

    suspend fun photos(since: Int = -1): List<PhotoInfo> = withContext(Dispatchers.IO) {
        val o = getJson("/api/photos?since=$since")
        val a = o.getJSONArray("items")
        List(a.length()) { PhotoInfo.from(a.getJSONObject(it)) }
    }

    suspend fun deleteOnCamera(stems: List<String>) = withContext(Dispatchers.IO) {
        postJson("/api/photos/delete", JSONObject().put("stems", JSONArray(stems)), 15000)
    }

    suspend fun system(action: String, extra: JSONObject = JSONObject()) = withContext(Dispatchers.IO) {
        postJson("/api/system/$action", extra, 30000)
    }

    suspend fun wifiGet(): JSONObject = withContext(Dispatchers.IO) { getJson("/api/wifi") }

    suspend fun wifiSet(ssid: String, pass: String): JSONObject = withContext(Dispatchers.IO) {
        postJson("/api/wifi", JSONObject().put("ssid", ssid).put("password", pass), 20000)
    }

    suspend fun update(archive: ByteArray): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${base()}/api/update")
            .post(archive.toRequestBody("application/gzip".toMediaType())).build()
        http.newBuilder().callTimeout(60, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            val o = try { JSONObject(r.body?.string() ?: "{}") } catch (_: Exception) { JSONObject() }
            if (!r.isSuccessful) throw CamError(o.optString("error", "HTTP ${r.code}"))
            o
        }
    }

    fun url(path: String) = base() + path
}

class CamError(msg: String) : IOException(msg)
