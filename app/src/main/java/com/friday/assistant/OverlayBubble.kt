package com.friday.assistant
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.*

/** Small floating circle. Never opens a full screen, so the app underneath is not interrupted. */
object OverlayBubble {
    private var v: View? = null
    fun show(c: Context) {
        if (v != null || !Settings.canDrawOverlays(c)) return
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#CC00B8D4")); setStroke(3, Color.WHITE) }
        val view = View(c).apply { background = d }
        val lp = WindowManager.LayoutParams(110, 110, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> { lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt(); wm.updateViewLayout(view, lp) }
            }; true
        }
        wm.addView(view, lp); v = view
    }
    fun hide(c: Context) { v?.let { (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }; v = null }
}
