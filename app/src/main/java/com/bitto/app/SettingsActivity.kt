package com.bitto.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var pal: Pal
    private lateinit var ll: LinearLayout
    private lateinit var keyBox: EditText
    private lateinit var nameBox: EditText
    private lateinit var modelBox: EditText
    private lateinit var ttsBox: EditText
    private lateinit var customBox: EditText
    private lateinit var voiceBtn: TextView
    private val checks = ArrayList<Pair<TextView, () -> Boolean>>()
    private val titles = HashMap<TextView, String>()
    private val voices = listOf("Leda", "Aoede", "Zephyr", "Kore", "Callirrhoe", "Autonoe", "Despina", "Erinome", "Laomedeia", "Achernar")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pal = Themes.get(this)
        window.statusBarColor = pal.bg1
        window.navigationBarColor = pal.bg2
        val sv = ScrollView(this)
        sv.setBackgroundColor(pal.bg1)
        ll = LinearLayout(this)
        ll.orientation = LinearLayout.VERTICAL
        ll.setPadding(dp(16), dp(40), dp(16), dp(40))
        sv.addView(ll)
        setContentView(sv)

        val top = TextView(this)
        top.text = "Settings"
        top.textSize = 28f
        top.typeface = Typeface.DEFAULT_BOLD
        top.setTextColor(pal.text)
        ll.addView(top)

        head("ZAROORI SETUP (✓ aane tak karo)")
        var c = card()
        setup(c, "Permissions (mic, contacts, phone, SMS)", { perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } }) {
            requestPermissions(perms(), 1)
        }
        setup(c, "Accessibility ON (app control, lock)", { BittoAccessibility.instance != null }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        setup(c, "Display over other apps ON", { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        }
        setup(c, "Notifications padhne ki permission", { BittoNotifications.instance != null }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        row(c, "Battery: Unrestricted karo (App info → Battery)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
        }

        head("BASIC")
        c = card()
        keyBox = field(c, "Gemini API key", Prefs.key(this))
        keyBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        nameBox = field(c, "Assistant ka naam (wake word, English letters)", Prefs.name(this))
        modelBox = field(c, "Gemini model", Prefs.model(this))

        head("AWAAZ")
        c = card()
        val cur = Prefs.voice(this)
        voiceBtn = row(c, "Awaaz: " + cur + "  ▾") {
            AlertDialog.Builder(this).setTitle("Awaaz chuno")
                .setItems(voices.toTypedArray()) { _, i ->
                    Prefs.sp(this).edit().putString("voice", voices[i]).apply()
                    customBox.setText("")
                    voiceBtn.text = "Awaaz: " + voices[i] + "  ▾"
                }.show()
        }
        customBox = field(c, "Custom awaaz ka naam (khaali chhodo to upar wali chalegi)", if (cur in voices) "" else cur)
        row(c, "▶  Awaaz sunke dekho") {
            saveFields()
            Toast.makeText(this, "Ruko, awaaz aa rahi hai...", Toast.LENGTH_SHORT).show()
            VoiceTest.play(this, "नमस्ते, मैं " + Prefs.name(this) + " हूँ। बताओ, क्या करना है?") {
                Toast.makeText(this, "Awaaz nahi aayi (key ya limit check karo)", Toast.LENGTH_LONG).show()
            }
        }
        toggle(c, "Natural awaaz (Gemini)", "cloudvoice", true)
        toggle(c, "Tez mode (chhote jawab turant phone ki awaaz me)", "fast", true)

        head("THEME")
        c = card()
        val tr = LinearLayout(this)
        tr.orientation = LinearLayout.HORIZONTAL
        c.addView(tr)
        for (n in listOf("dark", "pink", "blue", "light")) {
            val t = TextView(this)
            t.text = n.replaceFirstChar { it.uppercase() }
            t.textSize = 15f
            t.gravity = Gravity.CENTER
            t.setTextColor(0xFFFFFFFF.toInt())
            t.background = roundBg(this, pal.accent, 20)
            t.setOnClickListener {
                saveFields()
                Prefs.sp(this).edit().putString("theme", n).apply()
                recreate()
            }
            tr.addView(t, LinearLayout.LayoutParams(0, dp(44), 1f).also { it.setMargins(dp(3), dp(6), dp(3), dp(6)) })
        }

        head("AUTO START")
        c = card()
        toggle(c, "Hamesha active (phone restart par khud chalu)", "auto", false)

        head("ADVANCED")
        c = card()
        ttsBox = field(c, "Awaaz wala Gemini model", Prefs.ttsModel(this))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        saveFields()
        super.onPause()
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

    private fun saveFields() {
        if (!::ttsBox.isInitialized) return
        val sp = Prefs.sp(this)
        val before = Prefs.key(this) + "|" + Prefs.name(this) + "|" + Prefs.model(this) + "|" + Prefs.voice(this)
        val voice = customBox.text.toString().trim().ifEmpty { Prefs.voice(this) }
        sp.edit()
            .putString("key", keyBox.text.toString().trim())
            .putString("name", nameBox.text.toString().trim().ifEmpty { "BITTO" })
            .putString("gmodel", modelBox.text.toString().trim().ifEmpty { "gemini-3.1-flash-lite" })
            .putString("ttsmodel", ttsBox.text.toString().trim().ifEmpty { "gemini-3.1-flash-tts-preview" })
            .putString("voice", voice)
            .apply()
        val after = Prefs.key(this) + "|" + Prefs.name(this) + "|" + Prefs.model(this) + "|" + Prefs.voice(this)
        if (before != after && BittoService.running) {
            try {
                stopService(Intent(this, BittoService::class.java))
                startForegroundService(Intent(this, BittoService::class.java))
            } catch (e: Exception) {}
        }
    }

    private fun refresh() {
        for ((t, ok) in checks) {
            val good = ok()
            t.text = (if (good) "✓  " else "○  ") + titles[t]
            t.setTextColor(if (good) 0xFF2ECC71.toInt() else pal.text)
        }
    }

    private fun head(s: String) {
        val t = TextView(this)
        t.text = s
        t.textSize = 13f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(pal.accent)
        t.setPadding(dp(4), dp(22), 0, dp(6))
        ll.addView(t)
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = roundBg(this, pal.card, 16)
        c.setPadding(dp(14), dp(8), dp(14), dp(8))
        ll.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return c
    }

    private fun field(p: LinearLayout, hint: String, v: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(v)
        e.textSize = 15f
        e.setTextColor(pal.text)
        e.setHintTextColor(pal.sub)
        e.setSingleLine(true)
        p.addView(e)
        return e
    }

    private fun row(p: LinearLayout, text: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 16f
        t.setTextColor(pal.text)
        t.setPadding(0, dp(12), 0, dp(12))
        t.setOnClickListener { onClick() }
        p.addView(t)
        return t
    }

    private fun setup(p: LinearLayout, title: String, ok: () -> Boolean, go: () -> Unit) {
        val t = row(p, title, go)
        checks.add(Pair(t, ok))
        titles[t] = title
    }

    private fun toggle(p: LinearLayout, text: String, key: String, def: Boolean) {
        val s = Switch(this)
        s.text = text
        s.textSize = 15f
        s.setTextColor(pal.text)
        s.isChecked = Prefs.sp(this).getBoolean(key, def)
        s.setPadding(0, dp(10), 0, dp(10))
        s.setOnCheckedChangeListener { _, on -> Prefs.sp(this).edit().putBoolean(key, on).apply() }
        p.addView(s)
    }
}
