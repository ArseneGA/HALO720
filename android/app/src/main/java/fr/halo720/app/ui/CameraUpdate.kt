package fr.halo720.app.ui

import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Le logiciel de la caméra est livré dans l'app : on le compare et on l'envoie s'il est plus récent. */
object CameraUpdate {
    private val FILES = listOf("halo_camera.py", "dng.py", "install.sh")

    fun bundledVersion(ctx: Context): String? = try {
        ctx.assets.open("camera/halo_camera.py").bufferedReader().useLines { lines ->
            lines.firstNotNullOfOrNull { Regex("^VERSION = \"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        }
    } catch (_: Exception) { null }

    fun newer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Archive .tar.gz minimale (format ustar) des fichiers de la caméra. */
    fun archive(ctx: Context): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            for (name in FILES) {
                val data = try { ctx.assets.open("camera/$name").readBytes() } catch (_: Exception) { continue }
                val h = ByteArray(512)
                fun put(off: Int, s: String) { val b = s.toByteArray(Charsets.US_ASCII); System.arraycopy(b, 0, h, off, b.size) }
                put(0, name)
                put(100, "0000644\u0000"); put(108, "0001750\u0000"); put(116, "0001750\u0000")
                put(124, String.format("%011o", data.size) + "\u0000")
                put(136, String.format("%011o", System.currentTimeMillis() / 1000) + "\u0000")
                for (i in 148 until 156) h[i] = ' '.code.toByte()
                h[156] = '0'.code.toByte()
                put(257, "ustar\u0000"); put(263, "00")
                val sum = h.sumOf { it.toInt() and 0xff }
                put(148, String.format("%06o", sum) + "\u0000 ")
                gz.write(h); gz.write(data)
                val pad = (512 - data.size % 512) % 512
                gz.write(ByteArray(pad))
            }
            gz.write(ByteArray(1024))
        }
        return bos.toByteArray()
    }
}
