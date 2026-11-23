package fr.halo720.app.image

import android.graphics.Bitmap
import fr.halo720.app.data.LookDef
import fr.halo720.app.data.Looks
import fr.halo720.app.data.PhotoInfo
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Mathématiques des looks IR.
 *
 * La caméra rend son JPEG avec CCM_total = S(sat) · L_look · base_ccm, en RGB linéaire (S = matrice de
 * saturation de l'ISP, luminance Rec.601 : vérifié sur l'échantillon HALO_0089, le CCM publié vaut
 * exactement S(1,25) · L_bleu · base), puis gamma et contraste de l'ISP.
 *
 * Passer du look A (cuit dans le JPEG) au look B : on linéarise (gamma 2,2), on applique
 * CCM_B · CCM_A⁻¹, on regamme et on remplace le contraste A par le contraste B.
 * Si CCM_A n'est pas inversible (N&B : saturation 0), il faut repartir du RAW.
 */
object LookMath {
    private val LUMA = floatArrayOf(0.299f, 0.587f, 0.114f)

    /** Matrice de saturation de l'ISP (3×3, ligne par ligne). */
    fun satMatrix(s: Float): FloatArray {
        val m = FloatArray(9)
        for (r in 0..2) for (c in 0..2) m[r * 3 + c] = (if (r == c) s else 0f) + (1f - s) * LUMA[c]
        return m
    }

    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(9)
        for (r in 0..2) for (c in 0..2) {
            var s = 0f
            for (k in 0..2) s += a[r * 3 + k] * b[k * 3 + c]
            o[r * 3 + c] = s
        }
        return o
    }

    fun det(m: FloatArray): Float =
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

    /** Inverse 3×3, ou null si la matrice n'est pas inversible (N&B). */
    fun invert(m: FloatArray): FloatArray? {
        val d = det(m)
        val scale = m.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        if (abs(d) < 1e-4f * scale * scale * scale) return null
        val i = 1f / d
        return floatArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * i, (m[2] * m[7] - m[1] * m[8]) * i, (m[1] * m[5] - m[2] * m[4]) * i,
            (m[5] * m[6] - m[3] * m[8]) * i, (m[0] * m[8] - m[2] * m[6]) * i, (m[2] * m[3] - m[0] * m[5]) * i,
            (m[3] * m[7] - m[4] * m[6]) * i, (m[1] * m[6] - m[0] * m[7]) * i, (m[0] * m[4] - m[1] * m[3]) * i,
        )
    }

    fun def(key: String): LookDef = Looks.defs[key] ?: Looks.defs["brut"]!!

    private fun JSONObject.floats(k: String, n: Int): FloatArray? {
        val a = optJSONArray(k) ?: return null
        if (a.length() != n) return null
        return FloatArray(n) { a.optDouble(it).toFloat() }
    }

    /** base_ccm de la photo (métadonnées), sinon celle publiée par la caméra. */
    fun baseCcm(info: PhotoInfo?): FloatArray = info?.camera?.floats("base_ccm", 9) ?: Looks.baseCcm

    /** CCM total (S · L · base) d'un look. */
    fun lookCcm(key: String, base: FloatArray): FloatArray {
        val d = def(key)
        return mul(satMatrix(d.sat), mul(d.m, base))
    }

    /** CCM réellement cuit dans le JPEG d'origine : celui des métadonnées, sinon recalculé. */
    fun bakedCcm(info: PhotoInfo): FloatArray =
        info.camera?.floats("ColourCorrectionMatrix", 9) ?: lookCcm(info.look, baseCcm(info))

    /** Contraste cuit dans le JPEG d'origine. */
    fun bakedContrast(info: PhotoInfo): Float =
        info.camera?.optJSONObject("look_matrix")?.optDouble("contrast", Double.NaN)?.toFloat()?.takeIf { !it.isNaN() }
            ?: def(info.look).contrast

    /** Gains de balance des blancs [R, B] de la photo, ou null. */
    fun gains(info: PhotoInfo?): FloatArray? = info?.camera?.floats("ColourGains", 2)

    /** Conversion d'un look à l'autre, ou null s'il faut le RAW. */
    class Conversion(val m: FloatArray, val contrastFrom: Float, val contrastTo: Float)

    fun conversion(fromCcm: FloatArray, fromContrast: Float, toKey: String, base: FloatArray): Conversion? {
        val inv = invert(fromCcm) ?: return null
        return Conversion(mul(lookCcm(toKey, base), inv), fromContrast, def(toKey).contrast)
    }

    /** Conversion depuis le look cuit (sourceLook) d'une image vers toKey. */
    fun conversionFor(info: PhotoInfo, sourceLook: String, toKey: String): Conversion? {
        val base = baseCcm(info)
        val from = if (sourceLook == info.look) bakedCcm(info) else lookCcm(sourceLook, base)
        val fc = if (sourceLook == info.look) bakedContrast(info) else def(sourceLook).contrast
        return conversion(from, fc, toKey, base)
    }

    fun canConvertFrom(info: PhotoInfo, sourceLook: String): Boolean =
        invert(if (sourceLook == info.look) bakedCcm(info) else lookCcm(sourceLook, baseCcm(info))) != null

    // ------------------------------------------------------------------ transformation par pixel

    /** 256 entrées : valeur gamma (0..255) -> linéaire, après retrait du contraste source. */
    private fun linLut(contrast: Float): FloatArray = FloatArray(256) { v ->
        val g = (0.5f + (v / 255f - 0.5f) / contrast).coerceIn(0f, 1f)
        g.toDouble().pow(2.2).toFloat()
    }

    /** 4096 entrées : linéaire (0..1) -> gamma 8 bits, contraste cible compris. */
    private fun gamLut(contrast: Float): IntArray = IntArray(4096) { i ->
        val g = (i / 4095.0).pow(1 / 2.2).toFloat()
        ((0.5f + (g - 0.5f) * contrast).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }

    /** Applique la conversion à un bitmap (copie). À appeler hors du fil principal. */
    fun apply(src: Bitmap, conv: Conversion): Bitmap {
        val w = src.width
        val h = src.height
        val lin = linLut(conv.contrastFrom)
        val gam = gamLut(conv.contrastTo)
        val m = conv.m
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val r = lin[(p shr 16) and 255]
                val g = lin[(p shr 8) and 255]
                val b = lin[p and 255]
                val ro = m[0] * r + m[1] * g + m[2] * b
                val go = m[3] * r + m[4] * g + m[5] * b
                val bo = m[6] * r + m[7] * g + m[8] * b
                row[x] = (0xFF shl 24) or (gam[idx(ro)] shl 16) or (gam[idx(go)] shl 8) or gam[idx(bo)]
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    private fun idx(v: Float): Int = if (v <= 0f) 0 else if (v >= 1f) 4095 else (v * 4095f).toInt()

    /** Petite copie (côté long ≤ maxSide) pour les vignettes de looks. */
    fun small(src: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / max(src.width, src.height)
        if (s >= 1f) return src
        return Bitmap.createScaledBitmap(src, max(1, (src.width * s).roundToInt()), max(1, (src.height * s).roundToInt()), true)
    }

    /** Luminance linéaire moyenne (gamma 2,2) d'un bitmap, échantillonnée. */
    fun meanLinearLuma(b: Bitmap): Float {
        val lut = FloatArray(256) { (it / 255.0).pow(2.2).toFloat() }
        val step = max(1, min(b.width, b.height) / 64)
        var s = 0.0
        var n = 0
        val row = IntArray(b.width)
        var y = 0
        while (y < b.height) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            var x = 0
            while (x < b.width) {
                val p = row[x]
                s += 0.299f * lut[(p shr 16) and 255] + 0.587f * lut[(p shr 8) and 255] + 0.114f * lut[p and 255]
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) 0.18f else (s / n).toFloat()
    }
}
