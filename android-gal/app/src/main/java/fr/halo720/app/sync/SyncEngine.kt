package fr.halo720.app.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import fr.halo720.app.data.Photo
import fr.halo720.app.data.PhotoInfo
import fr.halo720.app.data.PhotoStore
import fr.halo720.app.net.CameraLink
import fr.halo720.app.net.Link
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

data class SyncState(
    val rawPending: Int = 0,
    val rawBytesLeft: Long = 0,
    val jpgPending: Int = 0,
    val current: String? = null,
    val progress: Float = 0f,
    val speedBps: Double = 0.0,
    val phoneFull: Boolean = false,
    val lastError: String? = null,
) {
    val etaS: Int? get() = if (speedBps > 1000) (rawBytesLeft / speedBps).toInt() else null
    val busy get() = rawPending + jpgPending > 0
}

/**
 * Sauvegarde automatique dans Images/HALO720 :
 *   1. vignette (≈ 30 Ko) tout de suite, 2. JPEG en priorité, 3. RAW en file quand la caméra
 *   ne prend pas de photo. Reprise après coupure (téléchargement partiel conservé), fichier
 *   marqué « copié » seulement après vérification de la taille et de l'empreinte SHA-256.
 */
class SyncEngine(private val ctx: Context, private val link: CameraLink, private val store: PhotoStore) {
    private val tag = "HALO.sync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = ctx.getSharedPreferences("halo", Context.MODE_PRIVATE)
    val state = MutableStateFlow(SyncState())
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var lastShotMs = 0L
    @Volatile private var bytesWindow = ArrayDeque<Pair<Long, Long>>()

    var autoSave: Boolean
        get() = prefs.getBoolean("autosave", true)
        set(v) { prefs.edit().putBoolean("autosave", v).apply(); poke() }
    var deleteAfterCopy: Boolean
        get() = prefs.getBoolean("delete_after_copy", false)
        set(v) { prefs.edit().putBoolean("delete_after_copy", v).apply() }
    var copyRaw: Boolean
        get() = prefs.getBoolean("copy_raw", true)
        set(v) { prefs.edit().putBoolean("copy_raw", v).apply(); poke() }

    fun start() {
        scope.launch { loop() }
        scope.launch {
            link.events.collect { e ->
                when (e.kind) {
                    "shot" -> { lastShotMs = System.currentTimeMillis() }
                    "thumb", "photo", "raw", "deleted" -> poke()
                }
            }
        }
        scope.launch {
            var was = Link.SEARCHING
            link.link.collect { l -> if (l == Link.ONLINE && was != Link.ONLINE) poke(); was = l }
        }
    }

    fun poke() { wake.trySend(Unit) }

    private suspend fun loop() {
        var lastFull = 0L
        while (scope.isActive) {
            withTimeoutOrNull(15000) { wake.receive() }
            if (link.link.value != Link.ONLINE) continue
            try {
                val now = System.currentTimeMillis()
                val full = now - lastFull > 60_000
                val maxSeq = if (full) -1 else store.photos.value.values.maxOfOrNull { it.info.seq } ?: -1
                val list = link.photos(if (full) -1 else maxSeq)
                store.mergeCamera(list, complete = full)
                if (full) lastFull = now
                // les photos déjà connues ont pu changer d'état (RAW terminé) : petite relecture ciblée
                refreshPending()
                work()
            } catch (e: Exception) {
                Log.i(tag, "sync : ${e.message}")
                delay(1500)
            }
        }
    }

    private suspend fun refreshPending() {
        val pend = store.photos.value.values.filter { it.onCamera && (it.info.dngPending || it.info.jpgPending) }
        if (pend.isEmpty()) return
        val minSeq = pend.minOf { it.info.seq } - 1
        store.mergeCamera(link.photos(minSeq), complete = false)
    }

    private fun updateCounts(cur: String? = null, prog: Float = 0f) {
        val ps = store.photos.value.values
        val raw = if (autoSave && copyRaw) ps.filter { it.onCamera && it.dngUri == null && it.info.dngSize != null } else emptyList()
        val jpg = if (autoSave) ps.filter { it.onCamera && it.jpgUri == null && it.info.jpgSize != null } else emptyList()
        state.value = state.value.copy(rawPending = raw.size, rawBytesLeft = raw.sumOf { it.info.dngSize ?: 0 },
            jpgPending = jpg.size, current = cur, progress = prog, speedBps = speed())
        if (state.value.busy && autoSave) SyncService.ensure(ctx) else SyncService.update(ctx, state.value)
    }

