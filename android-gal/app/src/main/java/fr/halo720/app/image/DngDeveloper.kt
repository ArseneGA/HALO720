package fr.halo720.app.image

import android.graphics.Bitmap
import fr.halo720.app.data.PhotoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Développement sur le téléphone des DNG écrits par la caméra (camera/dng.py) :
 * TIFF little-endian, un seul IFD, une bande non compressée, 10 bits empaquetés poids fort d'abord
 * (4 pixels = 5 octets), motif CFA 2×2 (0 = R, 1 = G, 2 = B), niveaux de noir, blanc 1023.
 *
 * Chaîne : dépaquetage → noir → normalisation → dématriçage bilinéaire → gains BdB → gain d'exposition
 * → S(sat)·L_look·base_ccm → écrêtage → gamma sRGB → contraste. Le calcul a été vérifié en Python/numpy
 * sur HALO_0089 (corrélation > 0,98 avec le JPEG de la caméra par canal).
 */
object DngDeveloper {

    class Dng(
        val bytes: ByteArray,
        val w: Int,
        val h: Int,
        val strip: Int,
        val rowBytes: Int,
        /** Couleur (0 R, 1 G, 2 B) aux positions (0,0) (0,1) (1,0) (1,1). */
        val cfa: IntArray,
        val black: FloatArray,
        val white: Float,
        /** Rotation d'affichage en degrés (balise 274). */
        val rotation: Int,
        val neutral: FloatArray?,
    ) {
        /** Valeur brute 10 bits du pixel (x, y). */
        fun raw(x: Int, y: Int): Int {
            val o = strip + y * rowBytes + (x shr 2) * 5
            val b = bytes
            return when (x and 3) {
                0 -> ((b[o].toInt() and 255) shl 2) or ((b[o + 1].toInt() and 255) ushr 6)
                1 -> ((b[o + 1].toInt() and 63) shl 4) or ((b[o + 2].toInt() and 255) ushr 4)
                2 -> ((b[o + 2].toInt() and 15) shl 6) or ((b[o + 3].toInt() and 255) ushr 2)
                else -> ((b[o + 3].toInt() and 3) shl 8) or (b[o + 4].toInt() and 255)
            }
        }
    }

    // ------------------------------------------------------------------ lecture TIFF

