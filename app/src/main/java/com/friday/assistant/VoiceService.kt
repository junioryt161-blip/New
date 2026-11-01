package com.friday.assistant
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.StorageService
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Always-on listener.
 *  - "ear" recogniser: tiny grammar (wake word + fixed commands). Very accurate, runs ALWAYS, even while Friday talks or music plays.
 *  - "brain" recogniser: full vocabulary, used for open-ended speech (song names, messages, questions).
 *  - Microphone uses the phone's echo canceller so Friday's own voice and playing music are removed from what it hears.
 */
class VoiceService : Service() {
    companion object { const val ACTION_ENROLL = "enroll"; const val ACTION_CMD = "cmd"; const val SR = 16000f }
    private lateinit var prefs: Prefs
    private lateinit var tts: TextToSpeech
    private lateinit var router: CommandRouter
    @Volatile private var running = false
    @Volatile private var onMode = false
    @Volatile private var enrolling = false
    @Volatile private var speaking = false
    @Volatile private var muteUntil = 0L
    @Volatile private var awakeUntil = 0L
    @Volatile private var genDirty = false
    @Volatile private var lastWake = 0L
    @Volatile private var crText = ""
    @Volatile private var crAt = 0L
    private var spkModel: SpeakerModel? = null
    private val stopWords = setOf("stop", "stop talking", "quiet", "be quiet", "cancel", "enough", "shut up", "silence")

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        tts = TextToSpeech(this) {
            tts.language = Locale("en", "IN")
            tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { speaking = true }
                override fun onDone(id: String?) { speaking = false; muteUntil = System.currentTimeMillis() + 300; genDirty = true }
                override fun onError(id: String?) { speaking = false }
                override fun onStop(id: String?, interrupted: Boolean) { speaking = false }
            })
        }
        router = CommandRouter(this, prefs, ::say) { onMode = it; OverlayBubble.state(if (it) 1 else 0) }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("friday", "Assistant running", NotificationManager.IMPORTANCE_MIN))
        val stopPi = PendingIntent.getService(this, 0, Intent(this, VoiceService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "friday").setContentTitle("${prefs.assistantName} is listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(Notification.Action.Builder(null, "Turn off", stopPi).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
        if (i?.action == "stop") { prefs.enabled = false; stopSelf(); return START_NOT_STICKY }
        if (i?.action == ACTION_ENROLL) enrolling = true
        OverlayBubble.show(this)
        if (!running) { running = true; listen() }
        if (i?.action == ACTION_CMD) { val c = i.getStringExtra("text") ?: ""; Bus.post("heard", c); thread { router.handle(c, true) } }
        return START_STICKY
    }

    private fun grammar(wake: String): String {
        val pm = packageManager
        val labels = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString().lowercase().trim() }.filter { Regex("[a-z ]{2,20}").matches(it) }
        val apps = (labels + listOf("youtube", "whatsapp", "chrome", "gmail", "maps", "camera", "settings", "instagram", "facebook", "spotify",
            "telegram", "photos", "phonepe", "paytm", "calculator", "clock", "gallery", "messages", "phone", "contacts", "play store")).distinct().take(150)
        val fixed = mutableListOf<String>()
        for (a in apps) { fixed += "open $a"; fixed += "launch $a" }
        fixed += listOf("flashlight on", "flashlight off", "flash light on", "flash light off", "torch on", "torch off", "turn on flashlight", "turn off flashlight",
            "turn on the flashlight", "turn off the flashlight", "turn on torch", "turn off torch", "pause", "play", "resume", "next", "previous", "next video",
            "previous video", "next short", "previous short", "scroll down", "scroll up", "go back", "go home", "volume up", "volume down", "go on mode",
            "go off mode", "stop", "stop talking", "quiet", "send", "send message", "write message", "what is the time", "tell me the news")
        val all = mutableListOf(wake, "[unk]")
        for (f in fixed) { all += f; all += "$wake $f" }
        return JSONArray(all.distinct()).toString()
    }

    private fun listen() = thread(name = "listen") {
        Bus.post("info", "Loading voice models…")
        val model = try { Model(StorageService.sync(this, "model-en", "model")) } catch (e: Exception) { Bus.post("info", "Voice model missing"); say("Voice model missing"); return@thread }
        spkModel = try { SpeakerModel(StorageService.sync(this, "model-spk", "spk")) } catch (e: Exception) { null }
        val gen = Recognizer(model, SR).also { r -> spkModel?.let { r.setSpeakerModel(it) } }
        val wake = prefs.wakeWord
        val ear = try { Recognizer(model, SR, grammar(wake)) } catch (e: Exception) { Bus.post("info", "Grammar failed: ${e.message}"); Recognizer(model, SR, "[\"$wake\", \"[unk]\"]") }
        val bufSize = AudioRecord.getMinBufferSize(SR.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4
        // VOICE_COMMUNICATION = phone's echo cancellation: removes Friday's voice and playing music from the mic signal
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SR.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(ar.audioSessionId)?.enabled = true } catch (e: Exception) {}
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(ar.audioSessionId)?.enabled = true } catch (e: Exception) {}
        try { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(ar.audioSessionId)?.enabled = true } catch (e: Exception) {}
        ar.startRecording()
        Bus.post("info", "Ready. Say “$wake” + a command")
        say("Yes ${prefs.callMe}, I am ready")
        val buf = ByteArray(4096)

        fun onWake() {
            val now = System.currentTimeMillis()
            if (now - lastWake < 1200) return
            lastWake = now; awakeUntil = now + 8000; OverlayBubble.state(1)
            if (speaking) { tts.stop(); speaking = false; muteUntil = 0; gen.reset(); genDirty = false; Bus.post("info", "Interrupted") }   // barge-in
        }

        while (running) {
            val n = ar.read(buf, 0, buf.size); if (n <= 0) continue
            if (enrolling) { enroll(ar, gen); enrolling = false; continue }
            // 1) ear: always on
            if (ear.acceptWaveForm(buf, n)) {
                val tx = JSONObject(ear.result).optString("text")
                if (tx.isNotEmpty()) { crText = tx; crAt = System.currentTimeMillis(); if (wakeEnd(tx.split(" "), wake) >= 0) onWake() }
            } else {
                val pt = JSONObject(ear.partialResult).optString("partial")
                if (pt.isNotEmpty() && wakeEnd(pt.split(" "), wake) >= 0) onWake()
            }
            // 2) brain: skip while we are talking (echo), resume right after
            if (speaking || System.currentTimeMillis() < muteUntil) continue
            if (genDirty) { gen.reset(); genDirty = false }
            if (gen.acceptWaveForm(buf, n)) process(JSONObject(gen.result), wake)
        }
        ar.stop(); ar.release()
    }

    private fun process(j: JSONObject, wake: String) {
        val text = j.optString("text").trim(); if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        val tokens = text.split(" ")
        val end = wakeEnd(tokens, wake)
        var cmd: String
        if (end >= 0) cmd = tokens.drop(end).joinToString(" ")
        else if (onMode || now < awakeUntil) cmd = text
        else { Bus.post("ear", text); return }
        // The grammar recogniser is more accurate for fixed commands (open app, flashlight, pause…) – prefer it when it understood fully
        if (now - crAt < 2500 && crText.isNotEmpty() && !crText.contains("[unk]")) {
            val ct = crText.split(" "); val ce = wakeEnd(ct, wake)
            val alt = (if (ce >= 0) ct.drop(ce) else ct).joinToString(" ")
            if (alt.isNotEmpty()) cmd = alt
        }
        Bus.post("heard", if (end >= 0 || cmd == text) text else "$text  →  $cmd")
        if (cmd.isEmpty()) { awakeUntil = now + 8000; OverlayBubble.state(1); say("Yes ${prefs.callMe}"); return }
        if (cmd in stopWords) { tts.stop(); speaking = false; OverlayBubble.state(0); return }
        if (!isOwner(j)) { Bus.post("info", "Not recognised as your voice"); return }
        awakeUntil = 0; crText = ""
        if (!onMode) OverlayBubble.state(2)
        router.handle(cmd, allowAsk = !onMode)
        if (!onMode) OverlayBubble.state(0)
    }

    /** Index just after the wake word, tolerating small recognition errors. -1 if absent. */
    private fun wakeEnd(t: List<String>, wake: String): Int {
        for (i in t.indices) {
            if (lev(t[i], wake) <= (if (wake.length >= 5) 1 else 0)) return i + 1
            if (i + 1 < t.size && lev(t[i] + t[i + 1], wake) <= 1) return i + 2
        }
        return -1
    }
    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    private fun isOwner(j: JSONObject): Boolean {
        val mine = prefs.voiceprintArray() ?: return true
        val a = j.optJSONArray("spk") ?: return true
        val cur = DoubleArray(a.length()) { a.getDouble(it) }
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (i in mine.indices) { d += mine[i] * cur[i]; na += mine[i] * mine[i]; nb += cur[i] * cur[i] }
        return 1 - d / (sqrt(na) * sqrt(nb)) < 0.7
    }

    private fun enroll(ar: AudioRecord, r: Recognizer) {
        if (spkModel == null) { say("Speaker model missing"); return }
        say("Speak normally for five seconds"); Thread.sleep(3500)
        val buf = ByteArray(4096); val end = System.currentTimeMillis() + 5000
        r.reset()
        while (System.currentTimeMillis() < end) { val n = ar.read(buf, 0, buf.size); if (n > 0) r.acceptWaveForm(buf, n) }
        val a = JSONObject(r.finalResult).optJSONArray("spk")
        if (a != null) { prefs.voiceprint = (0 until a.length()).joinToString(",") { a.getDouble(it).toString() }; say("Voice saved") }
        else say("Could not capture your voice, try again")
        genDirty = true
    }

    fun say(t: String) { Bus.post("reply", t); genDirty = true; tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "f") }
    override fun onDestroy() { running = false; OverlayBubble.hide(this); tts.shutdown(); super.onDestroy() }
}
