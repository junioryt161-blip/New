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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.StorageService
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Always-on listener.
 *  - ear: tiny grammar (wake word + fixed commands) – always on, even while Friday talks or music plays.
 *  - brain: full vocabulary (offline) for one-breath commands.
 *  - smart listening: after "Friday" alone, Google's recogniser (English/Hindi, online) hears the long command.
 *  - input is amplified (sensitivity) and echo-cancelled.
 */
class VoiceService : Service() {
    companion object { const val ACTION_ENROLL = "enroll"; const val ACTION_CMD = "cmd"; const val SR = 16000f }
    private lateinit var prefs: Prefs
    private lateinit var tts: TextToSpeech
    private lateinit var router: CommandRouter
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    @Volatile private var onMode = false
    @Volatile private var enrolling = false
    @Volatile private var speaking = false
    @Volatile private var needSmart = false
    @Volatile private var muteUntil = 0L
    @Volatile private var awakeUntil = 0L
    @Volatile private var genDirty = false
    @Volatile private var lastWake = 0L
    @Volatile private var crText = ""
    @Volatile private var crAt = 0L
    private var spkModel: SpeakerModel? = null
    private var gain = 3f
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
        if (i?.action == ACTION_CMD) { val c = i.getStringExtra("text") ?: ""; Bus.post("heard", c); runCmd(c) }
        return START_STICKY
    }

    private fun runCmd(cmd: String) {
        worker.submit {
            try { OverlayBubble.state(2); router.handle(cmd, !onMode) }
            catch (e: Exception) { Bus.post("info", "Error: ${e.javaClass.simpleName} ${e.message}") }
            finally { if (!onMode) OverlayBubble.state(0) }
        }
    }

    private fun grammar(wake: String): String {
        val pm = packageManager
        val labels = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString().lowercase().trim() }.filter { Regex("[a-z ]{2,20}").matches(it) }
        val apps = (labels + listOf("youtube", "whatsapp", "chrome", "gmail", "maps", "camera", "settings", "instagram", "facebook", "spotify",
            "telegram", "photos", "phonepe", "paytm", "calculator", "clock", "gallery", "messages", "phone", "contacts", "play store")).distinct().take(150)
        val fixed = mutableListOf<String>()
        for (a in apps) { fixed += a; fixed += "open $a"; fixed += "launch $a" }
        fixed += listOf("flashlight on", "flashlight off", "flash light on", "flash light off", "torch on", "torch off", "light on", "light off", "turn on flashlight",
            "turn off flashlight", "turn on the flashlight", "turn off the flashlight", "turn on torch", "turn off torch", "turn on the light", "turn off the light",
            "take a selfie", "take selfie", "take a photo", "click a photo", "click photo", "take a picture",
            "pause", "play", "resume", "next", "previous", "next video", "previous video", "next short", "previous short", "scroll down", "scroll up",
            "go back", "go home", "volume up", "volume down", "go on mode", "go off mode", "stop", "stop talking", "quiet", "send", "send message", "write message",
            "what is the time", "tell me the news", "open search bar")
        val all = mutableListOf(wake, "[unk]")
        for (f in fixed) { all += f; all += "$wake $f" }
        return JSONArray(all.distinct()).toString()
    }

    private fun amplify(buf: ByteArray, n: Int) {
        if (gain <= 1.01f) return
        var i = 0
        while (i + 1 < n) {
            val s = (buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)
            val o = (s * gain).toInt().coerceIn(-32768, 32767)
            buf[i] = (o and 0xFF).toByte(); buf[i + 1] = ((o shr 8) and 0xFF).toByte(); i += 2
        }
    }

    private fun listen() = thread(name = "listen") {
        Bus.post("info", "Loading voice models…")
        val model = try { Model(StorageService.sync(this, "model-en", "model")) } catch (e: Exception) { Bus.post("info", "Voice model missing"); say("Voice model missing"); return@thread }
        spkModel = try { SpeakerModel(StorageService.sync(this, "model-spk", "spk")) } catch (e: Exception) { null }
        gain = prefs.gain
        val gen = Recognizer(model, SR).also { r -> spkModel?.let { r.setSpeakerModel(it) } }
        val wake = prefs.wakeWord
        val ear = try { Recognizer(model, SR, grammar(wake)) } catch (e: Exception) { Bus.post("info", "Grammar failed: ${e.message}"); Recognizer(model, SR, "[\"$wake\", \"[unk]\"]") }
        val bufSize = AudioRecord.getMinBufferSize(SR.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4
        val far = prefs.far
        val src = if (far) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.VOICE_COMMUNICATION
        val ar = AudioRecord(src, SR.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        if (!far) {
            try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(ar.audioSessionId)?.enabled = true } catch (e: Exception) {}
            try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(ar.audioSessionId)?.enabled = true } catch (e: Exception) {}
        }
        try { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(ar.audioSessionId)?.enabled = true } catch (e: Exception) {}
        ar.startRecording()
        var recording = true
        Bus.post("info", "Ready. Say “$wake” + a command" + if (far) " (noisy-place mode)" else "")
        say("Yes ${prefs.callMe}, I am ready")
        val buf = ByteArray(4096)

        fun onWake() {
            val now = System.currentTimeMillis()
            if (now - lastWake < 1200) return
            lastWake = now; awakeUntil = now + 8000; OverlayBubble.state(1)
            if (speaking) { tts.stop(); speaking = false; muteUntil = 0; gen.reset(); genDirty = false; Bus.post("info", "Interrupted") }
        }

        while (running) {
            // Privacy: nothing is recorded while a payment / banking app is open
            if (Privacy.paused) {
                if (recording) { ar.stop(); recording = false; tts.stop(); OverlayBubble.visible(false) }
                Thread.sleep(300); continue
            }
            if (!recording) { ar.startRecording(); recording = true; OverlayBubble.visible(true); ear.reset(); gen.reset() }
            if (needSmart) {
                needSmart = false; ar.stop()
                val txt = smartListen()
                ar.startRecording(); ear.reset(); gen.reset()
                if (txt != null) { Bus.post("heard", txt); runCmd(txt) } else { awakeUntil = System.currentTimeMillis() + 6000; OverlayBubble.state(0) }
                continue
            }
            val n = ar.read(buf, 0, buf.size); if (n <= 0) continue
            amplify(buf, n)
            if (enrolling) { enroll(ar, gen); enrolling = false; continue }
            if (ear.acceptWaveForm(buf, n)) {
                val tx = JSONObject(ear.result).optString("text")
                if (tx.isNotEmpty()) { crText = tx; crAt = System.currentTimeMillis(); if (wakeEnd(tx.split(" "), wake) >= 0) onWake() }
            } else {
                val pt = JSONObject(ear.partialResult).optString("partial")
                if (pt.isNotEmpty() && wakeEnd(pt.split(" "), wake) >= 0) onWake()
            }
            if (speaking || System.currentTimeMillis() < muteUntil) continue
            if (genDirty) { gen.reset(); genDirty = false }
            if (gen.acceptWaveForm(buf, n)) process(JSONObject(gen.result), wake)
        }
        ar.stop(); ar.release()
    }

    /** Google's speech recogniser (handles Hindi + English + names). Blocks until a result, or null. */
    private fun smartListen(): String? {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return null
        val latch = CountDownLatch(1); var out: String? = null
        val h = Handler(Looper.getMainLooper()); var sr: SpeechRecognizer? = null
        h.post {
            try {
                sr = SpeechRecognizer.createSpeechRecognizer(this)
                sr!!.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(r: Bundle?) {
                        val l = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        out = l?.firstOrNull { router.understands(it) } ?: l?.firstOrNull(); latch.countDown()
                    }
                    override fun onError(e: Int) { latch.countDown() }
                    override fun onReadyForSpeech(p: Bundle?) { OverlayBubble.caption("Listening…") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(p: Bundle?) {}
                    override fun onEvent(t: Int, p: Bundle?) {}
                })
                sr!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.lang)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800)
                })
            } catch (e: Exception) { latch.countDown() }
        }
        latch.await(14, TimeUnit.SECONDS)
        h.post { try { sr?.destroy() } catch (e: Exception) {} }
        return out
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
        // For fixed commands the small grammar recogniser is more accurate – use it when it agrees roughly with what the big one heard
        if (now - crAt < 2500 && crText.isNotEmpty() && !crText.contains("[unk]")) {
            val ct = crText.split(" "); val ce = wakeEnd(ct, wake)
            val alt = (if (ce >= 0) ct.drop(ce) else ct).joinToString(" ")
            if (alt.isNotEmpty() && (cmd.isEmpty() || lev(cmd, alt) <= maxOf(3, alt.length / 2))) cmd = alt
        }
        Bus.post("heard", text + if (cmd.isNotEmpty() && cmd != text && end >= 0) "  →  $cmd" else "")
        if (!isOwner(j)) { Bus.post("info", "Not recognised as your voice"); return }
        if (cmd.isEmpty()) {
            awakeUntil = now + 8000; OverlayBubble.state(1)
            if (prefs.smart && SpeechRecognizer.isRecognitionAvailable(this)) { tts.stop(); needSmart = true } else say("Yes ${prefs.callMe}")
            return
        }
        if (cmd in stopWords) { tts.stop(); speaking = false; OverlayBubble.state(0); return }
        awakeUntil = 0; crText = ""
        runCmd(cmd)
    }

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
        while (System.currentTimeMillis() < end) { val n = ar.read(buf, 0, buf.size); if (n > 0) { amplify(buf, n); r.acceptWaveForm(buf, n) } }
        val a = JSONObject(r.finalResult).optJSONArray("spk")
        if (a != null) { prefs.voiceprint = (0 until a.length()).joinToString(",") { a.getDouble(it).toString() }; say("Voice saved") }
        else say("Could not capture your voice, try again")
        genDirty = true
    }

    fun say(t: String) { Bus.post("reply", t); genDirty = true; tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "f") }
    override fun onDestroy() { running = false; worker.shutdown(); OverlayBubble.hide(this); tts.shutdown(); super.onDestroy() }
}
