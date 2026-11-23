package fr.halo720.app.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okio.BufferedSource

/** Une image de la visée. [seq] change à chaque image (Compose redessine). */
class Frame(val bitmap: Bitmap, val seq: Long, val atMs: Long)

/**
 * Lecture du flux MJPEG de la caméra (1280 × 720). Décodage direct dans un petit groupe de
 * bitmaps réutilisés : pas d'allocation par image, retard minimal (aucune mise en mémoire tampon).
 */
class MjpegStream(private val link: CameraLink) {
    val frame = MutableStateFlow<Frame?>(null)
    val fps = MutableStateFlow(0.0)
    val stalled = MutableStateFlow(false)
    private var job: Job? = null
    private val pool = ArrayDeque<Bitmap>()
    private var seq = 0L

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    read()
                } catch (_: Exception) {
                }
                stalled.value = true
                delay(600)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun CoroutineScope.read() {
        val req = Request.Builder().url(link.url("/stream.mjpg")).build()
        link.streamClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            val src = resp.body!!.source()
            var len = -1
            var times = ArrayDeque<Long>()
            while (isActive) {
                val line = src.readUtf8Line() ?: return
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    len = line.substringAfter(':').trim().toIntOrNull() ?: -1
                } else if (line.isEmpty() && len > 0) {
                    val bytes = src.readByteArray(len.toLong())
                    len = -1
                    decode(bytes)
                    val now = SystemClock.elapsedRealtime()
                    times.addLast(now)
                    while (times.size > 1 && now - times.first() > 2000) times.removeFirst()
                    if (times.size > 1) fps.value = (times.size - 1) * 1000.0 / (now - times.first())
                    stalled.value = false
                }
            }
        }
    }

    private fun decode(bytes: ByteArray) {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        // 3 bitmaps en rotation : celui affiché n'est jamais réécrit pendant qu'on le dessine
        val reuse = if (pool.size >= 3) pool.removeFirst() else null
        if (reuse != null) opts.inBitmap = reuse
        val bmp = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: IllegalArgumentException) {
            opts.inBitmap = null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } ?: return
        pool.addLast(bmp)
        frame.value = Frame(bmp, ++seq, SystemClock.elapsedRealtime())
    }
}
