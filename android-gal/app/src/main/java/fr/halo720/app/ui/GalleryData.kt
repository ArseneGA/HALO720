package fr.halo720.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import fr.halo720.app.HaloApp
import fr.halo720.app.data.Looks
import fr.halo720.app.data.Photo
import fr.halo720.app.data.PhotoStore
import fr.halo720.app.image.DngDeveloper
import fr.halo720.app.image.LookMath
import fr.halo720.app.net.Link
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/* Données et images de la galerie : sources, décodage, versions, partage, suppression. */

/** Où lire l'image affichée d'une photo. */
internal class ImgSource(
    val key: String,
    val uri: String?,
    val file: File?,
    /** Look cuit dans ces pixels. */
    val look: String,
    val isOriginal: Boolean,
    /** Rotation à appliquer aux pixels décodés (EXIF), en degrés. */
    val rotation: Int,
) {
    fun open(ctx: Context): InputStream? = try {
        if (file != null) FileInputStream(file) else uri?.let { ctx.contentResolver.openInputStream(Uri.parse(it)) }
    } catch (_: Exception) { null }
}

/** Métadonnées des versions créées sur le téléphone (uri -> look, retouche). Fichier propre à la galerie. */
internal class VersionIndex(ctx: Context) {
    private val file = File(ctx.filesDir, "gallery_versions.json")
    private val map: JSONObject = try { if (file.exists()) JSONObject(file.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }

    @Synchronized fun put(uri: String, look: String, edit: Boolean) {
        map.put(uri, JSONObject().put("look", look).put("edit", edit).put("t", System.currentTimeMillis()))
        try { file.writeText(map.toString()) } catch (_: Exception) {}
    }

    @Synchronized fun look(uri: String): String? = map.optJSONObject(uri)?.optString("look")
    @Synchronized fun isEdit(uri: String): Boolean = map.optJSONObject(uri)?.optBoolean("edit") ?: false

    companion object {
        @Volatile private var inst: VersionIndex? = null
        fun get(ctx: Context): VersionIndex = inst ?: synchronized(this) { inst ?: VersionIndex(ctx.applicationContext).also { inst = it } }
    }
}

internal object GalleryImages {
    /** Vignettes de la grille (versions ou JPEG sans vignette caméra). */
    val tiles = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Images de base du visualiseur (taille écran). */
    val views = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Vignettes de looks (petites) : clé source + look. */
    val looks = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun app(ctx: Context) = ctx.applicationContext as HaloApp

    /** Version affichée (look choisi) si elle existe encore, sinon null. */
    fun shownVersion(ctx: Context, p: Photo): String? {
        val want = p.lookShown ?: return null
        val idx = VersionIndex.get(ctx)
        return p.versions.lastOrNull { idx.look(it) == want }
    }

    /** Source de l'image affichée. Bloquant (E/S) : appeler sur Dispatchers.IO. */
    suspend fun resolve(ctx: Context, p: Photo, allowNetwork: Boolean, progress: ((Float) -> Unit)? = null): ImgSource? = withContext(Dispatchers.IO) { resolve0(ctx, p, allowNetwork, progress) }

    private suspend fun resolve0(ctx: Context, p: Photo, allowNetwork: Boolean, progress: ((Float) -> Unit)?): ImgSource? {
        val a = app(ctx)
        shownVersion(ctx, p)?.let { u ->
            if (a.store.exists(u)) {
                val look = VersionIndex.get(ctx).look(u) ?: p.info.look
                return ImgSource(u, u, null, look, false, exifRotation { a.store.open(u) } ?: 0)
            }
        }
        return original0(ctx, p, allowNetwork, progress)
    }

    /** JPEG d'origine (look cuit = info.look). Télécharge depuis la caméra si permis. */
    suspend fun original(ctx: Context, p: Photo, allowNetwork: Boolean, progress: ((Float) -> Unit)? = null): ImgSource? = withContext(Dispatchers.IO) { original0(ctx, p, allowNetwork, progress) }

    private suspend fun original0(ctx: Context, p: Photo, allowNetwork: Boolean, progress: ((Float) -> Unit)?): ImgSource? {
        val a = app(ctx)
        val rotFallback = p.info.orientation
        p.jpgUri?.let { u ->
            if (a.store.exists(u)) return ImgSource(u, u, null, p.info.look, true, exifRotation { a.store.open(u) } ?: rotFallback)
        }
        val cached = File(a.store.viewDir, "${p.stem}.jpg")
        val size = p.info.jpgSize
        if (cached.exists() && size != null && cached.length() == size) {
            return ImgSource(cached.path, null, cached, p.info.look, true, exifRotation { FileInputStream(cached) } ?: rotFallback)
        }
        if (!allowNetwork || !p.onCamera || size == null || a.link.link.value != Link.ONLINE) return null
        val f = a.sync.fetchToCache(p.stem, "jpg", progress)
        return ImgSource(f.path, null, f, p.info.look, true, exifRotation { FileInputStream(f) } ?: rotFallback)
    }

