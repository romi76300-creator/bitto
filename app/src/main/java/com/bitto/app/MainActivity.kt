package com.bitto.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var btn: TextView
    private lateinit var title: TextView
    private lateinit var pal: Pal
    private var dlText: String? = null
    private var starting = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pal = Themes.get(this)
        window.statusBarColor = pal.bg1
        window.navigationBarColor = pal.bg2
        val root = FrameLayout(this)
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pal.bg1, pal.bg2))
        setContentView(root)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        title = TextView(this)
        title.textSize = 30f
        title.typeface = Typeface.DEFAULT_BOLD
        title.letterSpacing = 0.25f
        title.setTextColor(pal.text)
        col.addView(title, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = dp(80) })

        orb = OrbView(this)
        orb.accent = pal.accent
        col.addView(orb, LinearLayout.LayoutParams(-1, 0, 1f))

        status = TextView(this)
        status.textSize = 16f
        status.setTextColor(pal.sub)
        status.gravity = Gravity.CENTER
        col.addView(status, LinearLayout.LayoutParams(-2, -2).also { it.bottomMargin = dp(24) })

        btn = TextView(this)
        btn.textSize = 18f
        btn.typeface = Typeface.DEFAULT_BOLD
        btn.gravity = Gravity.CENTER
        btn.setTextColor(0xFFFFFFFF.toInt())
        btn.background = roundBg(this, pal.accent, 32)
        btn.setOnClickListener { if (BittoService.running) stopAssistant() else start() }
        col.addView(btn, LinearLayout.LayoutParams(dp(220), dp(60)).also { it.bottomMargin = dp(60) })

        val gear = TextView(this)
        gear.text = "⚙"
        gear.textSize = 30f
        gear.setTextColor(pal.text)
        gear.setPadding(dp(14), dp(14), dp(14), dp(14))
        gear.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        val gl = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        gl.topMargin = dp(36)
        root.addView(gear, gl)

        if (intent.getBooleanExtra("auto", false)) {
            if (ModelStore.ready(this) && Prefs.key(this).isNotBlank() && !BittoService.running &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try { launch() } catch (e: Exception) {}
            }
            root.postDelayed({ moveTaskToBack(true) }, 1500)
        }
    }

    override fun onResume() {
        super.onResume()
        title.text = Prefs.name(this).uppercase()
        BittoService.onState = { s -> runOnUiThread { render(s) } }
        render(BittoService.state)
    }

    override fun onPause() {
        BittoService.onState = null
        super.onPause()
    }

    private fun render(level: Int) {
        val run = BittoService.running || starting
        orb.level = if (run) level else 0
        status.text = when {
            dlText != null -> dlText
            !run -> "Band hai"
            level == 0 -> "Tayyar ho rahi hu..."
            level == 1 -> "Bolo \"" + Prefs.name(this) + "\""
            level == 2 -> "Bolo, main sun rahi hu"
            level == 3 -> "Soch rahi hu..."
            else -> "Bol rahi hu..."
        }
        btn.text = if (run) "STOP" else "START"
    }

    private fun stopAssistant() {
        Prefs.sp(this).edit().putBoolean("auto", false).apply()
        stopService(Intent(this, BittoService::class.java))
        starting = false
        render(0)
    }

    private fun start() {
        if (Prefs.key(this).isBlank() || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Pehle Settings me key daalo aur permissions do", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (ModelStore.ready(this)) {
            launch()
        } else {
            dlText = "Awaaz ka model download ho raha hai (~40 MB)..."
            render(0)
            Thread {
                try {
                    ModelStore.download(this) { p -> runOnUiThread { dlText = p; render(0) } }
                    runOnUiThread { dlText = null; launch() }
                } catch (e: Exception) {
                    runOnUiThread { dlText = "Download fail: " + e.message; render(0) }
                }
            }.start()
        }
    }

    private fun launch() {
        Prefs.sp(this).edit().putBoolean("auto", true).apply()
        if (BittoService.running) stopService(Intent(this, BittoService::class.java))
        startForegroundService(Intent(this, BittoService::class.java))
        starting = true
        render(0)
        title.postDelayed({ starting = false; render(BittoService.state) }, 2500)
    }
}
