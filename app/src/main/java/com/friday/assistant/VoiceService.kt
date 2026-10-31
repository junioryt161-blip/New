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
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.StorageService
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Always-on foreground service: wake word -> speaker check -> command. Listening is fully offline. */
class VoiceService : Service() {
    companion object { const val ACTION_ENROLL = "enroll"; const val SR = 16000f }
    private lateinit var prefs: Prefs
    private lateinit var tts: TextToSpeech
    private lateinit var router: CommandRouter
    @Volatile private var running = false
    @Volatile private var onMode = false
    @Volatile private var enrolling = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        tts = TextToSpeech(this) { tts.language = Locale.getDefault() }
        router = CommandRouter(this, prefs, ::say) { onMode = it }
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
        return START_STICKY            // system restarts it if killed
    }

    private fun listen() = thread(name = "listen") {
        // Models go in assets/model-en (e.g. vosk-model-small-en-in-0.4) and assets/model-spk (vosk-model-spk-0.4)
        val model = try { Model(StorageService.sync(this, "model-en", "model")) } catch (e: Exception) { say("Voice model missing"); return@thread }
        val spk = try { SpeakerModel(StorageService.sync(this, "model-spk", "spk")) } catch (e: Exception) { null }
        val wake = prefs.wakeWord
        // Tiny grammar = a very reliable wake word, even with music playing
        val wakeRec = Recognizer(model, SR, "[\"$wake\", \"[unk]\"]")
        val cmdRec = Recognizer(model, SR).also { r -> spk?.let { r.setSpeakerModel(it) } }
        val bufSize = AudioRecord.getMinBufferSize(SR.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4
        // VOICE_RECOGNITION source applies noise/echo suppression, which helps while media plays
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        rec.startRecording()
        val buf = ByteArray(4096)
        var awake = false; var lastHeard = 0L
        while (running) {
            val n = rec.read(buf, 0, buf.size); if (n <= 0) continue
            if (enrolling) { enroll(rec, cmdRec); enrolling = false; continue }
            if (!awake && !onMode) {
                if (wakeRec.acceptWaveForm(buf, n) && JSONObject(wakeRec.result).optString("text").contains(wake)) {
                    awake = true; lastHeard = System.currentTimeMillis(); cmdRec.reset()
                }
            } else if (cmdRec.acceptWaveForm(buf, n)) {
                val j = JSONObject(cmdRec.result)
                val text = j.optString("text").replace(wake, "").trim()
                if (text.isNotEmpty() && isOwner(j)) { router.handle(text); lastHeard = System.currentTimeMillis() }
                if (!onMode) awake = false
            } else if (!onMode && System.currentTimeMillis() - lastHeard > 6000) awake = false
        }
        rec.stop(); rec.release()
    }

    /** Speaker verification: cosine distance between enrolled x-vector and the current utterance. */
    private fun isOwner(j: JSONObject): Boolean {
        val mine = prefs.voiceprintArray() ?: return true
        val a = j.optJSONArray("spk") ?: return true
        val cur = DoubleArray(a.length()) { a.getDouble(it) }
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (i in mine.indices) { d += mine[i] * cur[i]; na += mine[i] * mine[i]; nb += cur[i] * cur[i] }
        return 1 - d / (sqrt(na) * sqrt(nb)) < 0.5      // tune 0.4–0.6
    }

    private fun enroll(rec: AudioRecord, r: Recognizer) {
        say("Speak for five seconds"); Thread.sleep(2500)
        val buf = ByteArray(4096); val end = System.currentTimeMillis() + 5000
        r.reset()
        while (System.currentTimeMillis() < end) { val n = rec.read(buf, 0, buf.size); if (n > 0) r.acceptWaveForm(buf, n) }
        val a = JSONObject(r.finalResult).optJSONArray("spk")
        if (a != null) { prefs.voiceprint = (0 until a.length()).joinToString(",") { a.getDouble(it).toString() }; say("Voice saved") }
        else say("Could not capture voice, try again")
    }

    fun say(t: String) { tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "f") }
    override fun onDestroy() { running = false; OverlayBubble.hide(this); tts.shutdown(); super.onDestroy() }
}
