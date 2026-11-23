package fr.halo720.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Un profil enregistre tout : exposition, look, mode et ses paramètres, zoom, aides à la visée. */
data class Profile(val id: String, val name: String, val icon: String, val cam: JSONObject, val aids: JSONObject?) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("icon", icon).put("cam", cam).put("aids", aids ?: JSONObject.NULL)

    fun summary(): String {
        val iso = cam.optInt("iso", 0).let { if (it == 0) "ISO auto" else "ISO $it" }
        val mode = cam.optString("mode", "photo")
        val sh = when (mode) {
            "pose" -> Steps.poseLabel(cam.optInt("pose_s", 4))
            "interv" -> "${cam.optInt("interv_s")} s × ${cam.optInt("interv_count")}"
            else -> cam.optInt("shutter_us", 0).let { if (it == 0) "auto ${Steps.evLabel(cam.optDouble("ev", 0.0))}" else Steps.shutterLabel(it) }
        }
        val look = Looks.names[cam.optString("look", "bleu")] ?: ""
        val extra = when {
            cam.optInt("timer_s") > 0 -> " · retard. ${cam.optInt("timer_s")} s"
            cam.optString("format") == "jpg" -> " · JPEG seul"
            else -> ""
        }
        return "$iso · $sh · $look$extra"
    }

    companion object {
        fun from(o: JSONObject) = Profile(o.getString("id"), o.getString("name"), o.optString("icon", "•"),
            o.getJSONObject("cam"), o.optJSONObject("aids"))
    }
}

class Profiles(ctx: Context) {
    private val file = File(ctx.filesDir, "profiles.json")
    private val prefs = ctx.getSharedPreferences("halo", Context.MODE_PRIVATE)
    val list = MutableStateFlow<List<Profile>>(emptyList())
    /** Profil chargé, et s'il a été modifié depuis (« Paysage IR • »). */
    val active = MutableStateFlow<String?>(prefs.getString("profile", null))
    val modified = MutableStateFlow(prefs.getBoolean("profile_mod", false))

    init {
        list.value = try {
            val a = JSONArray(file.readText()); List(a.length()) { Profile.from(a.getJSONObject(it)) }
        } catch (_: Exception) { defaults() }
    }

    private fun defaults(): List<Profile> = listOf(
        Profile("paysage", "Paysage IR", "IR", jsonOf("iso" to 100, "shutter_us" to 0, "ev" to -0.67, "look" to "bleu",
            "format" to "jpg+raw", "timer_s" to 0, "mode" to "photo", "zoom" to 1.0, "metering" to "matrix", "af" to "continuous"), null),
        Profile("rue", "Rue N&B", "N&B", jsonOf("iso" to 400, "shutter_us" to 2000, "ev" to 0.0, "look" to "nb",
            "format" to "jpg+raw", "timer_s" to 0, "mode" to "photo", "zoom" to 1.0, "metering" to "matrix", "af" to "continuous"), null),
        Profile("nuit", "Nuit · pose longue", "◐", jsonOf("iso" to 200, "shutter_us" to 0, "ev" to 0.0, "look" to "bleu",
            "format" to "jpg+raw", "timer_s" to 2, "mode" to "pose", "pose_s" to 15, "zoom" to 1.0, "metering" to "matrix", "af" to "continuous"), null),
        Profile("nuages", "Time-lapse nuages", "⏱", jsonOf("iso" to 100, "shutter_us" to 0, "ev" to 0.0, "look" to "bleu",
            "format" to "jpg", "timer_s" to 0, "mode" to "interv", "interv_s" to 5, "interv_count" to 300, "interv_delay_s" to 0,
            "zoom" to 1.0, "metering" to "matrix", "af" to "continuous"), null),
    )

    private fun save() {
        val a = JSONArray(); list.value.forEach { a.put(it.toJson()) }
        file.writeText(a.toString())
    }

    fun setActive(id: String?, mod: Boolean = false) {
        active.value = id; modified.value = mod
        prefs.edit().putString("profile", id).putBoolean("profile_mod", mod).apply()
    }

    fun markModified() { if (active.value != null && !modified.value) setActive(active.value, true) }

    fun add(name: String, cam: JSONObject, aids: JSONObject?): Profile {
        val p = Profile("p" + System.currentTimeMillis(), name, name.take(2).uppercase(), cam, aids)
        list.value = list.value + p; save(); return p
    }

    fun rename(id: String, name: String) { list.value = list.value.map { if (it.id == id) it.copy(name = name) else it }; save() }
    fun delete(id: String) { list.value = list.value.filter { it.id != id }; if (active.value == id) setActive(null); save() }
    fun move(id: String, delta: Int) {
        val l = list.value.toMutableList(); val i = l.indexOfFirst { it.id == id }; val j = i + delta
        if (i < 0 || j !in l.indices) return
        l.add(j, l.removeAt(i)); list.value = l; save()
    }
    fun overwrite(id: String, cam: JSONObject, aids: JSONObject?) {
        list.value = list.value.map { if (it.id == id) it.copy(cam = cam, aids = aids) else it }; save(); setActive(id, false)
    }
    fun get(id: String?) = list.value.firstOrNull { it.id == id }
}
