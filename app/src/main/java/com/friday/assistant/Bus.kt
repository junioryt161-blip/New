package com.friday.assistant
import android.os.Handler
import android.os.Looper

/** Shares what Friday heard / answered with the app screen and the floating logo. */
object Bus {
    val lines = ArrayDeque<String>()
    var onLine: (() -> Unit)? = null
    private val h = Handler(Looper.getMainLooper())
    fun post(kind: String, text: String) {
        val tag = when (kind) { "heard" -> "🎤 "; "reply" -> "🤖 "; "ear" -> "👂 "; else -> "ℹ️ " }
        synchronized(lines) { lines.addLast(tag + text); while (lines.size > 30) lines.removeFirst() }
        h.post { onLine?.invoke() }
        if (kind == "heard" || kind == "reply") OverlayBubble.caption(text)
    }
}
