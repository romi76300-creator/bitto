package com.bitto.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class BittoAccessibility : AccessibilityService() {
    companion object {
        var instance: BittoAccessibility? = null
    }

    private val h = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        h.postDelayed({ ensureRunning() }, 5000)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val now = System.currentTimeMillis()
        if (now - lastCheck > 120000) {
            lastCheck = now
            ensureRunning()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    private fun clickableParent(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var x = n
        var i = 0
        while (x != null && i < 8) {
            if (x.isClickable) return x
            x = x.parent
            i++
        }
        return null
    }

    fun clickText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (n in root.findAccessibilityNodeInfosByText(t)) {
            val c = clickableParent(n) ?: continue
            if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    fun clickAny(texts: List<String>, tries: Int, done: (Boolean) -> Unit) {
        if (texts.any { it.isNotBlank() && clickText(it) }) {
            done(true)
            return
        }
        if (tries <= 0) {
            done(false)
            return
        }
        h.postDelayed({ clickAny(texts, tries - 1, done) }, 500)
    }

    fun scroll(dir: String) {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val hh = m.heightPixels.toFloat()
        val p = Path()
        when (dir) {
            "up" -> { p.moveTo(w / 2, hh * 0.3f); p.lineTo(w / 2, hh * 0.7f) }
            "left" -> { p.moveTo(w * 0.25f, hh / 2); p.lineTo(w * 0.75f, hh / 2) }
            "right" -> { p.moveTo(w * 0.75f, hh / 2); p.lineTo(w * 0.25f, hh / 2) }
            else -> { p.moveTo(w / 2, hh * 0.7f); p.lineTo(w / 2, hh * 0.3f) }
        }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 300)).build()
        dispatchGesture(g, null, null)
    }

    private fun firstEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable || n.className?.toString() == "android.widget.EditText") return n
        for (i in 0 until n.childCount) {
            val r = firstEditable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(root) ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return true
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("bitto", text))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    fun typeRetry(text: String, tries: Int, done: (Boolean) -> Unit) {
        if (typeText(text)) {
            done(true)
            return
        }
        if (tries <= 0) {
            done(false)
            return
        }
        h.postDelayed({ typeRetry(text, tries - 1, done) }, 500)
    }

    private var lastCheck = 0L

    private fun ensureRunning() {
        try {
            if (!Prefs.sp(this).getBoolean("auto", false) || BittoService.running) return
            val i = Intent(this, MainActivity::class.java)
            i.putExtra("auto", true)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    fun submit(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(root) ?: return false
        if (Build.VERSION.SDK_INT >= 30) {
            return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return false
    }

    fun screenText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || sb.length > 1800 || d > 25) return
            val t = (n.text ?: n.contentDescription)?.toString()
            if (!t.isNullOrBlank() || n.isEditable) {
                sb.append((t ?: "").take(60))
                if (n.isClickable) sb.append(" [tap]")
                if (n.isEditable) sb.append(" [edit]")
                sb.append(" | ")
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    fun forceStop(pkg: String, done: (Boolean) -> Unit) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            done(false)
            return
        }
        h.postDelayed({
            clickAny(listOf("force stop", "फ़ोर्स स्टॉप", "ज़बरदस्ती रोकें"), 6) { ok1 ->
                if (!ok1) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    done(false)
                } else {
                    h.postDelayed({
                        clickAny(listOf("force stop", "ok", "ठीक"), 6) { ok2 ->
                            h.postDelayed({
                                performGlobalAction(GLOBAL_ACTION_HOME)
                                done(ok2)
                            }, 600)
                        }
                    }, 700)
                }
            }
        }, 1000)
    }
}
