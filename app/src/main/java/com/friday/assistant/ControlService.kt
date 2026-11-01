package com.friday.assistant
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** The assistant's "hands": taps, types and scrolls inside any app, silently. */
object Foreground { @Volatile var pkg: String? = null }
/** Friday switches itself off while a payment / banking app is open. */
object Privacy {
    @Volatile var paused = false
    private val keys = listOf("phonepe", "paytm", "paisa", "bhim", "upi", "bank", "mobikwik", "freecharge", "razorpay", "amazonpay", "cred.club")
    fun isPayment(p: String) = keys.any { p.lowercase().contains(it) }
}

class ControlService : AccessibilityService() {
    companion object { var inst: ControlService? = null }
    override fun onServiceConnected() { inst = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p.contains("inputmethod") || p.contains("keyboard") || p == "com.android.systemui") return
        Foreground.pkg = p
        val was = Privacy.paused; Privacy.paused = Privacy.isPayment(p)
        if (was != Privacy.paused) Bus.post("info", if (Privacy.paused) "Paused: payment app open" else "Resumed")
    }
    override fun onInterrupt() {}
    override fun onDestroy() { inst = null; super.onDestroy() }

    private fun all(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo> = mutableListOf()): MutableList<AccessibilityNodeInfo> {
        if (n == null) return out; out.add(n); for (i in 0 until n.childCount) all(n.getChild(i), out); return out
    }
    private fun label(x: AccessibilityNodeInfo) = ((x.text ?: "").toString() + " " + (x.contentDescription ?: "")).lowercase()
    fun nodes() = all(rootInActiveWindow)

    fun clickText(vararg words: String): Boolean {
        val n = nodes().firstOrNull { x -> val t = label(x); t.isNotBlank() && words.any { t.contains(it.lowercase()) } } ?: return false
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        return (c ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK) || tapCenter(n)
    }
    /** nth (1-based) node matching words, e.g. "play the third video". */
    fun clickNth(nth: Int, vararg words: String): Boolean {
        val l = nodes().filter { x -> val t = label(x); words.any { t.contains(it) } }
        return l.getOrNull(nth - 1)?.let { tapCenter(it) } ?: false
    }
    fun tapCenter(n: AccessibilityNodeInfo): Boolean { val r = Rect(); n.getBoundsInScreen(r); return tap(r.centerX().toFloat(), r.centerY().toFloat()) }
    fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
    }
    /** up=true scrolls the content down (finger moves up). */
    fun swipe(up: Boolean): Boolean {
        val w = resources.displayMetrics.widthPixels; val h = resources.displayMetrics.heightPixels
        val p = Path().apply { if (up) { moveTo(w / 2f, h * .75f); lineTo(w / 2f, h * .25f) } else { moveTo(w / 2f, h * .25f); lineTo(w / 2f, h * .75f) } }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 250)).build(), null, null)
    }
    fun typeText(text: String, append: Boolean = false): Boolean {
        val f = nodes().firstOrNull { it.isEditable && it.isFocused } ?: nodes().firstOrNull { it.isEditable } ?: return false
        f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val cur = if (append) (f.text ?: "").toString() else ""
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + text) })
    }
    fun focusEditable(): Boolean { val f = nodes().firstOrNull { it.isEditable } ?: return false; return f.performAction(AccessibilityNodeInfo.ACTION_CLICK) || tapCenter(f) }
    fun imeEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val f = nodes().firstOrNull { it.isEditable && it.isFocused } ?: nodes().firstOrNull { it.isEditable } ?: return false
        return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }
    fun swipeH(left: Boolean): Boolean {
        val w = resources.displayMetrics.widthPixels; val h = resources.displayMetrics.heightPixels
        val p = Path().apply { if (left) { moveTo(w * .8f, h / 2f); lineTo(w * .2f, h / 2f) } else { moveTo(w * .2f, h / 2f); lineTo(w * .8f, h / 2f) } }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 250)).build(), null, null)
    }
    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
}
