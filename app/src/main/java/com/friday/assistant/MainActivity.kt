package com.friday.assistant
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var feed: TextView
    private lateinit var power: Button
    private lateinit var checks: LinearLayout
    private val BG = 0xFF0B1220.toInt(); private val CARD = 0xFF151F36.toInt(); private val ACC = 0xFF00E5FF.toInt(); private val MUT = 0xFF8FA3BF.toInt()
    private fun dp(i: Int) = (i * resources.displayMetrics.density).toInt()

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(CARD) } }
        c.addView(TextView(this).apply { text = title; setTextColor(ACC); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f })
        parent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }); return c
    }
    private fun input(p: LinearLayout, hint: String, v: String) = EditText(this).apply {
        this.hint = hint; setText(v); setTextColor(Color.WHITE); setHintTextColor(MUT); textSize = 16f
        backgroundTintList = android.content.res.ColorStateList.valueOf(ACC)
    }.also { p.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    private fun pill(t: String, filled: Boolean, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; setTextColor(if (filled) Color.parseColor("#06232A") else ACC); typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); if (filled) setColor(ACC) else { setColor(Color.TRANSPARENT); setStroke(dp(1), ACC) } }
        setOnClickListener { f() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        window.statusBarColor = BG; window.navigationBarColor = BG
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(40), dp(18), dp(24)) }

        // header with logo
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(LogoView(this), LinearLayout.LayoutParams(dp(64), dp(64)))
        val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        ht.addView(TextView(this).apply { text = prefs.assistantName; setTextColor(Color.WHITE); textSize = 26f; typeface = Typeface.DEFAULT_BOLD })
        ht.addView(TextView(this).apply { text = "Voice assistant"; setTextColor(MUT); textSize = 13f })
        head.addView(ht); root.addView(head)

        // power
        val pc = card(root, "ASSISTANT")
        power = pill("", true) { toggle() }
        pc.addView(power, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        pc.addView(TextView(this).apply { text = "Say “${prefs.wakeWord} open YouTube” or “${prefs.wakeWord} play Casa Tupka on YouTube”"; setTextColor(MUT); textSize = 13f; setPadding(0, dp(10), 0, 0) })

        // live feed
        val fc = card(root, "LIVE")
        feed = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(0, dp(8), 0, 0); minLines = 4 }
        fc.addView(feed)

        // setup checklist
        val sc = card(root, "SETUP  (tap a row that has ✗)")
        checks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; sc.addView(checks)

        // names
        val nc = card(root, "PERSONALISE")
        val name = input(nc, "Assistant name", prefs.assistantName)
        val wake = input(nc, "Wake word (e.g. friday)", prefs.wakeWord)
        val callMe = input(nc, "It calls me", prefs.callMe)
        nc.addView(pill("Save", true) {
            prefs.assistantName = name.text.toString().ifBlank { "Friday" }; prefs.wakeWord = wake.text.toString().ifBlank { "friday" }; prefs.callMe = callMe.text.toString().ifBlank { "boss" }
            Toast.makeText(this, "Saved. Turn the assistant off and on.", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        nc.addView(pill("Train my voice (only obey me)", false) {
            prefs.enabled = true; ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_ENROLL)); refresh()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        nc.addView(pill("Forget my voice", false) { prefs.voiceprint = null; Toast.makeText(this, "Voice removed – anyone can command now", Toast.LENGTH_SHORT).show() },
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
    }

    private fun toggle() {
        val i = Intent(this, VoiceService::class.java)
        if (prefs.enabled) { prefs.enabled = false; stopService(i) } else { prefs.enabled = true; ContextCompat.startForegroundService(this, i) }
        refresh()
    }
    private fun accOn() = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(packageName) == true
    private fun refresh() {
        power.text = if (prefs.enabled) "■  STOP ${prefs.assistantName.uppercase()}" else "●  START ${prefs.assistantName.uppercase()}"
        checks.removeAllViews()
        fun row(ok: Boolean, label: String, act: () -> Unit) = TextView(this).apply {
            text = (if (ok) "✓  " else "✗  ") + label; textSize = 16f; setPadding(0, dp(9), 0, dp(9))
            setTextColor(if (ok) 0xFF69F0AE.toInt() else 0xFFFF8A80.toInt()); if (!ok) setOnClickListener { act() }
        }.also { checks.addView(it) }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        row(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED, "Microphone & notifications") {
            val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
            ActivityCompat.requestPermissions(this, p.toTypedArray(), 1)
        }
        row(Settings.canDrawOverlays(this), "Show over other apps (floating logo)") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        row(accOn(), "Accessibility (scroll, tap, type in apps)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        row(pm.isIgnoringBatteryOptimizations(packageName), "Run without battery limits") { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        synchronized(Bus.lines) { feed.text = if (Bus.lines.isEmpty()) "Nothing yet…" else Bus.lines.takeLast(8).joinToString("\n") }
    }
    override fun onResume() { super.onResume(); Bus.onLine = { refresh() }; refresh() }
    override fun onPause() { super.onPause(); Bus.onLine = null }
}
