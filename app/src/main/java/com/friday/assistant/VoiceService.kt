package com.friday.assistant
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.StorageService
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Always-on service. One recogniser hears everything; "friday <command>" works in one breath. */
class VoiceService : Service() {
    companion object { const val ACTION_ENROLL = "enroll"; const val SR = 16000f }
    private lateinit var prefs: Prefs
    private lateinit var tts: TextToSpeech
    private lateinit var router: CommandRouter
    @Volatile private var running = false
    @Volatile private var onMode = false
    @Volatile private var enrolling = false
    @Volatile private var speaking = false
    @Volatile private var muteUntil = 0L
    @Volatile private var awakeUntil = 0L
    private var spkModel: SpeakerModel? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        tts = TextToSpeech(this) {
            tts.language = Locale("en", "IN")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { speaking = true }
                override fun onDone(id: String?) { speaking = false; muteUntil = System.currentTimeMillis() + 500 }
                override fun onError(id: String?) { speaking = false }
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
        return START_STICKY
    }

    private fun listen() = thread(name = "listen") {
        Bus.post("info", "Loading voice model…")
        val model = try { Model(StorageService.sync(this, "model-en", "model")) } catch (e: Exception) { Bus.post("info", "Voice model missing"); say("Voice model missing"); return@thread }
        spkModel = try { SpeakerModel(StorageService.sync(this, "model-spk", "spk")) } catch (e: Exception) { null }
        val rec = Recognizer(model, SR).also { r -> spkModel?.let { r.setSpeakerModel(it) } }
        val bufSize = AudioRecord.getMinBufferSize(SR.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        ar.startRecording()
        Bus.post("info", "Ready. Say “${prefs.wakeWord}” + a command")
        say("Yes ${prefs.callMe}, I am ready")
        val buf = ByteArray(4096); var wasMuted = false
        while (running) {
            val n = ar.read(buf, 0, buf.size); if (n <= 0) continue
            // Do not listen to our own voice
            if (speaking || System.currentTimeMillis() < muteUntil) { if (!wasMuted) rec.reset(); wasMuted = true; continue }
            wasMuted = false
            if (enrolling) { enroll(ar, rec); enrolling = false; continue }
            if (rec.acceptWaveForm(buf, n)) process(JSONObject(rec.result))
        }
        ar.stop(); ar.release()
    }

    private fun process(j: JSONObject) {
        val text = j.optString("text").trim(); if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        val tokens = text.split(" ")
        val end = wakeEnd(tokens, prefs.wakeWord)
        val cmd: String
        if (end >= 0) {
            cmd = tokens.drop(end).joinToString(" ")
            Bus.post("heard", text)
            if (cmd.isEmpty()) { awakeUntil = now + 8000; OverlayBubble.state(1); say("Yes ${prefs.callMe}"); return }
        } else if (onMode || now < awakeUntil) { cmd = text; Bus.post("heard", text) }
        else return
        if (!isOwner(j)) { Bus.post("info", "Not recognised as your voice"); return }
        awakeUntil = 0
        if (!onMode) OverlayBubble.state(2)
        router.handle(cmd, allowAsk = !onMode)
        if (!onMode) OverlayBubble.state(0)
    }

    /** Index just after the wake word, tolerating small recognition errors (fry day, fridays). -1 if absent. */
    private fun wakeEnd(t: List<String>, wake: String): Int {
        for (i in t.indices) {
            if (lev(t[i], wake) <= (if (wake.length >= 5) 1 else 0)) return i + 1
            if (i + 1 < t.size && lev(t[i] + t[i + 1], wake) <= 1) return i + 2
        }
        return -1
    }
    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i; for (j in 0..b.length) d[0][j] = j
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
    }

    fun say(t: String) { Bus.post("reply", t); tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "f") }
    override fun onDestroy() { running = false; OverlayBubble.hide(this); tts.shutdown(); super.onDestroy() }
}
