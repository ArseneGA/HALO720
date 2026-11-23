package fr.halo720.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Réglages qui vivent sur le téléphone (aides à la visée, retours). */
data class Aids(
    val grid: String = "tiers",          // aucune | tiers | carre | diagonales
    val level: Boolean = true,
    val histogram: Boolean = true,
    val clipping: Boolean = false,       // zones brûlées
    val peaking: Boolean = true,         // surlignage de netteté (mise au point manuelle)
    val framing: String = "crop",        // crop = plein écran recadré | fit = image entière
    val orientation: String = "auto",    // auto | paysage | portrait
    val leftHanded: Boolean = false,     // commandes à gauche en paysage
    val vibrations: Boolean = true,
    val sound: Boolean = false,
    val volumeKeys: Boolean = true,
    val review: Boolean = false,         // revoir la photo après la prise
    val touchAf: Boolean = true,
)

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("aids", Context.MODE_PRIVATE)
    val aids = MutableStateFlow(read())

    private fun read() = Aids(
        grid = p.getString("grid", "tiers")!!, level = p.getBoolean("level", true),
        histogram = p.getBoolean("histogram", true), clipping = p.getBoolean("clipping", false),
        peaking = p.getBoolean("peaking", true), framing = p.getString("framing", "crop")!!,
        orientation = p.getString("orientation", "auto")!!, leftHanded = p.getBoolean("left", false),
        vibrations = p.getBoolean("vib", true), sound = p.getBoolean("sound", false),
        volumeKeys = p.getBoolean("volume", true), review = p.getBoolean("review", false),
        touchAf = p.getBoolean("touch_af", true),
    )

    fun edit(f: (Aids) -> Aids) {
        val a = f(aids.value)
        aids.value = a
        p.edit().putString("grid", a.grid).putBoolean("level", a.level).putBoolean("histogram", a.histogram)
            .putBoolean("clipping", a.clipping).putBoolean("peaking", a.peaking).putString("framing", a.framing)
            .putString("orientation", a.orientation).putBoolean("left", a.leftHanded).putBoolean("vib", a.vibrations)
            .putBoolean("sound", a.sound).putBoolean("volume", a.volumeKeys).putBoolean("review", a.review)
            .putBoolean("touch_af", a.touchAf).apply()
    }
}
