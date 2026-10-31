package com.friday.assistant
import android.content.*
import androidx.core.content.ContextCompat
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Prefs(c).enabled) ContextCompat.startForegroundService(c, Intent(c, VoiceService::class.java))
    }
}