    fun parse(bytes: ByteArray): Dng {
        fun u8(p: Int) = bytes[p].toInt() and 255
        fun u16(p: Int) = u8(p) or (u8(p + 1) shl 8)
        fun u32(p: Int) = u16(p) or (u16(p + 2) shl 16)
        if (bytes.size < 16 || u8(0) != 'I'.code || u8(1) != 'I'.code || u16(2) != 42) throw IOException("DNG illisible")
        val ifd = u32(4)
        val n = u16(ifd)
        val tags = HashMap<Int, DoubleArray>()
        val sizes = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 7 to 1, 9 to 4, 10 to 8)
        for (i in 0 until n) {
            val e = ifd + 2 + 12 * i
            val tag = u16(e)
            val typ = u16(e + 2)
            val cnt = u32(e + 4)
            val sz = (sizes[typ] ?: continue) * cnt
            if (cnt <= 0 || cnt > 4096) continue
            val p = if (sz <= 4) e + 8 else u32(e + 8)
            tags[tag] = DoubleArray(cnt) { k ->
                when (typ) {
                    1, 2, 7 -> u8(p + k).toDouble()
                    3 -> u16(p + 2 * k).toDouble()
                    4 -> u32(p + 4 * k).toLong().and(0xFFFFFFFFL).toDouble()
                    9 -> u32(p + 4 * k).toDouble()
                    5 -> u32(p + 8 * k).toLong().and(0xFFFFFFFFL).toDouble() / u32(p + 8 * k + 4).toLong().and(0xFFFFFFFFL).coerceAtLeast(1)
                    10 -> u32(p + 8 * k).toDouble() / u32(p + 8 * k + 4).let { if (it == 0) 1 else it }
                    else -> 0.0
                }
            }
        }
        val w = tags[256]?.get(0)?.toInt() ?: throw IOException("DNG sans largeur")
        val h = tags[257]?.get(0)?.toInt() ?: throw IOException("DNG sans hauteur")
        val bps = tags[258]?.get(0)?.toInt() ?: 10
        if (bps != 10) throw IOException("DNG $bps bits non pris en charge")
        if ((tags[259]?.get(0)?.toInt() ?: 1) != 1) throw IOException("DNG compressé non pris en charge")
        val strip = tags[273]?.get(0)?.toInt() ?: throw IOException("DNG sans données")
        val rowBytes = w * 10 / 8
        if (strip.toLong() + rowBytes.toLong() * h > bytes.size) throw IOException("DNG tronqué")
        val cfa = tags[33422]?.takeIf { it.size >= 4 }?.let { a -> IntArray(4) { a[it].toInt() } } ?: intArrayOf(2, 1, 1, 0)
        val bl = tags[50714]
        val black = FloatArray(4) { k -> (bl?.let { it[if (it.size >= 4) k else 0] } ?: 64.0).toFloat() }
        val white = (tags[50717]?.get(0) ?: 1023.0).toFloat()
        val rot = when (tags[274]?.get(0)?.toInt() ?: 1) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 }
        val neutral = tags[50728]?.takeIf { it.size >= 3 }?.let { a -> FloatArray(3) { a[it].toFloat() } }
        return Dng(bytes, w, h, strip, rowBytes, cfa, black, white, rot, neutral)
    }

    // ------------------------------------------------------------------ paramètres de rendu

    /** Matrice finale (gains BdB et exposition compris) et table gamma + contraste. */
    class Params(val m: FloatArray, val lut: IntArray)

    /** Gains de balance des blancs (R, G, B). */
    fun wbGains(dng: Dng, info: PhotoInfo?): FloatArray {
        LookMath.gains(info)?.let { return floatArrayOf(it[0], 1f, it[1]) }
        val n = dng.neutral ?: return floatArrayOf(1f, 1f, 1f)
        return floatArrayOf(1f / n[0].coerceAtLeast(0.05f), 1f / n[1].coerceAtLeast(0.05f), 1f / n[2].coerceAtLeast(0.05f))
    }

    private fun srgb(v: Double): Double = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055

    fun params(dng: Dng, info: PhotoInfo?, look: String, gain: Float): Params {
        val ccm = LookMath.lookCcm(look, LookMath.baseCcm(info))
        val wb = wbGains(dng, info)
        val m = FloatArray(9) { gain * ccm[it] * wb[it % 3] }
        val c = LookMath.def(look).contrast
        val lut = IntArray(4096) { i ->
            val g = srgb(i / 4095.0).toFloat()
            ((0.5f + (g - 0.5f) * c).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }
        return Params(m, lut)
    }

    /** Zone du capteur à développer : le JPEG est recadré au zoom, le RAW est toujours le capteur entier. */
    fun cropRect(dng: Dng, info: PhotoInfo?): IntArray {
        val z = info?.zoom ?: 1.0
        if (z <= 1.01) return intArrayOf(0, 0, dng.w, dng.h)
        val cw = (dng.w / z).roundToInt().coerceIn(16, dng.w) and 1.inv()
        val ch = (dng.h / z).roundToInt().coerceIn(16, dng.h) and 1.inv()
        val x0 = ((dng.w - cw) / 2) and 1.inv()
        val y0 = ((dng.h - ch) / 2) and 1.inv()
        return intArrayOf(x0, y0, x0 + cw, y0 + ch)
    }

    // ------------------------------------------------------------------ aperçu rapide (demi-définition échantillonnée)

    /** RGB linéaire (avant matrice) d'une cellule 2×2 dont le coin est (x, y). */
    private fun cell(d: Dng, x: Int, y: Int, out: FloatArray) {
        val norm = 1f / (d.white - d.black.average().toFloat())
        out[0] = 0f; out[1] = 0f; out[2] = 0f
        for (dy in 0..1) for (dx in 0..1) {
            val xx = x + dx
            val yy = y + dy
            val k = ((yy and 1) shl 1) or (xx and 1)
            val v = ((d.raw(xx, yy) - d.black[k]) * norm).coerceIn(0f, 1f)
            val c = d.cfa[k]
            out[c] += if (c == 1) v * 0.5f else v
        }
    }

    /**
     * Image réduite (côté long ≈ maxSide), une cellule 2×2 par pixel, orientée. Rapide (≈ 50 ms) :
     * sert aux vignettes de looks et à l'aperçu instantané quand le JPEG ne suffit pas (N&B).
     */
    fun proxy(d: Dng, info: PhotoInfo?, look: String, maxSide: Int, gain: Float): Bitmap {
        val p = params(d, info, look, gain)
        val r = cropRect(d, info)
        val cw = r[2] - r[0]
        val ch = r[3] - r[1]
        val step = max(1, (max(cw, ch) / 2 + maxSide - 1) / maxSide)
        val ow = max(1, cw / (2 * step))
        val oh = max(1, ch / (2 * step))
        val px = IntArray(ow * oh)
        val c = FloatArray(3)
        val m = p.m
        for (j in 0 until oh) {
            val y = (r[1] + j * 2 * step) and 1.inv()
            for (i in 0 until ow) {
                val x = (r[0] + i * 2 * step) and 1.inv()
                cell(d, x, y, c)
                px[j * ow + i] = pack(m, p.lut, c[0], c[1], c[2])
            }
        }
        val b = Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
        return rotate(b, d.rotation)
    }

    /**
     * Gain d'exposition : la caméra applique un gain numérique qui n'est pas dans les métadonnées.
     * On cale la luminance linéaire moyenne du rendu sur celle du JPEG d'origine (ref, look d'origine),
     * sinon sur 18 %. Vérifié sur HALO_0089 : gain ≈ 2,0, erreur RMS minimale.
     */
    fun autoGain(d: Dng, info: PhotoInfo?, ref: Bitmap?): Float {
        val target = ref?.let { LookMath.meanLinearLuma(it) }?.takeIf { it > 0.002f } ?: 0.18f
        val ccm = info?.let { LookMath.bakedCcm(it) } ?: LookMath.lookCcm("brut", LookMath.baseCcm(null))
        val wb = wbGains(d, info)
        val m = FloatArray(9) { ccm[it] * wb[it % 3] }
        val r = cropRect(d, info)
        val step = max(1, max(r[2] - r[0], r[3] - r[1]) / 2 / 160)
        val lin = ArrayList<FloatArray>()
        val c = FloatArray(3)
        var y = r[1]
        while (y + 1 < r[3]) {
            var x = r[0]
            while (x + 1 < r[2]) {
                cell(d, x and 1.inv(), y and 1.inv(), c)
                lin += floatArrayOf(
                    m[0] * c[0] + m[1] * c[1] + m[2] * c[2],
                    m[3] * c[0] + m[4] * c[1] + m[5] * c[2],
                    m[6] * c[0] + m[7] * c[1] + m[8] * c[2],
                )
                x += 2 * step
            }
            y += 2 * step
        }
        if (lin.isEmpty()) return 1f
        var g = 1f
        repeat(4) {
            var s = 0.0
            for (v in lin) {
                s += 0.299f * (v[0] * g).coerceIn(0f, 1f) + 0.587f * (v[1] * g).coerceIn(0f, 1f) + 0.114f * (v[2] * g).coerceIn(0f, 1f)
            }
            val mean = (s / lin.size).toFloat().coerceAtLeast(1e-5f)
            g = (g * target / mean).coerceIn(0.25f, 16f)
        }
        return g
    }

    // ------------------------------------------------------------------ développement pleine définition

    /**
     * Dématriçage bilinéaire pleine définition, par bandes, sur plusieurs cœurs. Le résultat est orienté
     * (rotation appliquée aux pixels). Mémoire : le bitmap final + quelques lignes par fil.
     */
    suspend fun develop(d: Dng, info: PhotoInfo?, look: String, gain: Float, onProgress: (Float) -> Unit = {}): Bitmap =
        withContext(Dispatchers.Default) {
            val p = params(d, info, look, gain)
            val r = cropRect(d, info)
            val cw = r[2] - r[0]
            val ch = r[3] - r[1]
            val rot = d.rotation
            val ow = if (rot == 90 || rot == 270) ch else cw
            val oh = if (rot == 90 || rot == 270) cw else ch
            val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val per = (ch + threads - 1) / threads
            val done = AtomicInteger(0)
            coroutineScope {
                (0 until threads).map { t ->
                    async {
                        val v0 = t * per
                        val v1 = min(ch, v0 + per)
                        if (v0 < v1) band(d, p, r, v0, v1, out, rot) { n ->
                            onProgress(done.addAndGet(n).toFloat() / ch)
                        }
                    }
                }.awaitAll()
            }
            out
        }

    private suspend fun band(d: Dng, p: Params, r: IntArray, v0: Int, v1: Int, out: Bitmap, rot: Int, step: (Int) -> Unit) {
        val w = d.w
        val h = d.h
        val norm = 1f / (d.white - d.black.average().toFloat())
        // trois lignes glissantes du capteur, normalisées
        var prev = FloatArray(w)
        var cur = FloatArray(w)
        var next = FloatArray(w)
        fun reflect(y: Int) = if (y < 0) -y else if (y >= h) 2 * h - 2 - y else y
        fun unpack(yIn: Int, dst: FloatArray) {
            val y = reflect(yIn)
            val base = d.strip + y * d.rowBytes
            val b = d.bytes
            val k0 = (y and 1) shl 1
            val bl0 = d.black[k0]
            val bl1 = d.black[k0 + 1]
            var x = 0
            var o = base
            while (x + 3 < w) {
                val b0 = b[o].toInt() and 255
                val b1 = b[o + 1].toInt() and 255
                val b2 = b[o + 2].toInt() and 255
                val b3 = b[o + 3].toInt() and 255
                val b4 = b[o + 4].toInt() and 255
                dst[x] = (((b0 shl 2) or (b1 ushr 6)) - bl0) * norm
                dst[x + 1] = ((((b1 and 63) shl 4) or (b2 ushr 4)) - bl1) * norm
                dst[x + 2] = ((((b2 and 15) shl 6) or (b3 ushr 2)) - bl0) * norm
                dst[x + 3] = ((((b3 and 3) shl 8) or b4) - bl1) * norm
                x += 4
                o += 5
            }
            for (i in 0 until w) { val v = dst[i]; dst[i] = if (v < 0f) 0f else if (v > 1f) 1f else v }
        }
        val cw = r[2] - r[0]
        val ch = r[3] - r[1]
        val chunk = 32
        val m = p.m
        val lut = p.lut
        var vStart = v0
        unpack(r[1] + v0 - 1, prev)
        unpack(r[1] + v0, cur)
        unpack(r[1] + v0 + 1, next)
        while (vStart < v1) {
            coroutineContext.ensureActive()
            val vEnd = min(v1, vStart + chunk)
            val n = vEnd - vStart
            val block = IntArray(n * cw)
            for (v in vStart until vEnd) {
                if (v != vStart || vStart != v0) {
                    // avance d'une ligne
                    val t = prev; prev = cur; cur = next; next = t
                    unpack(r[1] + v + 1, next)
                }
                val y = r[1] + v
                val rowHasR = d.cfa[(y and 1) shl 1] == 0 || d.cfa[((y and 1) shl 1) + 1] == 0
                val k0 = (y and 1) shl 1
                for (u in 0 until cw) {
                    val x = r[0] + u
                    val xl = if (x == 0) 1 else x - 1
                    val xr = if (x == w - 1) w - 2 else x + 1
                    val c = d.cfa[k0 or (x and 1)]
                    val self = cur[x]
                    var rr: Float
                    var gg: Float
                    var bb: Float
                    if (c == 1) {
                        gg = self
                        val hz = (cur[xl] + cur[xr]) * 0.5f
                        val vt = (prev[x] + next[x]) * 0.5f
                        if (rowHasR) { rr = hz; bb = vt } else { bb = hz; rr = vt }
                    } else {
                        gg = (cur[xl] + cur[xr] + prev[x] + next[x]) * 0.25f
                        val dg = (prev[xl] + prev[xr] + next[xl] + next[xr]) * 0.25f
                        if (c == 0) { rr = self; bb = dg } else { bb = self; rr = dg }
                    }
                    val px = pack(m, lut, rr, gg, bb)
                    val vv = v - vStart
                    val idx = when (rot) {
                        90 -> u * n + (n - 1 - vv)
                        180 -> (n - 1 - vv) * cw + (cw - 1 - u)
                        270 -> (cw - 1 - u) * n + vv
                        else -> vv * cw + u
                    }
                    block[idx] = px
                }
            }
            synchronized(out) {
                when (rot) {
                    90 -> out.setPixels(block, 0, n, ch - vEnd, 0, n, cw)
                    180 -> out.setPixels(block, 0, cw, 0, ch - vEnd, cw, n)
                    270 -> out.setPixels(block, 0, n, vStart, 0, n, cw)
                    else -> out.setPixels(block, 0, cw, 0, vStart, cw, n)
                }
            }
            step(n)
            vStart = vEnd
        }
    }

    private fun pack(m: FloatArray, lut: IntArray, r: Float, g: Float, b: Float): Int {
        val ro = m[0] * r + m[1] * g + m[2] * b
        val go = m[3] * r + m[4] * g + m[5] * b
        val bo = m[6] * r + m[7] * g + m[8] * b
        return (0xFF shl 24) or (lut[q(ro)] shl 16) or (lut[q(go)] shl 8) or lut[q(bo)]
    }

    private fun q(v: Float): Int = if (v <= 0f) 0 else if (v >= 1f) 4095 else (v * 4095f).toInt()

    fun rotate(b: Bitmap, deg: Int): Bitmap {
        if (deg % 360 == 0) return b
        val mx = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, mx, true)
    }
}