    private suspend fun work() {
        // 1. vignettes (toutes, la plus récente d'abord)
        for (p in store.sorted()) {
            if (!p.onCamera || store.thumbFile(p.stem).exists()) continue
            if (link.link.value != Link.ONLINE) return
            try { download("/thumb/${p.stem}.jpg", store.thumbFile(p.stem), null, null) } catch (e: Exception) { Log.i(tag, "vignette ${p.stem}: ${e.message}") }
        }
        if (!autoSave) { updateCounts(); return }
        while (link.link.value == Link.ONLINE) {
            updateCounts()
            val ps = store.sorted()
            // 2. JPEG, le plus récent d'abord
            val j = ps.firstOrNull { it.onCamera && it.jpgUri == null && it.info.jpgSize != null && it.info.jpgSha != null }
            if (j != null) { copyFile(j, "jpg"); continue }
            // 3. RAW, quand la caméra ne prend pas de photo (le Wi-Fi reste libre pour la visée)
            if (!copyRaw) break
            val r = ps.lastOrNull { it.onCamera && it.dngUri == null && it.info.dngSize != null && it.info.dngSha != null }
                ?: break
            val st = link.status.value
            if (st.busy || System.currentTimeMillis() - lastShotMs < 3000) {
                delay(800); continue
            }
            copyFile(r, "dng")
        }
        updateCounts()
    }

    private suspend fun copyFile(p: Photo, kind: String) {
        val info = p.info
        val name = "${p.stem}.$kind"
        val size = if (kind == "jpg") info.jpgSize!! else info.dngSize!!
        val sha = if (kind == "jpg") info.jpgSha!! else info.dngSha!!
        val part = File(store.partialDir, name)
        try {
            download("/photos/$name", part, size) { done -> updateCounts(name, done.toFloat() / size) }
            if (part.length() != size || PhotoStore.sha256(part) != sha) {
                part.delete()
                throw IOException("vérification échouée pour $name")
            }
            val uri = try {
                store.publish(part, name, if (kind == "jpg") "image/jpeg" else "image/x-adobe-dng", (info.time * 1000).toLong())
            } catch (e: Exception) {
                if ((e.message ?: "").contains("ENOSPC") || (e.message ?: "").contains("No space")) {
                    state.value = state.value.copy(phoneFull = true)
                }
                throw e
            }
            part.delete()
            store.update(p.stem) { it?.let { if (kind == "jpg") it.copy(jpgUri = uri.toString()) else it.copy(dngUri = uri.toString()) } }
            state.value = state.value.copy(phoneFull = false, lastError = null)
            maybeDeleteFromCamera(p.stem)
        } catch (e: Exception) {
            Log.w(tag, "copie $name : ${e.message}")
            state.value = state.value.copy(lastError = e.message)
            delay(1000)
        }
    }

    private suspend fun maybeDeleteFromCamera(stem: String) {
        if (!deleteAfterCopy) return
        val p = store.photos.value[stem] ?: return
        val jpgOk = p.jpgUri != null || p.info.jpgSize == null
        val dngOk = p.dngUri != null || p.info.dngSize == null
        if (jpgOk && dngOk && !p.info.dngPending) {
            try {
                link.deleteOnCamera(listOf(stem))
                store.update(stem) { it?.copy(onCamera = false) }
            } catch (_: Exception) {}
        }
    }

    /** Téléchargement avec reprise (Range). */
    private fun download(path: String, dest: File, size: Long?, progress: ((Long) -> Unit)?) {
        val have = if (dest.exists() && size != null) dest.length() else 0L
        if (size != null && have == size) return
        val rb = Request.Builder().url(link.url(path))
        if (have > 0) rb.header("Range", "bytes=$have-")
        link.http.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val append = r.code == 206 && have > 0
            val tmp = if (size == null) File(dest.path + ".tmp") else dest
            RandomAccessFile(tmp, "rw").use { raf ->
                if (!append) raf.setLength(0)
                raf.seek(if (append) have else 0)
                val src = r.body!!.byteStream()
                val buf = ByteArray(1 shl 16)
                var done = if (append) have else 0L
                var lastReport = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    done += n
                    noteBytes(n.toLong())
                    if (progress != null && done - lastReport > 256 * 1024) { lastReport = done; progress(done) }
                }
            }
            if (size == null) tmp.renameTo(dest)
        }
    }

    private fun noteBytes(n: Long) {
        val now = System.currentTimeMillis()
        synchronized(bytesWindow) {
            bytesWindow.addLast(now to n)
            while (bytesWindow.isNotEmpty() && now - bytesWindow.first().first > 4000) bytesWindow.removeFirst()
        }
    }

    private fun speed(): Double = synchronized(bytesWindow) {
        if (bytesWindow.size < 2) return 0.0
        val span = (bytesWindow.last().first - bytesWindow.first().first).coerceAtLeast(500)
        bytesWindow.sumOf { it.second } * 1000.0 / span
    }

    /** Pour la galerie : télécharge un fichier à la demande (aperçu plein écran, RAW pour un look). */
    suspend fun fetchToCache(stem: String, kind: String, progress: ((Float) -> Unit)? = null): File {
        val p = store.photos.value[stem]
        val f = File(store.viewDir, "$stem.$kind")
        val size = if (kind == "jpg") p?.info?.jpgSize else p?.info?.dngSize
        if (f.exists() && size != null && f.length() == size) return f
        download("/photos/$stem.$kind", f, size) { d -> if (size != null) progress?.invoke(d.toFloat() / size) }
        return f
    }

    fun intentForService() = Intent(ctx, SyncService::class.java)
}
