package com.friday.assistant
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class CommandRouter(private val ctx: Context, private val prefs: Prefs, private val say: (String) -> Unit, private val setOnMode: (Boolean) -> Unit) {
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var lastIds = listOf<String>()
    private var newsTitles = listOf<String>()
    private val ordinals = mapOf("first" to 1, "1st" to 1, "second" to 2, "2nd" to 2, "third" to 3, "3rd" to 3, "fourth" to 4, "4th" to 4, "fifth" to 5, "5th" to 5)
    private val pk = mapOf("youtube" to "com.google.android.youtube", "whatsapp" to "com.whatsapp", "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm", "maps" to "com.google.android.apps.maps", "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana", "spotify" to "com.spotify.music", "telegram" to "org.telegram.messenger",
        "photos" to "com.google.android.apps.photos", "phonepe" to "com.phonepe.app", "paytm" to "net.one97.paytm",
        "gpay" to "com.google.android.apps.nbu.paisa.user", "playstore" to "com.android.vending")

    private fun launch(i: Intent): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Accessibility service context is allowed to start apps from the background on more phones
        for (c in listOfNotNull<Context>(ControlService.inst, ctx)) { try { c.startActivity(i); return true } catch (e: Exception) { Bus.post("info", "Open failed: ${e.javaClass.simpleName}") } }
        return false
    }
    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }
    private val verbs = listOf("open", "launch", "play", "search", "pause", "resume", "next", "previous", "scroll", "volume", "flashlight", "torch")
    /** Fix common mis-hearings so "flash light", "you tube", "opan" still work. */
    private fun normalize(raw: String): String {
        var t = raw.lowercase().trim().replace("you tube", "youtube").replace("whats app", "whatsapp").replace("what's app", "whatsapp")
            .replace("flash light", "flashlight").replace("flashlite", "flashlight").replace("flash like", "flashlight").replace("flesh light", "flashlight")
            .replace("torch light", "torch").replace("tourch", "torch").replace("switch on", "turn on").replace("switch off", "turn off")
        val tok = t.split(" ").toMutableList()
        for (i in 0 until minOf(2, tok.size)) { val w = tok[i]; if (w.length >= 4 && w !in verbs) verbs.firstOrNull { lev(w, it) <= 1 }?.let { tok[i] = it } }
        return tok.joinToString(" ")
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun acc(): ControlService? {
        ControlService.inst?.let { return it }
        say("Please turn on Friday in accessibility settings"); launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return null
    }
    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun handle(raw: String, allowAsk: Boolean = true) {
        val t = normalize(raw)
        // ---- modes ----
        if (t.startsWith("go on mode") || t == "on mode") { setOnMode(true); return say("Yes ${prefs.callMe}, on mode") }
        if (t.startsWith("go off mode") || t == "off mode") { setOnMode(false); return say("Off mode") }
        // ---- calculator (spoken numbers become digits) ----
        calcOrNull(t)?.let { return say("It is $it") }
        // ---- YouTube ----
        Regex("^(?:open youtube(?: and)? )?(play|search)\\s+(.+?)(?:\\s+(?:on|in|from)\\s+youtube)?$").find(t)?.let { m ->
            val q = m.groupValues[2]
            val isPlay = m.groupValues[1] == "play"
            when {
                q.startsWith("the ") && ordinals.keys.any { q.contains(it) } -> playNth(q)
                q == "music" || q == "my music" || q == "songs" -> { if (!launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))) say("No music app found"); return }
                isPlay || t.contains("youtube") -> youtube(q, isPlay)
                else -> { say("Searching $q"); launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + enc(q)))) }
            }
            return
        }
        Regex("^(?:play|open)\\s+(?:the\\s+)?(first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th)\\s+(?:video|one|result)").find(t)?.let { return playNth(t) }
        // ---- playback keys (work in YouTube, Spotify, music apps) ----
        when (t) {
            "pause", "play", "resume", "stop video", "pause video", "play video" -> return mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next", "next video", "next song" -> return mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous", "previous video", "previous song" -> return mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "next short", "scroll down", "swipe up" -> return run { acc()?.swipe(true) }.let { }
            "previous short", "scroll up", "swipe down" -> return run { acc()?.swipe(false) }.let { }
            "go back", "back" -> return run { acc()?.back() }.let { }
            "go home", "home" -> return run { acc()?.home() }.let { }
            "volume up" -> return audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume down" -> return audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "send", "send message" -> return run { acc()?.clickText("send") }.let { }
            "write message", "type message" -> return run { acc()?.focusEditable(); say("Say your message") }.let { setOnMode(true) }
        }
        if (t.startsWith("write ") || t.startsWith("type ")) return run { acc()?.typeText(t.substringAfter(" "), append = true) }.let { }
        if (t.startsWith("open ") && t.endsWith(" chat")) {
            val who = t.removePrefix("open ").removeSuffix(" chat").trim()
            val a = acc() ?: return
            a.clickText("search"); Thread.sleep(800); a.typeText(who); Thread.sleep(1500); a.clickNth(1, who); return
        }
        // ---- open apps ----
        if (t.startsWith("open ") || t.startsWith("launch ") || t.startsWith("start ")) return openApp(t.substringAfter(" ").trim())
        // ---- things that work with the screen off ----
        if (t.contains("flashlight") || t.contains("torch")) {
            val off = Regex("\\b(off|close|stop)\\b").containsMatchIn(t)
            return torch(if (off) false else if (Regex("\\bon\\b").containsMatchIn(t)) true else !torchOn)
        }
        if (verbs.contains(t.split(" ")[0]).not() && pk.containsKey(t.replace(" ", ""))) return openApp(t)
        if (t.contains("time")) return say("It is " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date()))
        if (t.startsWith("set alarm")) { say("Opening alarms"); launch(Intent(AlarmClock.ACTION_SHOW_ALARMS)); return }
        if (t.contains("news") && (t.contains("today") || t.contains("latest") || t.contains("headline"))) { news(); return }
        if (t.startsWith("tell me about") && newsTitles.isNotEmpty() && t.contains("news")) {
            val n = ordinals.entries.firstOrNull { t.contains(it.key) }?.value ?: 1; ask(newsTitles.getOrNull(n - 1) ?: return); return
        }
        if (allowAsk) ask(t) else Bus.post("info", "Ignored: $t")
    }

    // ---------- YouTube: find the video and open it directly ----------
    private fun youtube(q: String, play: Boolean) {
        say(if (play) "Playing $q" else "Searching $q on YouTube")
        thread {
            try {
                val ids = ytSearch(q); lastIds = ids
                if (play && ids.isNotEmpty()) playId(ids[0])
                else launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc(q))).setPackage("com.google.android.youtube"))
            } catch (e: Exception) {
                Bus.post("info", "YouTube lookup failed: ${e.javaClass.simpleName}")
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc(q))))
            }
        }
    }
    private fun playNth(t: String) {
        val n = ordinals.entries.firstOrNull { t.contains(it.key) }?.value ?: 1
        val id = lastIds.getOrNull(n - 1) ?: return say("Search for something on YouTube first")
        say("Playing video $n"); playId(id)
    }
    private fun playId(id: String) {
        if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id")).setPackage("com.google.android.youtube")))
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")))
    }
    private fun ytSearch(q: String): List<String> {
        val c = URL("https://www.youtube.com/results?hl=en&search_query=" + enc(q)).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
        c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9"); c.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
        c.connectTimeout = 8000; c.readTimeout = 8000
        val html = c.inputStream.bufferedReader().readText()
        val a = Regex("\"videoRenderer\":\\{\"videoId\":\"([\\w-]{11})\"").findAll(html).map { it.groupValues[1] }.distinct().toList()
        return if (a.isNotEmpty()) a else Regex("\"videoId\":\"([\\w-]{11})\"").findAll(html).map { it.groupValues[1] }.distinct().toList()
    }

    // ---------- apps ----------
    private fun openApp(spoken: String) {
        fun norm(x: String) = x.lowercase().replace(Regex("[^a-z0-9]"), "")
        val name = norm(spoken.removePrefix("the ").removePrefix("my "))
        val pm = ctx.packageManager
        if (name == "settings") { say("Opening settings"); launch(Intent(Settings.ACTION_SETTINGS)); return }
        val li = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { norm(it.loadLabel(pm).toString()) to it.activityInfo.packageName }
        val tol = maxOf(1, name.length / 4)
        var pkg: String? = pk.entries.firstOrNull { it.key == name || (name.length >= 5 && lev(it.key, name) <= 1) }?.value?.takeIf { pm.getLaunchIntentForPackage(it) != null }
        if (pkg == null) pkg = li.firstOrNull { it.first == name }?.second
        if (pkg == null && name.length >= 3) pkg = li.firstOrNull { it.first.contains(name) || (name.contains(it.first) && it.first.length >= 4) }?.second
        if (pkg == null) pkg = li.filter { lev(it.first, name) <= tol }.minByOrNull { lev(it.first, name) }?.second
        val intent = pkg?.let { pm.getLaunchIntentForPackage(it) }
        if (intent != null && launch(intent)) say("Opening $spoken") else say("I could not open $spoken")
    }

    private var torchOn = false
    private fun torch(on: Boolean) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val id = cm.cameraIdList.firstOrNull { val c = cm.getCameraCharacteristics(it)
                c.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK }
                ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return say("This phone has no flashlight")
            cm.setTorchMode(id, on); torchOn = on; say(if (on) "Flashlight on" else "Flashlight off")
        } catch (e: Exception) { say("Flashlight failed: ${e.javaClass.simpleName}") }
    }

    // ---------- calculator ----------
    private fun calcOrNull(t: String): String? {
        var e = Words.toDigits(t).replace(Regex("^(what is|what's|calculate|tell me|how much is)\\s*"), "")
            .replace("multiplied by", "*").replace("divided by", "/").replace("times", "*").replace("into", "*")
            .replace("plus", "+").replace("minus", "-").replace("over", "/").replace(Regex("\\bx\\b"), "*").replace(" ", "")
        if (!Regex("^[0-9.+\\-*/]+$").matches(e) || !Regex("[0-9][+\\-*/][0-9]").containsMatchIn(e)) return null
        return try { val r = Eval.run(e); if (r % 1.0 == 0.0) r.toLong().toString() else "%.4f".format(r).trimEnd('0') } catch (x: Exception) { null }
    }

    // ---------- online answers ----------
    private fun news() = thread {
        try {
            val xml = URL("https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en").readText()
            newsTitles = Regex("<item>.*?<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1].replace("&amp;", "and").replace("&quot;", "") }.take(6).toList()
            say("Today's headlines. " + newsTitles.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString(". "))
        } catch (e: Exception) { say("I could not fetch the news") }
    }
    private fun ask(q: String) = thread {
        try {
            val e = enc(q.replace(Regex("^(how to|what is|who is|tell me about)\\s+"), ""))
            val ddg = JSONObject(URL("https://api.duckduckgo.com/?q=$e&format=json&no_html=1&skip_disambig=1").readText()).optString("AbstractText")
            if (ddg.isNotBlank()) return@thread say(ddg)
            val s = JSONObject(URL("https://en.wikipedia.org/api/rest_v1/page/summary/$e").readText()).optString("extract")
            say(if (s.isNotBlank()) s else "I did not find anything for that")
        } catch (ex: Exception) { say("Sorry, I did not understand: $q") }
    }
}

