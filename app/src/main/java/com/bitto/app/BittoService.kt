package com.bitto.app

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class BittoService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var ss: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var sr: SpeechRecognizer? = null
    private var busy = false
    private var wakeWords = listOf<String>()
    private val history = JSONArray()
    private val cbs = HashMap<String, () -> Unit>()
    private val emo = mapOf(
        "happy" to Pair(1.3f, 1.05f), "excited" to Pair(1.4f, 1.15f),
        "sad" to Pair(1.0f, 0.85f), "shy" to Pair(1.3f, 0.9f),
        "angry" to Pair(1.1f, 1.15f), "worried" to Pair(1.1f, 0.95f),
        "neutral" to Pair(1.2f, 1.0f)
    )

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bitto", "BITTO", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bitto")
            .setContentTitle(Prefs.name(this) + " sun rahi hai")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                setupTts()
                ttsReady = true
            }
        }
        Thread {
            try {
                model = Model(ModelStore.dir(this).absolutePath)
                prewarm()
                h.post { startWake() }
            } catch (e: Exception) {
                h.post { stopSelf() }
            }
        }.start()
    }

    override fun onDestroy() {
        stopWake()
        try { sr?.destroy() } catch (e: Exception) {}
        tts?.shutdown()
        try { model?.close() } catch (e: Exception) {}
        super.onDestroy()
    }

    // ---------- Awaaz (TTS) ----------
    private fun setupTts() {
        val t = tts ?: return
        t.setLanguage(Locale("hi", "IN"))
        try {
            val v = t.voices.filter { it.locale.language == "hi" }
            val pick = v.firstOrNull { it.name.contains("female", true) } ?: v.firstOrNull()
            if (pick != null) t.setVoice(pick)
        } catch (e: Exception) {}
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { h.post { cbs.remove(id)?.invoke() } }
            override fun onError(id: String?) { h.post { cbs.remove(id)?.invoke() } }
        })
    }

    private val ttsCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private fun prewarm() {
        Thread { cloudTts("happy", "जी, बोलिए") }.start()
    }

    private fun say(emotion: String, text: String, done: () -> Unit) {
        if (text.isBlank() || !Prefs.sp(this).getBoolean("cloudvoice", true) || Prefs.key(this).isBlank()) {
            sayLocal(emotion, text, done)
            return
        }
        Thread {
            val pcm = cloudTts(emotion, text)
            h.post { if (pcm == null) sayLocal(emotion, text, done) else playPcm(pcm, done) }
        }.start()
    }

    private fun cloudTts(emotion: String, text: String): ByteArray? {
        val ck = emotion + "|" + text
        val hit = ttsCache[ck]
        if (hit != null) return hit
        try {
            val tag = when (emotion) {
                "happy" -> "happy"
                "excited" -> "excited"
                "sad" -> "sad"
                "shy" -> "softly"
                "angry" -> "annoyed"
                "worried" -> "worried"
                else -> "warmly"
            }
            val voice = JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", Prefs.voice(this)))
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "[" + tag + "] " + text)))))
                .put("generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", voice)))
            val url = "https://generativelanguage.googleapis.com/v1beta/models/" + Prefs.ttsModel(this) + ":generateContent"
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 25000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", Prefs.key(this))
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) return null
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            val b64 = JSONObject(txt).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
            var pcm = Base64.decode(b64, Base64.DEFAULT)
            if (pcm.size > 44 && pcm[0].toInt() == 0x52 && pcm[1].toInt() == 0x49) pcm = pcm.copyOfRange(44, pcm.size)
            if (ttsCache.size > 30) ttsCache.clear()
            ttsCache[ck] = pcm
            return pcm
        } catch (e: Exception) {
            return null
        }
    }

    private fun playPcm(pcm: ByteArray, done: () -> Unit) {
        Thread {
            try {
                val at = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(pcm.size, 4096))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                at.write(pcm, 0, pcm.size)
                at.play()
                Thread.sleep(pcm.size / 2 * 1000L / 24000 + 300)
                at.release()
            } catch (e: Exception) {
            }
            h.post { done() }
        }.start()
    }

    private fun sayLocal(emotion: String, text: String, done: () -> Unit) {
        val t = tts
        if (t == null || !ttsReady || text.isBlank()) {
            h.post { done() }
            return
        }
        val pr = emo[emotion] ?: Pair(1.2f, 1.0f)
        t.setPitch(pr.first)
        t.setSpeechRate(pr.second)
        val id = "u" + System.nanoTime()
        cbs[id] = done
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        h.postDelayed({ cbs.remove(id)?.invoke() }, 20000)
    }

    // ---------- Wake word (Vosk) ----------
    private fun wakeList(): List<String> {
        val n = Prefs.name(this).lowercase().trim()
        val l = mutableListOf(n)
        if (n == "bitto") l.addAll(listOf("bit toe", "beat oh", "bitter", "bito", "pitto", "bit to"))
        return l
    }

    private fun startWake() {
        val m = model ?: return
        stopWake()
        busy = false
        wakeWords = wakeList()
        val g = JSONArray()
        wakeWords.forEach { g.put(it) }
        g.put("[unk]")
        try {
            val rec = Recognizer(m, 16000.0f, g.toString())
            val s = SpeechService(rec, 16000.0f)
            ss = s
            s.startListening(object : RecognitionListener {
                override fun onPartialResult(x: String?) { hear(x, "partial") }
                override fun onResult(x: String?) { hear(x, "text") }
                override fun onFinalResult(x: String?) {}
                override fun onError(e: Exception?) { h.postDelayed({ if (!busy) startWake() }, 2000) }
                override fun onTimeout() {}
            })
        } catch (e: Exception) {
            h.postDelayed({ startWake() }, 3000)
        }
    }

    private fun stopWake() {
        try { ss?.stop() } catch (e: Exception) {}
        try { ss?.shutdown() } catch (e: Exception) {}
        ss = null
    }

    private fun hear(s: String?, k: String) {
        if (busy || s == null) return
        val t = try { JSONObject(s).optString(k) } catch (e: Exception) { "" }
        if (t.isNotBlank() && wakeWords.any { t.contains(it) }) onWake()
    }

    private fun onWake() {
        if (busy) return
        busy = true
        stopWake()
        say("happy", "जी, बोलिए") {
            listenOnce { t -> if (t == null) resume() else handle(t) }
        }
    }

    private fun resume() {
        agentSteps = 0
        busy = false
        h.post { startWake() }
    }

    // ---------- Command sunna ----------
    private fun listenOnce(result: (String?) -> Unit) {
        h.postDelayed({
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                    result(null)
                    return@postDelayed
                }
                sr?.destroy()
                val r = SpeechRecognizer.createSpeechRecognizer(this)
                sr = r
                var delivered = false
                fun deliver(x: String?) {
                    if (delivered) return
                    delivered = true
                    h.post { try { r.destroy() } catch (e: Exception) {} }
                    sr = null
                    result(x)
                }
                r.setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(e: Int) { deliver(null) }
                    override fun onResults(b: Bundle?) {
                        deliver(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                    }
                    override fun onPartialResults(b: Bundle?) {}
                    override fun onEvent(t: Int, b: Bundle?) {}
                })
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                r.startListening(i)
                h.postDelayed({ deliver(null) }, 20000)
            } catch (e: Exception) {
                result(null)
            }
        }, 400)
    }

    private fun handle(text: String) {
        history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        Thread {
            val res = askGemini()
            h.post { reply(res) }
        }.start()
    }

    // ---------- Dimag (Gemini) ----------
    private fun system(): String = """Tum ${Prefs.name(this)} ho: ek pyaari, sweet, caring ladki jo user ki phone assistant hai. Hinglish me baat karo, chhote natural jawab (1-2 vaakya). Real ladki ki tarah emotions dikhao. Agar user sachchi me puche ki tum insaan ho ya AI, to pyaar se sach bata do ki tum AI ho.
Sirf ye JSON do:
{"emotion":"happy|excited|sad|shy|angry|worried|neutral","display":"Roman Hinglish jawab","speech":"wahi jawab Devanagari Hindi me (English words Latin me hi rehne do)","followup":false,"action":{"type":"none|call|sms|whatsapp|open_app|torch|volume|wifi|lock|url|stop","name":"contact ka naam English letters me","number":"agar user ne number bola","app":"app ka naam English me","value":"torch: on ya off, volume: 0 se 100, url: poora link","message":"sms ya whatsapp ka text"}}
Agar tumhe user se kuch aur puchna ho to followup true rakho. WhatsApp me message sirf prefill hota hai, user ko send dabana padta hai. Search ya YouTube ke liye type url me search ka link do. Jab user band ho jao bole to type stop do.
Phone ke andar kaam ke liye ye extra types bhi hain: home, back, recents, close_app (app me app ka naam, khaali ho to abhi khula app band hoga), tap (value me screen par dikhne wale button ya text ka naam), scroll (value: up ya down ya left ya right), type (value me likhne ka text), submit (keyboard ka search ya enter), read_screen (screen ka text padhne ke liye; uske baad tumhe screen ka text milega, tab agla action do aur speech chhota ya khaali rakho). Kai kaam ek saath hon to JSON me "steps" naam ki list do (har step me type aur zaroori fields, aur wait_ms me intezaar milliseconds me, naya app khulne par 2000) aur action none rakho. WhatsApp message ke liye sirf whatsapp action do, service khud confirm karke Send dabati hai. YouTube search ke steps: open_app youtube, tap Search (wait_ms 2500), type, submit (wait_ms 500)."""

    private fun err(m: String): JSONObject {
        try { if (history.length() > 0) history.remove(history.length() - 1) } catch (e: Exception) {}
        return JSONObject().put("emotion", "worried").put("speech", m).put("display", m)
    }

    private fun askGemini(): JSONObject {
        val key = Prefs.key(this)
        if (key.isBlank()) return err("पहले ऐप में Gemini की डालो।")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system()))))
            .put("contents", history)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        for (a in 1..3) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/" + Prefs.model(this) + ":generateContent"
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15000
                c.readTimeout = 40000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", key)
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                if (code == 429) {
                    Thread.sleep(3000)
                    continue
                }
                if (code != 200) return err("Gemini में error आया, code " + code)
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                var out = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
                out = out.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val o = JSONObject(out)
                history.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", out))))
                while (history.length() > 12) {
                    history.remove(0)
                    history.remove(0)
                }
                return o
            } catch (e: Exception) {
                Thread.sleep(1500)
            }
        }
        return err("इंटरनेट या की में दिक्कत है।")
    }

    private fun reply(res: JSONObject) {
        val speech = res.optString("speech").ifBlank { res.optString("display") }
        val a = res.optJSONObject("action") ?: JSONObject()
        val follow = res.optBoolean("followup", false)
        say(res.optString("emotion", "neutral"), speech) {
            runActions(res) {
                if (follow) {
                    listenOnce { t -> if (t == null) resume() else handle(t) }
                } else {
                    resume()
                }
            }
        }
    }

    // ---------- Haath (kaam karna) ----------
    private fun perform(a: JSONObject, next: () -> Unit) {
        val t = a.optString("type", "none")
        val nm = a.optString("name").ifBlank { a.optString("number") }.ifBlank { "unko" }
        try {
            when (t) {
                "call", "sms", "whatsapp" -> {
                    val n = number(a)
                    if (n == null) {
                        say("worried", "ये नंबर मुझे नहीं मिला।", next)
                        return
                    }
                    when (t) {
                        "call" -> confirm(nm + " को कॉल लगा दूँ?") {
                            if (it) go(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", n, null)))
                            next()
                        }
                        "sms" -> confirm(nm + " को एसएमएस भेज दूँ?") {
                            if (it) {
                                sendSms(n, a.optString("message"))
                                say("happy", "भेज दिया!", next)
                            } else {
                                next()
                            }
                        }
                        else -> {
                            confirm(nm + " को WhatsApp भेज दूँ?") {
                                if (it) {
                                    go(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + n.removePrefix("+") + "?text=" + Uri.encode(a.optString("message")))))
                                    val acc = BittoAccessibility.instance
                                    if (acc == null) next() else h.postDelayed({ acc.clickAny(listOf("Send", "भेजें"), 10) { next() } }, 3000)
                                } else {
                                    next()
                                }
                            }
                            return
                            next()
                        }
                    }
                }
                "open_app" -> {
                    val i = findApp(a.optString("app"))
                    if (i == null) say("worried", "ये ऐप मुझे नहीं मिला।", next) else { go(i); next() }
                }
                "torch" -> {
                    val cm = getSystemService(CameraManager::class.java)
                    val id = cm.cameraIdList.firstOrNull {
                        cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                    if (id != null) cm.setTorchMode(id, a.optString("value").lowercase() != "off")
                    next()
                }
                "volume" -> {
                    val am = getSystemService(AudioManager::class.java)
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val v = a.optString("value").toFloatOrNull() ?: 50f
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * v / 100f).toInt().coerceIn(0, max), 0)
                    next()
                }
                "wifi" -> {
                    go(Intent(Settings.Panel.ACTION_WIFI))
                    say("neutral", "वाईफाई का पैनल खोल दिया, एंड्रॉइड सीधे बंद नहीं करने देता।", next)
                }
                "lock" -> {
                    val acc = BittoAccessibility.instance
                    if (acc == null) {
                        say("worried", "पहले एक्सेसिबिलिटी ऑन करो।", next)
                    } else {
                        acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                        next()
                    }
                }
                "home", "back", "recents", "scroll", "tap", "type", "submit", "close_app", "read_screen" -> {
                    val acc = BittoAccessibility.instance
                    if (acc == null) {
                        needAcc(next)
                        return
                    }
                    val v = a.optString("value")
                    when (t) {
                        "home" -> { acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); next() }
                        "back" -> { acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); next() }
                        "recents" -> { acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); next() }
                        "scroll" -> { acc.scroll(v.lowercase()); h.postDelayed({ next() }, 600) }
                        "tap" -> acc.clickAny(listOf(v), 6) { ok ->
                            if (ok) h.postDelayed({ next() }, 800) else say("worried", "वो बटन नहीं मिला।", next)
                        }
                        "type" -> {
                            if (acc.typeText(v)) h.postDelayed({ next() }, 500) else say("worried", "लिखने की जगह नहीं मिली।", next)
                        }
                        "submit" -> { acc.submit(); h.postDelayed({ next() }, 800) }
                        "close_app" -> {
                            val pkg = findApp(a.optString("app"))?.component?.packageName ?: acc.currentPackage()
                            if (pkg == null || pkg == packageName) {
                                say("worried", "कौन सा ऐप बंद करूँ?", next)
                            } else {
                                acc.forceStop(pkg) { ok -> if (ok) next() else say("worried", "ऐप बंद नहीं हो पाया।", next) }
                            }
                        }
                        else -> {
                            h.postDelayed({
                                val s = acc.screenText()
                                agentSteps++
                                if (agentSteps > 6) next() else handle("[SCREEN] " + s + " --- ab agla action do, ya kaam ho gaya to action none.")
                            }, 900)
                        }
                    }
                }
                "url" -> {
                    go(Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("value"))))
                    next()
                }
                "stop" -> say("happy", "ठीक है, बाय!") { stopSelf() }
                else -> next()
            }
        } catch (e: Exception) {
            say("worried", "ये काम नहीं हो पाया।", next)
        }
    }

    private var agentSteps = 0

    private fun needAcc(next: () -> Unit) {
        say("worried", "पहले एक्सेसिबिलिटी ऑन करो।", next)
    }

    private fun runActions(res: JSONObject, done: () -> Unit) {
        val list = JSONArray()
        val arr = res.optJSONArray("steps")
        if (arr != null) {
            for (i in 0 until arr.length()) list.put(arr.get(i))
        }
        val single = res.optJSONObject("action")
        if (list.length() == 0 && single != null) list.put(single)
        runAt(list, 0, done)
    }

    private fun runAt(list: JSONArray, i: Int, done: () -> Unit) {
        if (i >= list.length()) {
            done()
            return
        }
        val a = list.optJSONObject(i)
        if (a == null) {
            runAt(list, i + 1, done)
            return
        }
        h.postDelayed({ perform(a) { runAt(list, i + 1, done) } }, a.optLong("wait_ms", 0L))
    }

    private fun confirm(q: String, r: (Boolean) -> Unit) {
        say("neutral", q) {
            listenOnce { t ->
                val s = (t ?: "").lowercase()
                val no = listOf("nahi", "nahin", "mat", "no", "ruko", "cancel", "नहीं", "मत", "रुको").any { s.contains(it) }
                val yes = listOf("haan", "han", "ha ", "yes", "kar do", "kardo", "okay", "laga do", "bhej do", "हाँ", "हां", "कर दो", "लगा दो", "भेज दो", "ठीक").any { s.contains(it) }
                r(yes && !no)
            }
        }
    }

    private fun go(i: Intent) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {}
    }

    private fun sendSms(n: String, m: String) {
        try {
            val sm = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sm.sendMultipartTextMessage(n, null, sm.divideMessage(m), null, null)
        } catch (e: Exception) {}
    }

    private fun findApp(name: String): Intent? {
        val n = name.lowercase().trim()
        if (n.isEmpty()) return null
        val pm = packageManager
        val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(li, 0)
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(n) }
            ?: apps.firstOrNull { val l = it.loadLabel(pm).toString().lowercase(); l.length >= 3 && n.contains(l) }
        return hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
    }

    private fun norm(s: String): String {
        val d = s.filter { it.isDigit() || it == '+' }
        return when {
            d.startsWith("+") -> d
            d.length == 10 -> "+91" + d
            d.length == 11 && d.startsWith("0") -> "+91" + d.substring(1)
            d.length == 12 && d.startsWith("91") -> "+" + d
            else -> d
        }
    }

    private fun number(a: JSONObject): String? {
        val num = a.optString("number")
        if (num.isNotBlank()) return norm(num)
        val name = a.optString("name").lowercase().trim()
        if (name.isEmpty()) return null
        var best: String? = null
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0)?.lowercase() ?: continue
                if (n.contains(name)) {
                    best = c.getString(1)
                    break
                }
            }
        }
        return best?.let { norm(it) }
    }
}
