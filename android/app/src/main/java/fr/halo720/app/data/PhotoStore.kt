package fr.halo720.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Une photo vue par le téléphone : ce que dit la caméra + ce qui est copié ici.
 *  - jpgUri / dngUri : copies vérifiées dans Images/HALO720 (MediaStore)
 *  - versions : nouvelles versions (autre look, retouche) créées sur le téléphone
 */
data class Photo(
    val stem: String,
    val info: PhotoInfo,
    val jpgUri: String? = null,
    val dngUri: String? = null,
    val onCamera: Boolean = true,
    val versions: List<String> = emptyList(),
    val lookShown: String? = null,
    /** Supprimée « sur le téléphone seulement » : la sauvegarde auto ne la recopie pas. */
    val keepOff: Boolean = false,
) {
    val time get() = info.time
    val rawState: RawState
        get() = when {
            dngUri != null -> RawState.COPIED
            info.hasDng && onCamera -> RawState.WAITING
            else -> RawState.NONE
        }
    val cameraOnly get() = jpgUri == null && onCamera

    fun toJson(): JSONObject = JSONObject().put("info", info.raw).put("jpg", jpgUri).put("dng", dngUri)
        .put("onCamera", onCamera).put("versions", JSONArray(versions)).put("lookShown", lookShown).put("keepOff", keepOff)

    companion object {
        fun from(o: JSONObject): Photo {
            val info = PhotoInfo.from(o.getJSONObject("info"))
            return Photo(info.stem, info, o.optStringOrNull("jpg"), o.optStringOrNull("dng"), o.optBoolean("onCamera", true),
                o.optJSONArray("versions")?.strings() ?: emptyList(), o.optStringOrNull("lookShown"), o.optBoolean("keepOff", false))
        }
    }
}

enum class RawState { COPIED, WAITING, NONE }

class PhotoStore(private val ctx: Context) {
    private val tag = "HALO.store"
    private val file = File(ctx.filesDir, "photos.json")
    val thumbDir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
    val partialDir = File(ctx.cacheDir, "partial").apply { mkdirs() }
    val viewDir = File(ctx.cacheDir, "view").apply { mkdirs() }
    val photos = MutableStateFlow<Map<String, Photo>>(emptyMap())
    private val lock = Any()
    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init {
        load()
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val a = JSONArray(file.readText())
            val m = LinkedHashMap<String, Photo>()
            for (i in 0 until a.length()) {
                val p = Photo.from(a.getJSONObject(i))
                m[p.stem] = p
            }
            photos.value = m
        } catch (e: Exception) {
            Log.w(tag, "index illisible", e)
        }
    }

    private fun save() {
        val a = JSONArray()
        photos.value.values.forEach { a.put(it.toJson()) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    fun update(stem: String, f: (Photo?) -> Photo?) {
        synchronized(lock) {
            val m = LinkedHashMap(photos.value)
            val n = f(m[stem])
            if (n == null) m.remove(stem) else m[stem] = n
            photos.value = m
            save()
        }
    }

    /** Fusionne la liste de la caméra : nouvelles photos, états RAW, photos effacées de la caméra. */
    fun mergeCamera(list: List<PhotoInfo>, complete: Boolean) {
        synchronized(lock) {
            val m = LinkedHashMap(photos.value)
            val seen = HashSet<String>()
            for (info in list) {
                seen += info.stem
                val old = m[info.stem]
                m[info.stem] = old?.copy(info = info, onCamera = true) ?: Photo(info.stem, info)
            }
            if (complete) {
                for ((k, p) in m) if (k !in seen && p.onCamera) m[k] = p.copy(onCamera = false)
                // ni sur la caméra, ni sur le téléphone : on oublie
                m.entries.removeAll { !it.value.onCamera && it.value.jpgUri == null && it.value.dngUri == null }
            }
            photos.value = m
            save()
        }
    }

    fun sorted(): List<Photo> = photos.value.values.sortedWith(compareByDescending<Photo> { it.info.seq }.thenByDescending { it.time })

    // ------------------------------------------------------------------ vignettes

    fun thumbFile(stem: String) = File(thumbDir, "$stem.jpg")

    fun thumb(stem: String): Bitmap? {
        thumbCache.get(stem)?.let { return it }
        val f = thumbFile(stem)
        if (!f.exists()) return null
        val b = BitmapFactory.decodeFile(f.path) ?: return null
        val rotated = rotateFor(b, photos.value[stem]?.info?.orientation ?: 0)
        thumbCache.put(stem, rotated)
        return rotated
    }

    fun forgetThumb(stem: String) = thumbCache.remove(stem)

    // ------------------------------------------------------------------ MediaStore (Images/HALO720)

    /** Copie un fichier vérifié dans Images/HALO720 ; renvoie l'URI. */
    fun publish(src: File, name: String, mime: String, timeMs: Long): Uri {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HALO720")
            put(MediaStore.Images.Media.DATE_TAKEN, timeMs)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val coll = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = cr.insert(coll, values) ?: throw IllegalStateException("MediaStore refuse l'insertion")
        try {
            cr.openOutputStream(uri, "w")!!.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 16) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        return uri
    }

    fun publishBitmap(bmp: Bitmap, name: String, quality: Int = 94, exif: ((File) -> Unit)? = null): Uri {
        val tmp = File(viewDir, name)
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        exif?.invoke(tmp)
        val uri = publish(tmp, name, "image/jpeg", System.currentTimeMillis())
        tmp.delete()
        return uri
    }

    fun exists(uri: String?): Boolean {
        if (uri == null) return false
        return try {
            ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
        } catch (_: Exception) { false }
    }

    fun deleteLocal(stem: String) {
        val p = photos.value[stem] ?: return
        for (u in listOfNotNull(p.jpgUri, p.dngUri) + p.versions) {
            try { ctx.contentResolver.delete(Uri.parse(u), null, null) } catch (_: Exception) {}
        }
        update(stem) { it?.copy(jpgUri = null, dngUri = null, versions = emptyList(), keepOff = true) }
    }

    fun open(uri: String): InputStream? = try { ctx.contentResolver.openInputStream(Uri.parse(uri)) } catch (_: Exception) { null }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun rotateFor(b: Bitmap, deg: Int): Bitmap {
            if (deg % 360 == 0) return b
            val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
            return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }
    }
}
