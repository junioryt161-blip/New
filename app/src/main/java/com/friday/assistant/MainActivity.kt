package com.friday.assistant
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 80, 48, 48) }
        fun field(hint: String, value: String) = EditText(this).apply { this.hint = hint; setText(value) }.also { root.addView(it) }
        fun button(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }.also { root.addView(it) }
        val name = field("Assistant name", prefs.assistantName)
        val wake = field("Wake word (e.g. friday)", prefs.wakeWord)
        val callMe = field("What should it call you?", prefs.callMe)
        button("Save names") {
            prefs.assistantName = name.text.toString(); prefs.wakeWord = wake.text.toString(); prefs.callMe = callMe.text.toString()
            Toast.makeText(this, "Saved. Turn assistant off and on to apply.", Toast.LENGTH_SHORT).show()
        }
        button("1. Grant permissions") {
            val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) { p += Manifest.permission.POST_NOTIFICATIONS; p += Manifest.permission.READ_MEDIA_AUDIO }
            else p += Manifest.permission.READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(this, p.toTypedArray(), 1)
        }
        button("2. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("3. Enable Friday accessibility (control other apps)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("4. Disable battery optimisation (stay always on)") {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName))
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        button("5. Train my voice (speak for 5 sec after the beep)") {
            prefs.enabled = true
            ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_ENROLL))
        }
        val sw = Switch(this).apply { text = "Assistant ON"; isChecked = prefs.enabled }
        sw.setOnCheckedChangeListener { _, on ->
            prefs.enabled = on
            val i = Intent(this, VoiceService::class.java)
            if (on) ContextCompat.startForegroundService(this, i) else stopService(i)
        }
        root.addView(sw, ViewGroup.LayoutParams(-1, -2))
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
