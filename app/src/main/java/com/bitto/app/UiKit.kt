package com.bitto.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sin

class Pal(val bg1: Int, val bg2: Int, val text: Int, val sub: Int, val accent: Int, val card: Int)

object Themes {
    fun get(c: Context): Pal = when (Prefs.sp(c).getString("theme", "dark")) {
        "pink" -> Pal(0xFFFFF0F5.toInt(), 0xFFFFC1D9.toInt(), 0xFF4A0D2A.toInt(), 0xFF8A4A63.toInt(), 0xFFE91E63.toInt(), 0xFFFFFFFF.toInt())
        "blue" -> Pal(0xFFEAF4FF.toInt(), 0xFFB3D9FF.toInt(), 0xFF0D2A4A.toInt(), 0xFF4A6A8A.toInt(), 0xFF1976D2.toInt(), 0xFFFFFFFF.toInt())
        "light" -> Pal(0xFFFFFFFF.toInt(), 0xFFEDE7FF.toInt(), 0xFF1A1A1A.toInt(), 0xFF666666.toInt(), 0xFF7B3FF2.toInt(), 0xFFFFFFFF.toInt())
        else -> Pal(0xFF0F0C29.toInt(), 0xFF302B63.toInt(), 0xFFFFFFFF.toInt(), 0xFFB8B5D6.toInt(), 0xFFFF4FA3.toInt(), 0x33FFFFFF)
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun roundBg(c: Context, color: Int, r: Int): GradientDrawable {
    val g = GradientDrawable()
    g.cornerRadius = c.dp(r).toFloat()
    g.setColor(color)
    return g
}

class OrbView(c: Context) : View(c) {
    var accent: Int = 0xFFFF4FA3.toInt()
    var level: Int = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        t += 0.05f
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) / 5f
        val amp = when (level) { 0 -> 0.02f; 1 -> 0.05f; 2 -> 0.09f; 3 -> 0.12f; else -> 0.18f }
        val sp = when (level) { 3 -> 3.2f; 4 -> 2.6f; 2 -> 1.6f; else -> 1f }
        for (i in 3 downTo 1) {
            val r = base * (1f + i * 0.3f) * (1f + amp * sin(t * sp + i))
            p.color = accent
            p.alpha = if (level == 0) 18 * (4 - i) else 34 * (4 - i)
            cv.drawCircle(cx, cy, r, p)
        }
        p.color = accent
        p.alpha = if (level == 0) 110 else 255
        cv.drawCircle(cx, cy, base * (1f + amp * sin(t * sp * 1.5f)), p)
        postInvalidateOnAnimation()
    }
}

object VoiceTest {
    fun play(c: Context, text: String, onFail: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            try {
                val voice = JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", Prefs.voice(c)))
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "[happy] " + text)))))
                    .put("generationConfig", JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("speechConfig", JSONObject().put("voiceConfig", voice)))
                val url = "https://generativelanguage.googleapis.com/v1beta/models/" + Prefs.ttsModel(c) + ":generateContent"
                val cn = URL(url).openConnection() as HttpURLConnection
                cn.requestMethod = "POST"
                cn.connectTimeout = 10000
                cn.readTimeout = 25000
                cn.doOutput = true
                cn.setRequestProperty("Content-Type", "application/json")
                cn.setRequestProperty("x-goog-api-key", Prefs.key(c))
                cn.outputStream.use { it.write(body.toString().toByteArray()) }
                if (cn.responseCode != 200) throw Exception("http " + cn.responseCode)
                val txt = cn.inputStream.bufferedReader().use { it.readText() }
                val b64 = JSONObject(txt).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
                var pcm = Base64.decode(b64, Base64.DEFAULT)
                if (pcm.size > 44 && pcm[0].toInt() == 0x52 && pcm[1].toInt() == 0x49) pcm = pcm.copyOfRange(44, pcm.size)
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
                main.post { onFail() }
            }
        }.start()
    }
}
