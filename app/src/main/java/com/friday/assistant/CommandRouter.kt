package com.friday.assistant
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Understands a command in 3 steps:  clean (Hindi/Hinglish/synonyms) -> resolve (to a small command language) -> execute.
 * Anything it cannot resolve can go to an online AI (optional key). Understood commands are remembered.
 */
class CommandRouter(private val ctx: Context, private val prefs: Prefs, private val say: (String) -> Unit, private val setOnMode: (Boolean) -> Unit) {
    init { Learned.init(ctx) }
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var lastIds = listOf<String>(); private var lastIdsAt = 0L
    private var newsTitles = listOf<String>()
    private var torchOn = false
    @Volatile var keepPaused = false
    val asyncBusy = java.util.concurrent.atomic.AtomicInteger(0)
    private var newsLinks = listOf<String>(); private var newsDescs = listOf<String>()
    private val history = ArrayList<Pair<String, String>>()
    private val ordinals = mapOf("first" to 1, "1st" to 1, "second" to 2, "2nd" to 2, "third" to 3, "3rd" to 3, "fourth" to 4, "4th" to 4, "fifth" to 5, "5th" to 5)
    private val pk = mapOf("youtube" to "com.google.android.youtube", "whatsapp" to "com.whatsapp", "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm", "maps" to "com.google.android.apps.maps", "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana", "spotify" to "com.spotify.music", "telegram" to "org.telegram.messenger",
        "photos" to "com.google.android.apps.photos", "phonepe" to "com.phonepe.app", "paytm" to "net.one97.paytm",
        "gpay" to "com.google.android.apps.nbu.paisa.user", "playstore" to "com.android.vending")

    // ---------------- language clean-up: Hindi / Hinglish / synonyms / mishearings ----------------
    private val latin = listOf(
        "you tube" to "youtube", "yu tube" to "youtube", "u tube" to "youtube", "whats app" to "whatsapp", "whatsap" to "whatsapp", "watsapp" to "whatsapp",
        "flash light" to "torch", "flashlight" to "torch", "flashlite" to "torch", "flash like" to "torch", "torch light" to "torch", "tourch" to "torch", "roshni" to "torch", "batti" to "torch",
        "in to" to "into", "whats" to "what is", "what s" to "what is",
        "khol do" to "open", "kholiye" to "open", "kholo" to "open", "khol" to "open",
        "baja do" to "play", "bajao" to "play", "chala do" to "play", "chalao" to "play", "laga do" to "play", "lagao" to "play",
        "dhundho" to "search", "dhundo" to "search", "khojo" to "search", "serach" to "search", "sarch" to "search",
        "chalu" to "on", "jala do" to "on", "jalao" to "on", "bujha do" to "off", "bujhao" to "off", "bandh" to "off", "band" to "off",
        "agla" to "next", "aage" to "next", "pichla" to "previous", "peeche" to "previous", "ruk jao" to "pause", "ruko" to "pause",
        "awaaz badhao" to "volume up", "awaz badhao" to "volume up", "volume badhao" to "volume up",
        "awaaz kam karo" to "volume down", "awaz kam karo" to "volume down", "volume kam karo" to "volume down",
        "photo kheencho" to "take photo", "photo khicho" to "take photo", "photo kheech" to "take photo", "photo lo" to "take photo", "tasveer" to "photo",
        "selfy" to "selfie", "selfi" to "selfie", "gaana" to "song", "gana" to "song", "naya" to "new",
        "kar do" to "", "karo" to "", "kijiye" to "", "kijiyega" to "")
    private val latinRx = latin.map { Regex("\\b" + Regex.escape(it.first) + "\\b") to it.second }
    private val deva = listOf("खोलिए" to "open", "खोलो" to "open", "चला दो" to "play", "चलाओ" to "play", "बजाओ" to "play", "लगाओ" to "play",
        "बंद करो" to "off", "बंद" to "off", "चालू करो" to "on", "चालू" to "on", "टॉर्च" to "torch", "टार्च" to "torch", "यूट्यूब" to "youtube",
        "व्हाट्सएप" to "whatsapp", "वॉट्सऐप" to "whatsapp", "कैमरा" to "camera", "कॉल" to "call", "खोजो" to "search", "ढूंढो" to "search", "सर्च" to "search",
        "अगला" to "next", "पिछला" to "previous", "रुको" to "pause", "गाना" to "song", "मम्मी" to "mummy", "पापा" to "papa", "सेल्फी" to "selfie",
        "फोटो" to "photo", "खींचो" to "take", "समय" to "time", "टाइम" to "time", "खबर" to "news", "समाचार" to "news", "न्यूज" to "news", "ऑन" to "on", "ऑफ" to "off",
        "आवाज बढ़ाओ" to "volume up", "आवाज कम करो" to "volume down")
    private val filler = Regex("\\b(?:please|kindly|hey|okay|ok|just|can you|could you|will you|would you|for me|mujhe|mere liye|zara|jara|jaldi|abhi|ek baar)\\b")

