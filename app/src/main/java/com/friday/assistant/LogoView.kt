package com.friday.assistant
import android.content.Context
import android.graphics.*
import android.view.View

/** Friday logo: gradient orb with an F. state 0 = listening, 1 = awake, 2 = working. */
class LogoView(c: Context) : View(c) {
    var state = 0
        set(v) { field = v; postInvalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val r = w / 2f
        val cols = when (state) {
            1 -> intArrayOf(0xFFFFD54F.toInt(), 0xFFFF6F00.toInt())
            2 -> intArrayOf(0xFF69F0AE.toInt(), 0xFF00897B.toInt())
            else -> intArrayOf(0xFF00E5FF.toInt(), 0xFF651FFF.toInt())
        }
        p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, 0f, w, w, cols, null, Shader.TileMode.CLAMP)
        cv.drawCircle(r, r, r * 0.92f, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.05f; p.color = 0x99FFFFFF.toInt()
        cv.drawCircle(r, r, r * 0.78f, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = w * 0.52f
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); p.textAlign = Paint.Align.CENTER
        cv.drawText("F", r, r - (p.ascent() + p.descent()) / 2, p)
    }
}