    fun exifRotation(open: () -> InputStream?): Int? = try {
        open()?.use { s ->
            val e = ExifInterface(s)
            if (e.getAttribute(ExifInterface.TAG_ORIENTATION) == null) null else e.rotationDegrees
        }
    } catch (_: Exception) { null }

    /** Dimensions brutes (non tournées). */
    fun bounds(ctx: Context, s: ImgSource): Pair<Int, Int>? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        s.open(ctx)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth > 0) o.outWidth to o.outHeight else null
    } catch (_: Exception) { null }

    /** Décode avec un sous-échantillonnage tel que le côté long reste ≥ maxSide (ou l'original), tourné. */
    fun decode(ctx: Context, s: ImgSource, maxSide: Int): Bitmap? {
        val b = bounds(ctx, s) ?: return null
        var sample = 1
        while (max(b.first, b.second) / (sample * 2) >= maxSide * 3 / 4) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = try { s.open(ctx)?.use { BitmapFactory.decodeStream(it, null, o) } } catch (_: OutOfMemoryError) { null } ?: return null
        return PhotoStore.rotateFor(bmp, s.rotation)
    }

    @Suppress("DEPRECATION")
    fun regionDecoder(ctx: Context, s: ImgSource): BitmapRegionDecoder? = try {
        if (s.file != null) BitmapRegionDecoder.newInstance(s.file.path, false)
        else s.open(ctx)?.use { BitmapRegionDecoder.newInstance(it, false) }
    } catch (_: Exception) { null }

    /** Vignette de la grille : version affichée, sinon vignette caméra, sinon miniature du JPEG local. */
    fun tile(ctx: Context, p: Photo): Bitmap? {
        val a = app(ctx)
        val v = shownVersion(ctx, p)
        if (v != null) {
            tiles.get(v)?.let { return it }
            mediaThumb(ctx, v)?.let { tiles.put(v, it); return it }
        }
        a.store.thumb(p.stem)?.let { return it }
        val u = p.jpgUri ?: return null
        tiles.get(u)?.let { return it }
        return mediaThumb(ctx, u)?.also { tiles.put(u, it) }
    }

    private fun mediaThumb(ctx: Context, uri: String): Bitmap? = try {
        ctx.contentResolver.loadThumbnail(Uri.parse(uri), Size(360, 360), null)
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------ looks

    /** Vignette d'une photo dans un look (depuis la petite image de base), ou null si le RAW est nécessaire. */
    fun lookThumb(p: Photo, src: ImgSource, small: Bitmap, look: String): Bitmap? {
        val k = "${src.key}|${small.width}|$look"
        looks.get(k)?.let { return it }
        if (look == src.look) return small
        val conv = LookMath.conversionFor(p.info, src.look, look) ?: return null
        return LookMath.apply(small, conv).also { looks.put(k, it) }
    }

    /** DNG disponible sans attendre (téléphone ou cache) ? */
    fun localDng(ctx: Context, p: Photo): File? {
        val f = File(app(ctx).store.viewDir, "${p.stem}.dng")
        val size = p.info.dngSize
        return if (f.exists() && size != null && f.length() == size) f else null
    }

    fun rawReachable(ctx: Context, p: Photo): Boolean {
        val a = app(ctx)
        return p.dngUri != null || localDng(ctx, p) != null ||
            (p.onCamera && p.info.dngSize != null && !p.info.dngPending && a.link.link.value == Link.ONLINE)
    }

    /** Lit le DNG (téléphone, cache, ou caméra). Bloquant. */
    suspend fun readDng(ctx: Context, p: Photo, progress: ((Float) -> Unit)?): DngDeveloper.Dng = withContext(Dispatchers.IO) { readDng0(ctx, p, progress) }

    private suspend fun readDng0(ctx: Context, p: Photo, progress: ((Float) -> Unit)?): DngDeveloper.Dng {
        val a = app(ctx)
        p.dngUri?.let { u -> a.store.open(u)?.use { return DngDeveloper.parse(it.readBytes()) } }
        localDng(ctx, p)?.let { return DngDeveloper.parse(it.readBytes()) }
        if (!p.onCamera || p.info.dngSize == null || a.link.link.value != Link.ONLINE) throw IOException("RAW indisponible")
        val f = a.sync.fetchToCache(p.stem, "dng", progress)
        return DngDeveloper.parse(f.readBytes())
    }

    /** Référence de luminosité : le JPEG d'origine en petit (look cuit d'origine). */
    suspend fun reference(ctx: Context, p: Photo): Bitmap? =
        app(ctx).store.thumb(p.stem) ?: original(ctx, p, false)?.let { decode(ctx, it, 512) }

    // ------------------------------------------------------------------ enregistrement de versions

    fun stamp(): String = SimpleDateFormat("HHmmss", Locale.FRANCE).format(Date())

    /** Écrit quelques métadonnées EXIF dans la nouvelle version (orientation déjà appliquée aux pixels). */
    fun exifWriter(p: Photo, look: String, note: String): (File) -> Unit = { f ->
        try {
            val e = ExifInterface(f)
            e.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            e.setAttribute(ExifInterface.TAG_MAKE, "HALO 720")
            e.setAttribute(ExifInterface.TAG_MODEL, "Camera Module 3 NoIR 720nm")
            e.setAttribute(ExifInterface.TAG_SOFTWARE, "HALO 720 · $note")
            e.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "look=$look")
            val dt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date((p.info.time * 1000).toLong()))
            e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dt)
            e.setAttribute(ExifInterface.TAG_DATETIME, dt)
            p.info.iso?.let { e.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString()) }
            p.info.exposureUs?.let { e.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (it / 1e6).toString()) }
            e.saveAttributes()
        } catch (_: Exception) {}
    }

    /** Ajoute la version à la photo et l'affiche. */
    fun addVersion(ctx: Context, stem: String, uri: Uri, look: String, edit: Boolean) {
        VersionIndex.get(ctx).put(uri.toString(), look, edit)
        app(ctx).store.update(stem) { it?.copy(versions = it.versions + uri.toString(), lookShown = look) }
    }

    /**
     * Nouvelle version dans un look : depuis le RAW s'il est joignable, sinon depuis le JPEG.
     * stage(texte, progression ou null). À appeler hors du fil principal.
     */
    suspend fun saveLook(ctx: Context, p: Photo, look: String, stage: (String, Float?) -> Unit): Uri = withContext(Dispatchers.IO) {
        val a = app(ctx)
        val bmp: Bitmap
        val note: String
        if (rawReachable(ctx, p)) {
            stage("RAW", 0f)
            val (dng, gain) = rawFor(ctx, p, true) { f -> stage("Copie du RAW", f) } ?: throw IOException("RAW indisponible")
            stage("Développement", 0f)
            bmp = DngDeveloper.develop(dng, p.info, look, gain) { f -> stage("Développement", f) }
            note = "RAW"
        } else {
            stage("Lecture du JPEG", null)
            val src = original(ctx, p, true) { f -> stage("Copie du JPEG", f) } ?: throw IOException("JPEG indisponible")
            val conv = LookMath.conversionFor(p.info, src.look, look) ?: throw IOException("Ce look demande le RAW")
            val o = BitmapFactory.Options().apply { inSampleSize = 1 }
            val full = src.open(ctx)?.use { BitmapFactory.decodeStream(it, null, o) } ?: throw IOException("JPEG illisible")
            stage("Conversion", null)
            val conv1 = LookMath.apply(full, conv)
            full.recycle()
            bmp = PhotoStore.rotateFor(conv1, src.rotation).also { if (it !== conv1) conv1.recycle() }
            note = "JPEG"
        }
        stage("Enregistrement", null)
        val uri = a.store.publishBitmap(bmp, "${p.stem}_${look}.jpg", 94, exifWriter(p, look, "look $look depuis $note"))
        bmp.recycle()
        addVersion(ctx, p.stem, uri, look, false)
        uri
    }

    // ------------------------------------------------------------------ partage

    private fun shareable(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "fr.halo720.app.files", f)

    /** Uris à partager pour ces photos (JPEG du look affiché, RAW en option). Bloquant. */
    suspend fun shareUris(ctx: Context, ps: List<Photo>, withRaw: Boolean, stage: (String, Float?) -> Unit): List<Uri> = withContext(Dispatchers.IO) {
        val out = ArrayList<Uri>()
        for ((i, p) in ps.withIndex()) {
            stage(if (ps.size > 1) "Préparation ${i + 1} / ${ps.size}" else "Préparation", null)
            val v = shownVersion(ctx, p)
            val jpg = when {
                v != null && app(ctx).store.exists(v) -> Uri.parse(v)
                p.jpgUri != null && app(ctx).store.exists(p.jpgUri) -> Uri.parse(p.jpgUri)
                else -> original(ctx, p, true) { f -> stage("Copie du JPEG", f) }?.file?.let { shareable(ctx, it) }
            }
            jpg?.let { out += it }
            if (withRaw) {
                val raw = when {
                    p.dngUri != null && app(ctx).store.exists(p.dngUri) -> Uri.parse(p.dngUri)
                    localDng(ctx, p) != null -> shareable(ctx, localDng(ctx, p)!!)
                    rawReachable(ctx, p) -> try {
                        shareable(ctx, app(ctx).sync.fetchToCache(p.stem, "dng") { f -> stage("Copie du RAW", f) })
                    } catch (_: Exception) { null }
                    else -> null
                }
                raw?.let { out += it }
            }
        }
        out
    }

    fun launchShare(ctx: Context, uris: List<Uri>, onlyJpeg: Boolean) {
        if (uris.isEmpty()) return
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        i.type = if (onlyJpeg) "image/jpeg" else "image/*"
        val clip = ClipData.newRawUri("HALO 720", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        i.clipData = clip
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(i, "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }

    // ------------------------------------------------------------------ suppression

    /** Supprime sur le téléphone (et sur la caméra si everywhere). Bloquant / suspend. */
    suspend fun delete(ctx: Context, stems: List<String>, everywhere: Boolean) = withContext(Dispatchers.IO) {
        val a = app(ctx)
        if (everywhere) {
            val onCam = stems.filter { a.store.photos.value[it]?.onCamera == true }
            if (onCam.isNotEmpty()) {
                if (a.link.link.value != Link.ONLINE) throw IOException("Caméra hors ligne")
                a.link.deleteOnCamera(onCam)
            }
        }
        for (s in stems) {
            a.store.deleteLocal(s)
            File(a.store.viewDir, "$s.jpg").delete()
            File(a.store.viewDir, "$s.dng").delete()
            val p = a.store.photos.value[s]
            if (everywhere || p?.onCamera != true) {
                a.store.update(s) { null }
                a.store.thumbFile(s).delete()
                a.store.forgetThumb(s)
            }
        }
    }

    fun lookName(k: String) = Looks.names[k] ?: k

    /** Travaux qui doivent survivre à la fermeture du visualiseur (enregistrement d'une version). */
    val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    // ------------------------------------------------------------------ RAW en mémoire (une photo à la fois)

    private class RawSlot(val stem: String, val dng: DngDeveloper.Dng, val gain: Float)
    @Volatile private var rawSlot: RawSlot? = null
    private val rawLock = kotlinx.coroutines.sync.Mutex()

    fun rawInMemory(stem: String): Boolean = rawSlot?.stem == stem

    /** DNG + gain d'exposition de la photo, gardés en mémoire pour l'aperçu et l'enregistrement. */
    suspend fun rawFor(ctx: Context, p: Photo, allowDownload: Boolean, progress: ((Float) -> Unit)?): Pair<DngDeveloper.Dng, Float>? =
        rawLock.withLockCompat {
            rawSlot?.takeIf { it.stem == p.stem }?.let { return@withLockCompat it.dng to it.gain }
            val local = p.dngUri != null || localDng(ctx, p) != null
            if (!local && !(allowDownload && rawReachable(ctx, p))) return@withLockCompat null
            rawSlot = null
            val dng = readDng(ctx, p, progress)
            val gain = withContext(Dispatchers.Default) { DngDeveloper.autoGain(dng, p.info, reference(ctx, p)) }
            rawSlot = RawSlot(p.stem, dng, gain)
            dng to gain
        }

    /** Aperçu d'un look depuis le RAW (réduit), ou null si le RAW n'est pas joignable. */
    suspend fun rawPreview(ctx: Context, p: Photo, look: String, maxSide: Int, allowDownload: Boolean, progress: ((Float) -> Unit)? = null): Bitmap? {
        val (dng, gain) = try { rawFor(ctx, p, allowDownload, progress) } catch (_: Exception) { null } ?: return null
        val k = "raw:${p.stem}|$maxSide|$look"
        looks.get(k)?.let { return it }
        return withContext(Dispatchers.Default) { DngDeveloper.proxy(dng, p.info, look, maxSide, gain) }.also { looks.put(k, it) }
    }

    private suspend inline fun <T> kotlinx.coroutines.sync.Mutex.withLockCompat(crossinline f: suspend () -> T): T {
        lock()
        try { return f() } finally { unlock() }
    }
}
