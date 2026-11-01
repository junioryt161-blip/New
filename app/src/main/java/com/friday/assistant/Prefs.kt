package com.friday.assistant
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class Prefs(c: Context) {
    private val p = c.getSharedPreferences("friday", Context.MODE_PRIVATE)
    var assistantName: String get() = p.getString("name", "Friday")!!; set(v) = p.edit().putString("name", v).apply()
    var wakeWord: String get() = p.getString("wake", "friday")!!; set(v) = p.edit().putString("wake", v.lowercase().trim()).apply()
    var callMe: String get() = p.getString("callMe", "boss")!!; set(v) = p.edit().putString("callMe", v).apply()
    var enabled: Boolean get() = p.getBoolean("enabled", false); set(v) = p.edit().putBoolean("enabled", v).apply()
    var voiceprint: String? get() = p.getString("vp", null); set(v) = p.edit().putString("vp", v).apply()
    var gain: Float get() = p.getFloat("gain", 3f); set(v) = p.edit().putFloat("gain", v).apply()
    var lang: String get() = p.getString("lang", "en-IN")!!; set(v) = p.edit().putString("lang", v).apply()
    var smart: Boolean get() = p.getBoolean("smart", true); set(v) = p.edit().putBoolean("smart", v).apply()
    var far: Boolean get() = p.getBoolean("far", false); set(v) = p.edit().putBoolean("far", v).apply()
    var aiKey: String get() = p.getString("aiKey", "")!!; set(v) = p.edit().putString("aiKey", v.trim()).apply()
    fun voiceprintArray(): DoubleArray? = voiceprint?.split(",")?.map { it.toDouble() }?.toDoubleArray()
}

/** Phrases Friday has understood before: next time they are recognised instantly, offline. */
object Learned {
    private var sp: SharedPreferences? = null
    private val map = LinkedHashMap<String, String>()
    @Synchronized fun init(c: Context) {
        if (sp != null) return
        sp = c.getSharedPreferences("learned", Context.MODE_PRIVATE)
        try { val j = JSONObject(sp!!.getString("m", "{}")!!); j.keys().forEach { map[it] = j.getString(it) } } catch (e: Exception) {}
    }
    @Synchronized fun get(k: String): String? = map[k]
    @Synchronized fun put(k: String, v: String) { map[k] = v; while (map.size > 400) map.remove(map.keys.first()); save() }
    @Synchronized fun clear() { map.clear(); save() }
    @Synchronized fun size() = map.size
    private fun save() { sp?.edit()?.putString("m", JSONObject(map as Map<*, *>).toString())?.apply() }
}
