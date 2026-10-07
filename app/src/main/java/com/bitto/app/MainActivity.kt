package com.bitto.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var keyBox: EditText
    private lateinit var nameBox: EditText
    private lateinit var modelBox: EditText
    private lateinit var voiceBox: EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val sv = ScrollView(this)
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.VERTICAL
        ll.setPadding(40, 80, 40, 40)
        sv.addView(ll)
        setContentView(sv)

        fun label(s: String) {
            val t = TextView(this)
            t.text = s
            t.textSize = 16f
            ll.addView(t)
        }
        fun box(hint: String, v: String): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(v)
            ll.addView(e)
            return e
        }
        fun btn(s: String, f: () -> Unit) {
            val x = Button(this)
            x.text = s
            x.setOnClickListener { f() }
            ll.addView(x)
        }

        label("BITTO - Part 1")
        keyBox = box("Gemini API key", Prefs.key(this))
        nameBox = box("Assistant ka naam (wake word)", Prefs.name(this))
        modelBox = box("Gemini model", Prefs.model(this))
        voiceBox = box("Awaaz (Leda, Aoede, Zephyr, Kore)", Prefs.voice(this))
        btn("1. Permissions do") { requestPermissions(perms(), 1) }
        btn("2. Accessibility ON karo (lock ke liye)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("3. Display over other apps ON karo") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        }
        btn("4. Battery: Unrestricted karo") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
        }
        btn("6. Notifications padhne ki permission") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btn("5. SAVE + START") {
            save()
            start()
        }
        btn("Theme: Dark") { pickTheme("dark") }
        btn("Theme: Pink") { pickTheme("pink") }
        btn("Theme: Blue") { pickTheme("blue") }
        btn("Theme: Light") { pickTheme("light") }
        btn("STOP") {
            stopService(Intent(this, BittoService::class.java))
            Prefs.sp(this).edit().putBoolean("auto", false).apply()
            status.text = "Band kar diya"
        }
        status = TextView(this)
        status.textSize = 15f
        ll.addView(status)
        if (intent.getBooleanExtra("auto", false)) {
            if (ModelStore.ready(this) && Prefs.key(this).isNotBlank() &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try { launch() } catch (e: Exception) {}
            }
            sv.postDelayed({ moveTaskToBack(true) }, 1500)
        }
        applyTheme(sv, ll)
    }

    private fun pickTheme(n: String) {
        Prefs.sp(this).edit().putString("theme", n).apply()
        recreate()
    }

    private fun applyTheme(sv: ScrollView, ll: LinearLayout) {
        val th = Prefs.sp(this).getString("theme", "light") ?: "light"
        val c = when (th) {
            "dark" -> Triple(0xFF121212.toInt(), 0xFFFFFFFF.toInt(), 0xFF2A2A2A.toInt())
            "pink" -> Triple(0xFFFFE4EC.toInt(), 0xFF5A1030.toInt(), 0xFFFF8FB5.toInt())
            "blue" -> Triple(0xFFE3F2FD.toInt(), 0xFF0D2A4A.toInt(), 0xFF64B5F6.toInt())
            else -> Triple(0xFFFAFAFA.toInt(), 0xFF000000.toInt(), 0xFFD6D6D6.toInt())
        }
        sv.setBackgroundColor(c.first)
        for (i in 0 until ll.childCount) {
            val v = ll.getChildAt(i)
            if (v is TextView) {
                v.setTextColor(c.second)
                v.setHintTextColor(c.second and 0x99FFFFFF.toInt())
            }
            if (v is Button) v.setBackgroundColor(c.third)
        }
    }

    private fun perms(): Array<String> {
        val l = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l.toTypedArray()
    }

    private fun save() {
        Prefs.sp(this).edit()
            .putString("key", keyBox.text.toString().trim())
            .putString("name", nameBox.text.toString().trim().ifEmpty { "BITTO" })
            .putString("gmodel", modelBox.text.toString().trim().ifEmpty { "gemini-3.5-flash" })
            .putString("voice", voiceBox.text.toString().trim().ifEmpty { "Leda" })
            .apply()
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Pehle button 1 se Permissions do"
            return
        }
        if (Prefs.key(this).isBlank()) {
            status.text = "Gemini API key daalo"
            return
        }
        if (ModelStore.ready(this)) {
            launch()
        } else {
            status.text = "Awaaz ka model download ho raha hai (~40 MB), ruko..."
            Thread {
                try {
                    ModelStore.download(this) { p -> runOnUiThread { status.text = p } }
                    runOnUiThread { launch() }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Download fail: " + e.message }
                }
            }.start()
        }
    }

    private fun launch() {
        Prefs.sp(this).edit().putBoolean("auto", true).apply()
        stopService(Intent(this, BittoService::class.java))
        startForegroundService(Intent(this, BittoService::class.java))
        status.text = "Chalu! Ab bolo: " + Prefs.name(this)
    }
}
