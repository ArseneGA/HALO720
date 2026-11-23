package fr.halo720.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Analyse des images de visée sur le téléphone (la caméra n'a pas le temps) :
 *  - histogramme de luminance (48 classes)
 *  - zones brûlées : masque rouge des pixels ≥ 250
 *  - surlignage de netteté (focus peaking) : contours forts, pour la mise au point manuelle
 * Tout est calculé sur une image réduite (192 × 108), quelques millisecondes par image.
 */
class FrameAnalyzer(scope: CoroutineScope) {
    class Result(val histo: FloatArray, val clip: Bitmap?, val peak: Bitmap?)

    val result = MutableStateFlow<Result?>(null)
    private val input = Channel<Triple<Bitmap, Boolean, Boolean>>(Channel.CONFLATED)
    private val W = 192
    private val H = 108
    private val px = IntArray(W * H)
    private val lum = IntArray(W * H)
    private val clipBufs = arrayOf(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888))
    private val peakBufs = arrayOf(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888))
    private var flip = 0
    private val small = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val canvas = android.graphics.Canvas(small)
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    init {
        scope.launch(Dispatchers.Default) {
            for ((bmp, clip, peak) in input) {
                try { analyze(bmp, clip, peak) } catch (_: Exception) {}
            }
        }
    }

    fun submit(b: Bitmap, clip: Boolean, peak: Boolean) { input.trySend(Triple(b, clip, peak)) }

    private fun analyze(src: Bitmap, wantClip: Boolean, wantPeak: Boolean) {
        synchronized(src) {
            if (src.isRecycled) return
            canvas.drawBitmap(src, android.graphics.Rect(0, 0, src.width, src.height), android.graphics.Rect(0, 0, W, H), paint)
        }
        small.getPixels(px, 0, W, 0, 0, W, H)
        val h = FloatArray(48)
        for (i in px.indices) {
            val c = px[i]
            val l = (AColor.red(c) * 77 + AColor.green(c) * 150 + AColor.blue(c) * 29) shr 8
            lum[i] = l
            h[l * 48 / 256] += 1f
        }
        val mx = h.max().coerceAtLeast(1f)
        for (i in h.indices) h[i] = h[i] / mx
        flip = 1 - flip
        var clipBmp: Bitmap? = null
        if (wantClip) {
            val out = IntArray(W * H)
            for (i in lum.indices) {
                val c = px[i]
                if (AColor.red(c) >= 250 || AColor.green(c) >= 250 || AColor.blue(c) >= 250) out[i] = 0xDDE5243B.toInt()
            }
            clipBmp = clipBufs[flip].also { it.setPixels(out, 0, W, 0, 0, W, H) }
        }
        var peakBmp: Bitmap? = null
        if (wantPeak) {
            val out = IntArray(W * H)
            for (y in 1 until H - 1) for (x in 1 until W - 1) {
                val i = y * W + x
                val gx = -lum[i - W - 1] - 2 * lum[i - 1] - lum[i + W - 1] + lum[i - W + 1] + 2 * lum[i + 1] + lum[i + W + 1]
                val gy = -lum[i - W - 1] - 2 * lum[i - W] - lum[i - W + 1] + lum[i + W - 1] + 2 * lum[i + W] + lum[i + W + 1]
                val g = gx * gx + gy * gy
                if (g > 140 * 140) out[i] = 0xFFE5243B.toInt()
            }
            peakBmp = peakBufs[flip].also { it.setPixels(out, 0, W, 0, 0, W, H) }
        }
        result.value = Result(h, clipBmp, peakBmp)
    }
}
