package com.friday.assistant
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.AlarmClock
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/** Turns recognised text into actions. Add new commands by adding a `when` branch. */
class CommandRouter(private val ctx: Context, private val prefs: Prefs, private val say: (String) -> Unit, private val setOnMode: (Boolean) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val a get() = ControlService.inst
    private var torch = false
    private var newsTitles = listOf<String>()
    private val nums = mapOf("first" to 1, "one" to 1, "second" to 2, "two" to 2, "third" to 3, "three" to 3, "fourth" to 4, "four" to 4, "fifth" to 5, "five" to 5)

    private fun delay(ms: Long, f: () -> Unit) = ui.postDelayed(f, ms)
    private fun launch(i: Intent) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { ctx.startActivity(i) } catch (e: Exception) { say("I could not open that") } }

    fun handle(raw: String) {
        val t = raw.lowercase().trim()
        when {
            t == "go on mode" || t.startsWith("on mode") -> { setOnMode(true); say("Yes ${prefs.callMe}, on mode") }
            t == "go off mode" || t.startsWith("off mode") -> { setOnMode(false); say("Off mode") }

            // ---- screen control inside any app ----
            t == "next" -> if (!a!!.clickText("next")) a!!.swipe(true)        // YouTube Next button, else next Short
            t == "previous" || t == "back video" -> if (!a!!.clickText("previous")) a!!.swipe(false)
            t == "pause" || t == "play" || t == "resume" -> a?.clickText("pause", "play")
            t.contains("scroll down") -> a?.swipe(true)
            t.contains("scroll up") -> a?.swipe(false)
            t == "go back" || t == "back" -> a?.back()
            t == "go home" || t == "home" -> a?.home()
            t.startsWith("play the ") && nums.keys.any { t.contains(it) } && t.contains("video") -> {
                val n = nums.entries.first { t.contains(it.key) }.value; a?.clickNth(n, "play video", "views", "ago")
            }

            // ---- YouTube ----
            t.startsWith("play ") && t.endsWith("on youtube") -> youtube(t.removePrefix("play ").removeSuffix("on youtube").trim(), play = true)
            t.startsWith("search ") && t.contains("on youtube") -> youtube(t.removePrefix("search ").replace("on youtube", "").trim(), play = false)

            // ---- WhatsApp & general app flow ----
            t.startsWith("open ") && t.endsWith(" chat") -> {
                val who = t.removePrefix("open ").removeSuffix(" chat").trim()
                a?.clickText("search"); delay(700) { a?.typeText(who) }; delay(1600) { a?.clickNth(1, who) }
            }
            t == "write message" || t == "type message" -> { a?.focusEditable(); setOnMode(true); say("Say your message") }
            t.startsWith("write ") || t.startsWith("type ") -> a?.typeText(t.substringAfter(" "), append = true)
            t == "send" -> a?.clickText("send")
            t.startsWith("search ") -> { a?.focusEditable(); delay(500) { a?.typeText(t.removePrefix("search ")) }; delay(1200) { a?.clickText("search") } }
            t.startsWith("open ") -> openApp(t.removePrefix("open ").trim())

            // ---- things that work with the screen off ----
            t.contains("flashlight on") || t.contains("torch on") -> torch(true)
            t.contains("flashlight off") || t.contains("torch off") -> torch(false)
            t.startsWith("play music") || t.contains("play my music") -> launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
            t.startsWith("volume up") -> (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            t.startsWith("volume down") -> (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            t.startsWith("call ") -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("tel:")))   // extend: contact lookup
            t.startsWith("set alarm") -> launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            t.contains("news") && (t.contains("today") || t.contains("latest")) -> news()
            t.startsWith("tell me about") && newsTitles.isNotEmpty() && t.contains("news") -> newsDetail(t)
            t.startsWith("what is ") && Regex("[0-9]").containsMatchIn(t) -> calc(t)
            else -> ask(t)
        }
    }

    private fun youtube(q: String, play: Boolean) {
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"))).setPackage("com.google.android.youtube"))
        if (play) delay(3500) { a?.clickNth(1, "play video", "views", "ago") }
    }

    private fun openApp(name: String) {
        val pm = ctx.packageManager
        val li = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val m = li.firstOrNull { it.loadLabel(pm).toString().lowercase() == name } ?: li.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(name) }
        m?.let { launch(pm.getLaunchIntentForPackage(it.activityInfo.packageName)!!) } ?: say("I could not find $name")
    }

    private fun torch(on: Boolean) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try { cm.setTorchMode(cm.cameraIdList[0], on); torch = on } catch (e: Exception) { say("Torch not available") }
    }

    private fun calc(t: String) {
        val e = t.removePrefix("what is ").replace("plus", "+").replace("minus", "-").replace("times", "*").replace("multiplied by", "*")
            .replace("into", "*").replace("x", "*").replace("divided by", "/").replace("by", "/").replace(" ", "")
        try { say("It is " + fmt(Eval.run(e))) } catch (ex: Exception) { say("I could not calculate that") }
    }
    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else "%.4f".format(d).trimEnd('0')

    /** Headlines from Google News RSS (India edition). */
    private fun news() = thread {
        try {
            val xml = URL("https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en").readText()
            newsTitles = Regex("<item>.*?<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1].replace("&amp;", "and").replace("&quot;", "") }.take(6).toList()
            say("Today's headlines. " + newsTitles.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString(". "))
        } catch (e: Exception) { say("I could not fetch the news") }
    }
    private fun newsDetail(t: String) {
        val n = nums.entries.firstOrNull { t.contains(it.key) }?.value ?: 1
        ask(newsTitles.getOrNull(n - 1) ?: return)
    }

    /** General questions: DuckDuckGo instant answer, then Wikipedia summary as fallback. Swap in an LLM API here for richer answers. */
    private fun ask(q: String) = thread {
        try {
            val e = URLEncoder.encode(q.replace(Regex("^(how to|what is|who is|tell me about)\\s+"), ""), "UTF-8")
            val ddg = JSONObject(URL("https://api.duckduckgo.com/?q=$e&format=json&no_html=1&skip_disambig=1").readText()).optString("AbstractText")
            if (ddg.isNotBlank()) { say(ddg); return@thread }
            val s = JSONObject(URL("https://en.wikipedia.org/api/rest_v1/page/summary/$e").readText()).optString("extract")
            say(if (s.isNotBlank()) s else "I did not find anything for that")
        } catch (ex: Exception) { say("I could not find that online") }
    }
}

object Eval {   // tiny + - * / parser
    fun run(s: String): Double { var p = 0
        fun num(): Double { val st = p; while (p < s.length && (s[p].isDigit() || s[p] == '.')) p++; return s.substring(st, p).toDouble() }
        fun term(): Double { var v = num(); while (p < s.length && (s[p] == '*' || s[p] == '/')) { val o = s[p++]; val r = num(); v = if (o == '*') v * r else v / r }; return v }
        var v = term(); while (p < s.length && (s[p] == '+' || s[p] == '-')) { val o = s[p++]; val r = term(); v = if (o == '+') v + r else v - r }; return v }
}
