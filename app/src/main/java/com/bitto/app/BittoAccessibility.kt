package com.bitto.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class BittoAccessibility : AccessibilityService() {
    companion object {
        var instance: BittoAccessibility? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}
