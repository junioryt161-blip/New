package com.friday.assistant
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView

/** Small floating logo with a tiny caption. It never takes over the screen. */
object OverlayBubble {
    private var box: LinearLayout? = null
    private var logo: LogoView? = null
    private var cap: TextView? = null
    private val h = Handler(Looper.getMainLooper())
    private val hideCap = Runnable { cap?.visibility = View.GONE }

    fun show(c: Context) = h.post {
        if (box != null || !Settings.canDrawOverlays(c)) return@post
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = c.resources.displayMetrics.density
        val l = LogoView(c); val t = TextView(c).apply {
            setTextColor(Color.WHITE); textSize = 11f; setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(0xCC0B1220.toInt()) }
            maxWidth = (220 * d).toInt(); visibility = View.GONE
        }
        val b = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(l, LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt())); addView(t) }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 320 }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> { lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt(); wm.updateViewLayout(b, lp) }
            }; true
        }
        wm.addView(b, lp); box = b; logo = l; cap = t
    }
    fun state(s: Int) { h.post { logo?.state = s } }
    fun caption(s: String) = h.post {
        cap?.apply { text = if (s.length > 70) s.take(70) + "…" else s; visibility = View.VISIBLE }
        h.removeCallbacks(hideCap); h.postDelayed(hideCap, 3500)
    }
    fun hide(c: Context) = h.post {
        box?.let { (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }; box = null; logo = null; cap = null
    }
}