object Words {
    private val u = listOf("zero","one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen").withIndex().associate { it.value to it.index }
    private val tn = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private fun isNum(w: String) = u.containsKey(w) || tn.containsKey(w)
    fun toDigits(s: String): String {
        val tok = s.split(" ").filter { it.isNotEmpty() }; val out = mutableListOf<String>(); var i = 0
        while (i < tok.size) {
            if (!isNum(tok[i])) { out.add(tok[i]); i++; continue }
            var total = 0; var cur = 0; var j = i
            loop@ while (j < tok.size) {
                val x = tok[j]
                when {
                    u.containsKey(x) -> cur += u[x]!!
                    tn.containsKey(x) -> cur += tn[x]!!
                    x == "hundred" -> cur = (if (cur == 0) 1 else cur) * 100
                    x == "thousand" -> { total += (if (cur == 0) 1 else cur) * 1000; cur = 0 }
                    x == "lakh" -> { total += (if (cur == 0) 1 else cur) * 100000; cur = 0 }
                    x == "and" && j + 1 < tok.size && isNum(tok[j + 1]) -> {}
                    else -> break@loop
                }
                j++
            }
            out.add((total + cur).toString()); i = j
        }
        return out.joinToString(" ")
    }
}

object Eval {
    fun run(s: String): Double { var p = 0
        fun num(): Double { val st = p; while (p < s.length && (s[p].isDigit() || s[p] == '.')) p++; return s.substring(st, p).toDouble() }
        fun term(): Double { var v = num(); while (p < s.length && (s[p] == '*' || s[p] == '/')) { val o = s[p++]; val r = num(); v = if (o == '*') v * r else v / r }; return v }
        var v = term(); while (p < s.length && (s[p] == '+' || s[p] == '-')) { val o = s[p++]; val r = term(); v = if (o == '+') v + r else v - r }; return v }
}
