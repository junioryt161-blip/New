package com.friday.assistant
import android.content.Context

class Prefs(c: Context) {
    private val p = c.getSharedPreferences("friday", Context.MODE_PRIVATE)
    var assistantName: String get() = p.getString("name", "Friday")!!; set(v) = p.edit().putString("name", v).apply()
    var wakeWord: String get() = p.getString("wake", "friday")!!; set(v) = p.edit().putString("wake", v.lowercase().trim()).apply()
    var callMe: String get() = p.getString("callMe", "boss")!!; set(v) = p.edit().putString("callMe", v).apply()
    var enabled: Boolean get() = p.getBoolean("enabled", false); set(v) = p.edit().putBoolean("enabled", v).apply()
    var voiceprint: String? get() = p.getString("vp", null); set(v) = p.edit().putString("vp", v).apply()
    fun voiceprintArray(): DoubleArray? = voiceprint?.split(",")?.map { it.toDouble() }?.toDoubleArray()
}