    fun clean(raw: String): String {
        var t = raw.lowercase().trim().replace(Regex("[,!?;:\"“”।]"), " ").replace("'", "").replace("’", "")
        for ((a, b) in deva) t = t.replace(a, " $b ")
        for ((r, w) in latinRx) t = t.replace(r, w)
        return t.replace(filler, " ").replace(Regex("\\s+"), " ").trim()
    }

    // ---------------- resolve: text -> command list ----------------
    private val starts = setOf("open", "play", "search", "call", "take", "click", "torch", "scroll", "next", "previous", "pause", "volume", "send", "type", "write",
        "go", "tell", "what", "set", "stop", "turn", "photo", "selfie", "camera", "select", "tap", "press", "swipe", "resume", "find", "launch")
    private fun has(t: String, vararg w: String) = w.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(t) }

    fun resolve(raw: String): List<String> {
        val t = clean(raw); if (t.isEmpty()) return emptyList()
        Learned.get(t)?.let { return it.split("||") }
        val out = mutableListOf<String>()
        for (p in splitChain(t)) out += (parseOne(p) ?: "unknown $p")
        // "open camera" + "take photo"  ->  just take the photo; "open youtube" + "play x" -> just play
        val r = mutableListOf<String>()
        var skip = false
        for ((i, c) in out.withIndex()) {
            if (skip) { skip = false; continue }
            val nx = out.getOrNull(i + 1)
            if (nx != null && c.startsWith("camera ") && nx == "photo back") { r += "photo " + c.removePrefix("camera "); skip = true; continue }
            if (nx != null && ((c == "open camera" && nx.startsWith("photo")) || (c == "open youtube" && (nx.startsWith("play ") || nx.startsWith("yt_search "))))) continue
            r += c
        }
        return r
    }
    fun understands(raw: String): Boolean { val r = resolve(raw); return r.isNotEmpty() && r.none { it.startsWith("unknown ") || it.startsWith("ask ") } }

    private fun splitChain(t: String): List<String> {
        val out = mutableListOf<String>()
        for (s in t.split(Regex("\\s+(?:and then|then|phir|uske baad|aur phir|and|aur)\\s+")).map { it.trim() }.filter { it.isNotEmpty() }) {
            if (out.isNotEmpty() && s.split(" ")[0] !in starts) out[out.size - 1] = out.last() + " and " + s else out += s
        }
        return out
    }

    private fun isChat(t: String) = Regex("^(?:hi|hello|hello there|namaste|namaskar|good morning|good afternoon|good evening|good night|how are you|how are you doing|how do you do|what is up|who are you|what is your name|what are you|thank you|thanks|i love you|tell me a joke|joke|tell me something funny|are you there|do you hear me|can you hear me|how is your day|what can you do|help|bye|goodbye|good bye|you are awesome|you are great|you are smart|kaise ho|aap kaise ho|tum kaise ho|kya haal hai)$").matches(t)

    private fun parseOne(t: String): String? {
        if (t.isEmpty()) return null
        val tok = t.split(" "); val lead = tok[0]
        Regex("^(?:go )?(on|off) mode$").find(t)?.let { return "mode " + it.groupValues[1] }
        if (Regex("^(?:stop|quiet|be quiet|cancel|enough|shut up|silence)(?: talking)?$").matches(t)) return "silence"
        if (isChat(t)) return "chat $t"
        if (lead != "play" && lead != "search" && tok.size <= 7 && (has(t, "torch") || (has(t, "light", "lights", "lamp") && has(t, "on", "off")))) {
            val off = has(t, "off", "close", "disable", "stop"); val on = has(t, "on", "enable", "start")
            return if (off) "torch off" else if (on) "torch on" else "torch toggle"
        }
        if (lead != "play" && has(t, "camera") && has(t, "front", "selfie", "rear", "back") && !has(t, "photo", "picture", "pic", "click", "take", "snap", "capture"))
            return if (has(t, "front", "selfie")) "camera front" else "camera back"
        if (lead != "play" && has(t, "photo", "picture", "pic", "selfie", "snap") && (has(t, "take", "click", "capture", "snap", "selfie") || t == "photo"))
            return if (has(t, "selfie", "front")) "photo front" else "photo back"
        Regex("^(?:call|dial|phone|ring)\\s+(.+)$").find(t)?.let { return "call " + it.groupValues[1].removePrefix("to ").removeSuffix(" ko").trim() }
        Regex("^(.+?)\\s+ko\\s+(?:call|phone|dial)$").find(t)?.let { return "call " + it.groupValues[1] }
        if (t != "search" && Regex("^(?:open )?(?:the )?search(?: bar| box)?$").matches(t)) return "searchbar"
        if (t == "play music" || t == "play my music" || t == "play songs") return "music"
        Regex("^(?:play|open|select|pick|choose)\\s+(?:the\\s+)?(first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th)(?:\\s+\\w+)?$").find(t)?.let { return "nth " + ordinals[it.groupValues[1]] }
        if (Regex("^(?:pause|pause video|stop video|pause song|pause music|stop music)$").matches(t)) return "media pause"
        if (Regex("^(?:play|resume|play video|play song|continue|resume video)$").matches(t)) return "media play"
        Regex("^(?:search|find|look up|look for)\\s+(?:for\\s+)?(.+?)\\s+(?:on|in|at)\\s+youtube$").find(t)?.let { return "yt_search " + it.groupValues[1] }
        Regex("^youtube\\s+(?:search|par search|pe search)\\s+(.+)$").find(t)?.let { return "yt_search " + it.groupValues[1] }
        var q: String? = Regex("^(?:play|put on)\\s+(.+)$").find(t)?.groupValues?.get(1)
        if (q == null) q = Regex("^youtube\\s+(?:pe|par|me|mein|on)\\s+(.+?)(?:\\s+play)?$").find(t)?.groupValues?.get(1)
        if (q == null) q = Regex("^(.+?\\s(?:song|songs|video|videos|trailer))\\s+(?:on|in|pe|par)\\s+youtube$").find(t)?.groupValues?.get(1)
        if (q == null) q = Regex("^(.+?)\\s+play$").find(t)?.groupValues?.get(1)
        if (q != null) {
            val s = q.replace(Regex("\\s+(?:on|in|from|at|pe|par)\\s+youtube$"), "").replace(Regex("^(?:the|a)\\s+"), "").trim()
            if (s.isNotEmpty()) return "play $s"
        }
        when {
            Regex("^(?:next|next video|next song|next track)$").matches(t) -> return "media next"
            Regex("^(?:previous|previous video|previous song|previous track)$").matches(t) -> return "media prev"
            Regex("^(?:next short|next reel|scroll down|swipe up|scroll)$").matches(t) -> return "scroll down"
            Regex("^(?:previous short|previous reel|scroll up|swipe down)$").matches(t) -> return "scroll up"
            t == "swipe left" -> return "swipe left"
            t == "swipe right" -> return "swipe right"
            Regex("^(?:volume up|increase volume|louder|volume increase)$").matches(t) -> return "volume up"
            Regex("^(?:volume down|decrease volume|lower volume|volume decrease|quieter)$").matches(t) -> return "volume down"
            t == "send" || t == "send message" -> return "send"
            t == "back" || t == "go back" -> return "back"
            t == "home" || t == "go home" -> return "home"
            t == "write message" || t == "type message" -> return "typemode"
        }
        Regex("^(?:click|tap|press|touch)\\s+(?:on\\s+)?(.+)$").find(t)?.let { return "click " + it.groupValues[1] }
        Regex("^(?:type|write)\\s+(.+)$").find(t)?.let { return "type " + it.groupValues[1] }
        if (Regex("^(?:what is the time|time|what time is it|tell me the time|current time|what is time)$").matches(t)) return "time"
        if (Regex("^(?:date|what is the date|todays date|what is todays date|what day is it|what is the day today)$").matches(t)) return "date"
        if (has(t, "news") && Regex("(first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th|\\b[1-6]\\b)").containsMatchIn(t) && has(t, "detail", "details", "full", "more", "read", "about", "tell")) {
            val n = ordinals.entries.firstOrNull { has(t, it.key) }?.value ?: Regex("\\b([1-6])\\b").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            return "newsdetail $n"
        }
        if (has(t, "news", "headlines") && lead != "play") return "news"
        Regex("^(?:search|find|look up|look for|google)\\s+(?:for\\s+)?(.+)$").find(t)?.let { return "search " + it.groupValues[1] }
        if (Mathx.intent(t)) return "math $t"
        Regex("^(?:open|launch|start|run|go to|show)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$").find(t)?.let { return "open " + it.groupValues[1] }
        if (tok.size <= 3 && findPkg(t.removeSuffix(" app")) != null) return "open " + t.removeSuffix(" app")
        if (Regex("^(?:what|who|when|where|why|how|which|tell me|explain|define|is |are |can |kya|kaun|kab|kahan|kyun|kaise|kitna)").containsMatchIn(t)) return "ask $t"
        return null
    }

    // ---------------- main entry ----------------
    fun handle(raw: String, allowAsk: Boolean = true) {
        val cleaned = clean(raw)
        keepPaused = false
        Regex("^(?:learn|remember)(?: that)?\\s+(.+?)\\s+(?:means|mean|is)\\s+(.+)$").find(cleaned)?.let { m ->
            val v = resolve(m.groupValues[2])
            if (v.isNotEmpty() && v.none { it.startsWith("unknown ") }) { Learned.put(clean(m.groupValues[1]), v.joinToString("||")); say("Learned. When you say ${m.groupValues[1]}, I will do ${m.groupValues[2]}") }
            else say("I did not understand what to do for that")
            return
        }
        var cmds = resolve(raw)
        var viaAi = false
        if (cmds.any { it.startsWith("unknown ") }) {
            if (!allowAsk) { Bus.post("info", "Ignored: $cleaned"); return }
            val ai = aiInterpret(cleaned)
            if (ai != null && ai.isNotEmpty()) { cmds = ai; viaAi = true } else cmds = listOf("ask $cleaned")
        } else if (!allowAsk && cmds.size == 1 && cmds[0].startsWith("ask ")) { Bus.post("info", "Ignored: $cleaned"); return }
        Bus.post("info", "→ " + cmds.joinToString(" ; "))
        keepPaused = cmds.any { it.startsWith("media") || it.startsWith("play ") || it.startsWith("nth") || it.startsWith("music") || it.startsWith("yt_search") || it.startsWith("open ") || it.startsWith("photo") || it.startsWith("camera") }
        var ok = true
        for ((i, c) in cmds.withIndex()) { ok = exec(c) && ok; if (i < cmds.size - 1) Thread.sleep(if (c.startsWith("open ")) 2500 else 700) }
        val learnable = cmds.all { it.startsWith("torch") || it.startsWith("open ") || it.startsWith("media") || it.startsWith("scroll") || it.startsWith("volume") || it == "time" || it == "news" || it.startsWith("photo") }
        if (ok && (viaAi || (learnable && cleaned != cmds.joinToString(" ")))) Learned.put(cleaned, cmds.joinToString("||"))
    }

    private fun exec(c: String): Boolean {
        val sp = c.indexOf(' '); val verb = if (sp < 0) c else c.substring(0, sp); val arg = if (sp < 0) "" else c.substring(sp + 1).trim()
        return when (verb) {
            "mode" -> { setOnMode(arg == "on"); say(if (arg == "on") "Yes ${prefs.callMe}, on mode" else "Off mode"); true }
            "silence" -> true
            "torch" -> torch(if (arg == "toggle") !torchOn else arg == "on")
            "photo" -> takePhoto(arg == "front")
            "call" -> callContact(arg)
            "play" -> { youtube(arg, true); true }
            "yt_search" -> { youtube(arg, false); true }
            "nth" -> playNth(arg.toIntOrNull() ?: 1)
            "music" -> { if (!launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))) { say("No music app found"); false } else { say("Opening music"); true } }
            "media" -> {
                when (arg) {
                    "pause" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                    "play" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                    "next" -> { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); Thread.sleep(400); mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }
                    "prev" -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); Thread.sleep(400); mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }
                    else -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                }
                true
            }
            "camera" -> openCamera(arg == "front")
            "chat" -> chat(arg)
            "newsdetail" -> { newsDetail(arg.toIntOrNull() ?: 1); true }
            "scroll" -> acc()?.swipe(arg == "down") ?: false
            "swipe" -> acc()?.swipeH(arg == "left") ?: false
            "volume" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (arg == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); true }
            "searchbar" -> { val a = acc(); if (a != null && a.clickText("search")) true else { say("I could not find a search bar here"); false } }
            "search" -> if (inApp()) inAppSearch(arg) else { say("Searching $arg"); launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + enc(arg)))) }
            "click" -> { val a = acc(); if (a != null && a.clickText(arg)) true else { say("I could not find $arg on the screen"); false } }
            "type" -> acc()?.typeText(arg, append = true) ?: false
            "typemode" -> { acc()?.focusEditable(); setOnMode(true); say("Say your message, then say send"); true }
            "send" -> acc()?.clickText("send") ?: false
            "back" -> acc()?.back() ?: false
            "home" -> acc()?.home() ?: false
            "time" -> { say("It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())); true }
            "date" -> { say("Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())); true }
            "news" -> { news(); true }
            "open" -> openApp(arg)
            "math" -> { math(arg); true }
            "ask" -> { ask(arg); true }
            else -> { ask(c); true }
        }
    }

    // ---------------- helpers ----------------
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun launch(i: Intent): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (c in listOfNotNull<Context>(ControlService.inst, ctx)) { try { c.startActivity(i); return true } catch (e: Exception) { Bus.post("info", "Open failed: ${e.javaClass.simpleName}") } }
        return false
    }
    private fun acc(): ControlService? {
        ControlService.inst?.let { return it }
        say("Please turn on Friday in accessibility settings"); launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return null
    }
    private fun mediaKey(code: Int) { audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }
    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }
    private fun norm(x: String) = x.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun inApp(): Boolean {
        val p = Foreground.pkg ?: return false
        if (ControlService.inst == null || p == ctx.packageName) return false
        val home = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        return p != home
    }
    private fun inAppSearch(q: String): Boolean {
        val a = acc() ?: return false
        if (!a.clickText("search")) { say("I could not find a search box in this app"); return false }
        Thread.sleep(900)
        if (!a.typeText(q)) { say("I could not type in the search box"); return false }
        Thread.sleep(500)
        if (!a.imeEnter()) a.clickText("search")
        say("Searching $q"); return true
    }

    // ---------------- YouTube ----------------
    private fun youtube(q: String, play: Boolean) {
        say(if (play) "Playing $q" else "Searching $q on YouTube")
        thread {
            try {
                val ids = ytSearch(q); lastIds = ids; lastIdsAt = System.currentTimeMillis()
                if (play && ids.isNotEmpty()) playId(ids[0])
                else launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc(q))).setPackage("com.google.android.youtube"))
            } catch (e: Exception) {
                Bus.post("info", "YouTube lookup failed: ${e.javaClass.simpleName}")
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc(q))))
            }
        }
    }
    private fun playNth(n: Int): Boolean {
        if (lastIds.isNotEmpty() && System.currentTimeMillis() - lastIdsAt < 600000) { say("Playing video $n"); playId(lastIds.getOrNull(n - 1) ?: return false); return true }
        val a = acc() ?: return false
        return a.clickNth(n, "play video", "views", "ago")
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

    // ---------------- apps ----------------
    private fun findPkg(spoken: String): String? {
        val name = norm(spoken.removePrefix("the ").removePrefix("my ")); if (name.length < 2) return null
        val pm = ctx.packageManager
        pk.entries.firstOrNull { it.key == name || (name.length >= 5 && lev(it.key, name) <= 1) }?.value?.takeIf { pm.getLaunchIntentForPackage(it) != null }?.let { return it }
        val li = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { norm(it.loadLabel(pm).toString()) to it.activityInfo.packageName }
        li.firstOrNull { it.first == name }?.let { return it.second }
        if (name.length >= 4) li.firstOrNull { it.first.contains(name) || (name.contains(it.first) && it.first.length >= 4) }?.let { return it.second }
        val tol = if (name.length >= 6) maxOf(1, name.length / 4) else 0
        if (tol > 0) li.filter { lev(it.first, name) <= tol }.minByOrNull { lev(it.first, name) }?.let { return it.second }
        return null
    }
    private fun openApp(spoken: String): Boolean {
        if (norm(spoken) == "settings") { say("Opening settings"); return launch(Intent(Settings.ACTION_SETTINGS)) }
        val intent = findPkg(spoken)?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
        return if (intent != null && launch(intent)) { say("Opening $spoken"); true } else { say("I could not open $spoken"); false }
    }

    // ---------------- torch ----------------
    private fun torch(on: Boolean): Boolean {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val id = cm.cameraIdList.firstOrNull { val ch = cm.getCameraCharacteristics(it); ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            if (id == null) { say("This phone has no flashlight"); false } else { cm.setTorchMode(id, on); torchOn = on; say(if (on) "Flashlight on" else "Flashlight off"); true }
        } catch (e: Exception) { say("Flashlight failed: ${e.javaClass.simpleName}"); false }
    }

    // ---------------- camera: open it, pick the lens, press the shutter ----------------
    /** Camera apps label the switch button with the lens it switches TO, so we can tell which lens is active. */
    private fun openCamera(front: Boolean): Boolean {
        val a = acc() ?: return false
        say(if (front) "Opening front camera" else "Opening camera")
        val fresh = !(Foreground.pkg ?: "").contains("camera")
        if (fresh) {
            if (!launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) { say("I could not open the camera"); return false }
            Thread.sleep(2400)
        }
        val toFront = a.findNode("switch to front", "to front camera", "front camera", "selfie camera")
        val toRear = a.findNode("switch to rear", "switch to back", "to rear camera", "to back camera", "rear camera", "back camera")
        val generic = a.findNode("switch camera", "flip camera", "rotate camera", "switch cameras", "camera switch", "reverse camera", "flip", "switch")
        Bus.post("info", "Camera buttons: " + listOfNotNull(toFront?.let { "[" + a.labelOf(it) + "]" }, toRear?.let { "[" + a.labelOf(it) + "]" }, generic?.let { "[" + a.labelOf(it) + "]" }).joinToString(" "))
        if (front) {
            if (toFront != null && toRear == null) a.tapNode(toFront)
            else if (toFront == null && toRear == null && generic != null) a.tapNode(generic)
        } else if (toRear != null && toFront == null) a.tapNode(toRear)
        Thread.sleep(1300); return true
    }
    private fun takePhoto(front: Boolean): Boolean {
        val a = acc() ?: return false
        if (!openCamera(front)) return false
        say("Smile. 3, 2, 1"); Thread.sleep(3500)
        var ok = false
        for (k in 0 until 8) { if (a.clickText("shutter", "take photo", "take picture", "capture")) { ok = true; break }; Thread.sleep(500) }
        if (!ok) ok = a.tap(ctx.resources.displayMetrics.widthPixels / 2f, ctx.resources.displayMetrics.heightPixels * 0.88f)
        Thread.sleep(900); say(if (ok) "Photo taken" else "I could not press the shutter"); return ok
    }

    // ---------------- contacts & calling (stays on the phone) ----------------
    private fun callContact(name0: String): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) { say("Please allow contacts in Friday setup"); return false }
        val cands = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
            ?.use { c -> while (c.moveToNext()) cands += (c.getString(0) ?: "") to (c.getString(1) ?: "") }
        val key = norm(name0)
        val rel = listOf(listOf("mummy", "mom", "maa", "mother", "mumma", "mama", "amma"), listOf("papa", "dad", "father", "pappa", "baba"))
        val syn = rel.firstOrNull { key in it } ?: listOf(key)
        val best = cands.firstOrNull { norm(it.first) == key } ?: cands.firstOrNull { c -> syn.any { norm(c.first).contains(it) } }
            ?: cands.firstOrNull { key.length >= 3 && norm(it.first).contains(key) }
            ?: cands.minByOrNull { lev(norm(it.first), key) }?.takeIf { lev(norm(it.first), key) <= maxOf(1, key.length / 3) }
        if (best == null) { say("I could not find $name0 in your contacts"); return false }
        say("Calling ${best.first}")
        val act = if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return launch(Intent(act, Uri.parse("tel:" + Uri.encode(best.second))))
    }

    // ---------------- math ----------------
    private fun math(t: String) {
        Mathx.solve(t)?.let { return say(it) }
        val e = Mathx.expr(t)
        if (e != null) thread {
            try {
                val c = URL("https://api.mathjs.org/v4/?expr=" + enc(e)).openConnection() as HttpURLConnection; c.connectTimeout = 8000; c.readTimeout = 8000
                say("The answer is " + c.inputStream.bufferedReader().readText().trim())
            } catch (x: Exception) { ask(t) }
        } else ask(t)
    }

    // ---------------- chat ----------------
    private val jokes = listOf("Why did the phone go to school? To get smarter!", "Why was the computer cold? It left its Windows open.",
        "What do you call a sleeping bull? A bulldozer.", "Why did the smartphone need glasses? It lost all its contacts.")
    private fun chat(t: String): Boolean {
        val n = prefs.callMe
        val r: String = when {
            t.contains("how are you") || t.contains("kaise ho") || t.contains("kya haal") || t.contains("how do you do") || t.contains("how is your day") -> "I am doing great, thank you for asking $n. How are you?"
            t.contains("who are you") || t.contains("your name") || t.contains("what are you") -> "I am ${prefs.assistantName}, your voice assistant."
            t.contains("thank") -> "You are welcome $n!"
            t.contains("love you") -> "That is kind of you $n. I am happy to help you."
            t.contains("joke") || t.contains("funny") -> jokes.random()
            t.contains("what can you do") || t == "help" -> "I can open apps, play YouTube videos, control the flashlight and camera, call your contacts, do maths, read the news and chat with you."
            t.contains("bye") -> "Goodbye $n! Call me anytime."
            t.contains("morning") -> "Good morning $n! Have a great day."
            t.contains("afternoon") -> "Good afternoon $n!"
            t.contains("evening") -> "Good evening $n!"
            t.contains("night") -> "Good night $n. Sleep well!"
            t.contains("awesome") || t.contains("great") || t.contains("smart") -> "Thank you $n!"
            else -> "Hello $n! I am here. How can I help?"
        }
        if (prefs.aiKey.isNotBlank()) { thread { asyncBusy.incrementAndGet(); try { say(aiAnswer(t) ?: r) } finally { asyncBusy.decrementAndGet() } }; return true }
        say(r); return true
    }

    // ---------------- online: news, answers, optional AI ----------------
    private fun strip(x: String) = x.replace(Regex("<!\\[CDATA\\[|\\]\\]>"), "").replace(Regex("<[^>]+>"), " ").replace("&amp;", "and").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()
    private fun fetch(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
        c.connectTimeout = 9000; c.readTimeout = 10000
        return c.inputStream.bufferedReader().readText()
    }
    private fun news() { thread {
        asyncBusy.incrementAndGet()
        try {
            val items = mutableListOf<Triple<String, String, String>>()
            try {
                val xml = fetch("https://timesofindia.indiatimes.com/rssfeedstopstories.cms")
                for (m in Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).take(6)) {
                    val b = m.groupValues[1]
                    val ti = strip(Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1) ?: "")
                    val de = strip(Regex("<description>(.*?)</description>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1) ?: "")
                    val li = strip(Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1) ?: "")
                    if (ti.isNotEmpty()) items += Triple(ti, de, li)
                }
            } catch (e: Exception) {}
            if (items.isEmpty()) {
                val xml = fetch("https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en")
                for (m in Regex("<item>.*?<title>(.*?)</title>.*?<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL).findAll(xml).take(6)) items += Triple(strip(m.groupValues[1]), "", strip(m.groupValues[2]))
            }
            if (items.isEmpty()) return@thread say("I could not fetch the news")
            newsLinks = items.map { it.third }; newsDescs = items.map { it.second }
            say("Today's top news. " + items.take(5).mapIndexed { i, x -> "News ${i + 1}. ${x.first}. " + x.second.take(170) }.joinToString(" ") + " Say tell me more about news 2, or any number, for full detail.")
        } catch (e: Exception) { say("I could not fetch the news") } finally { asyncBusy.decrementAndGet() } } }

    private fun newsDetail(n: Int) { thread {
        asyncBusy.incrementAndGet()
        try {
            val link = newsLinks.getOrNull(n - 1) ?: return@thread say("Ask me for today's news first")
            say("Getting the full story")
            var text = ""
            try {
                val html = fetch(link)
                text = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { strip(it.groupValues[1]) }.filter { it.length > 70 }.take(7).joinToString(" ")
            } catch (e: Exception) {}
            if (text.isBlank()) text = newsDescs.getOrNull(n - 1) ?: ""
            say(if (text.isBlank()) "I could not open that story" else text.take(3500))
        } finally { asyncBusy.decrementAndGet() } } }

    private fun ask(q: String) { thread {
        asyncBusy.incrementAndGet()
        try {
            aiAnswer(q)?.let { return@thread say(it) }
            val e = enc(q.replace(Regex("^(how to|what is|who is|tell me about)\\s+"), ""))
            val ddg = JSONObject(URL("https://api.duckduckgo.com/?q=$e&format=json&no_html=1&skip_disambig=1").readText()).optString("AbstractText")
            if (ddg.isNotBlank()) return@thread say(ddg)
            val s = JSONObject(URL("https://en.wikipedia.org/api/rest_v1/page/summary/$e").readText()).optString("extract")
            say(if (s.isNotBlank()) s else "I did not find anything for that")
        } catch (ex: Exception) { say("Sorry, I did not understand: $q") } finally { asyncBusy.decrementAndGet() } } }

    /** Optional: the user's own Claude API key. Only the command TEXT is sent – never screen content. */
    private fun claudeMsgs(system: String, msgs: JSONArray, max: Int): String? {
        val key = prefs.aiKey; if (key.isBlank()) return null
        return try {
            val body = JSONObject().put("model", "claude-haiku-5-5").put("max_tokens", max).put("system", system).put("messages", msgs)
            val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 15000
            c.setRequestProperty("x-api-key", key); c.setRequestProperty("anthropic-version", "2023-06-01"); c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("content").getJSONObject(0).getString("text")
        } catch (e: Exception) { Bus.post("info", "AI help failed: ${e.javaClass.simpleName}"); null }
    }
    private fun claude(system: String, user: String, max: Int) = claudeMsgs(system, JSONArray().put(JSONObject().put("role", "user").put("content", user)), max)
    /** Chat answer with short memory of the conversation. */
    private fun aiAnswer(q: String): String? {
        val msgs = JSONArray()
        synchronized(history) { history.takeLast(6).forEach { msgs.put(JSONObject().put("role", it.first).put("content", it.second)) } }
        msgs.put(JSONObject().put("role", "user").put("content", q))
        val r = claudeMsgs("You are ${prefs.assistantName}, a friendly voice assistant talking to ${prefs.callMe}. Answer in at most 3 short spoken sentences, plain text, no markdown. The user may speak English, Hindi or Hinglish; reply in the same language.", msgs, 300)
        if (r != null) synchronized(history) { history.add("user" to q); history.add("assistant" to r); while (history.size > 12) history.removeAt(0) }
        return r
    }
    private fun aiInterpret(text: String): List<String>? {
        val r = claude("Convert a spoken phone command (English, Hindi or Hinglish, may contain speech-recognition mistakes) into JSON only: {\"cmds\":[...]}. " +
            "Allowed cmds: open <app>, play <youtube query>, yt_search <query>, search <query>, torch on, torch off, photo front, photo back, call <contact name>, media toggle, media next, media prev, " +
            "scroll down, scroll up, volume up, volume down, click <label>, type <text>, send, back, home, time, date, news, math <expression in words>, ask <question>. " +
            "Use several cmds in order for multi-step requests. Output JSON only.", text, 300) ?: return null
        return try { val a = JSONObject(r.substring(r.indexOf('{'), r.lastIndexOf('}') + 1)).getJSONArray("cmds"); (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() } } catch (e: Exception) { null }
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

/** Maths in spoken form: + - × ÷, into/times/by, powers, roots, percent, tables. */
object Mathx {
    private val hindi = mapOf("ek" to "1", "teen" to "3", "char" to "4", "chaar" to "4", "paanch" to "5", "panch" to "5", "chhe" to "6", "chheh" to "6", "saat" to "7", "aath" to "8", "nau" to "9", "das" to "10")
    private fun prep(t0: String): String {
        var t = t0.split(" ").joinToString(" ") { hindi[it] ?: it }
        return Words.toDigits(t)
    }
    fun tableN(t0: String): Int? {
        val t = prep(t0)
        return (Regex("table of (\\d+)").find(t) ?: Regex("(\\d+)\\s*(?:ka\\s*)?table").find(t) ?: Regex("table (\\d+)").find(t))?.groupValues?.get(1)?.toIntOrNull()
    }
    fun expr(t0: String): String? {
        var t = prep(t0)
        repeat(3) { t = t.replace(Regex("^(?:what is|calculate|tell me|find|solve|how much is|the value of|value of|answer of|kitna hota hai|kitne hote hain|evaluate)\\s*"), "") }
        val n = "(\\d+(?:\\.\\d+)?)"
        t = t.replace(Regex("(?:square root|squareroot|under root|underroot|sqrt|root)\\s*(?:of\\s*)?$n"), "sqrt($1)")
            .replace(Regex("$n\\s*(?:squared|square)"), "($1)^2").replace(Regex("$n\\s*(?:cubed|cube)"), "($1)^3")
            .replace(Regex("$n\\s*(?:to the power of|to the power|raised to|power)\\s*$n"), "$1^$2")
            .replace(Regex("$n\\s*(?:percent|percentage|%)\\s*of\\s*$n"), "($1/100)*$2")
            .replace(Regex("\\b(?:multiplied by|multiply by|multiply|times|into|cross|guna|x)\\b"), "*")
            .replace(Regex("\\b(?:divided by|divide by|upon|over|by|bhag|bhaag)\\b"), "/")
            .replace(Regex("\\b(?:plus|add|jod)\\b"), "+").replace(Regex("\\b(?:minus|subtract|less|ghata|ghatao)\\b"), "-")
            .replace(" ", "")
        if (!Regex("^[0-9.+\\-*/^()sqrt]+$").matches(t)) return null
        return if (Regex("[0-9)][+\\-*/^]|sqrt").containsMatchIn(t)) t else null
    }
    fun intent(t: String) = tableN(t) != null || expr(t) != null
    fun solve(t: String): String? {
        tableN(t)?.let { n -> return "Table of $n. " + (1..10).joinToString(". ") { "$n times $it is ${n * it}" } }
        val e = expr(t) ?: return null
        return try { "The answer is " + fmt(P(e).parse()) } catch (x: Exception) { null }
    }
    private fun fmt(d: Double): String = if (d % 1.0 == 0.0 && Math.abs(d) < 1e15) d.toLong().toString() else "%.6f".format(d).trimEnd('0').trimEnd('.')
    private class P(val s: String) {
        var i = 0
        fun parse(): Double { val v = expr(); if (i != s.length) throw IllegalStateException(); return v }
        fun expr(): Double { var v = term(); while (i < s.length && (s[i] == '+' || s[i] == '-')) { val o = s[i++]; val r = term(); v = if (o == '+') v + r else v - r }; return v }
        fun term(): Double { var v = pow(); while (i < s.length && (s[i] == '*' || s[i] == '/')) { val o = s[i++]; val r = pow(); v = if (o == '*') v * r else v / r }; return v }
        fun pow(): Double { val b = unary(); if (i < s.length && s[i] == '^') { i++; return Math.pow(b, pow()) }; return b }
        fun unary(): Double { if (s[i] == '-') { i++; return -unary() }; if (s[i] == '+') { i++; return unary() }; return atom() }
        fun atom(): Double {
            if (s.startsWith("sqrt", i)) { i += 4; return Math.sqrt(atom()) }
            if (s[i] == '(') { i++; val v = expr(); if (i >= s.length || s[i] != ')') throw IllegalStateException(); i++; return v }
            val st = i; while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(st, i).toDouble()
        }
    }
}
