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
        btn("5. SAVE + START") {
            save()
            start()
        }
        btn("STOP") {
            stopService(Intent(this, BittoService::class.java))
            status.text = "Band kar diya"
        }
        status = TextView(this)
        status.textSize = 15f
        ll.addView(status)
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
        stopService(Intent(this, BittoService::class.java))
        startForegroundService(Intent(this, BittoService::class.java))
        status.text = "Chalu! Ab bolo: " + Prefs.name(this)
    }
}
