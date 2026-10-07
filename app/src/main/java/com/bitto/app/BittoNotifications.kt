package com.bitto.app

import android.service.notification.NotificationListenerService

class BittoNotifications : NotificationListenerService() {
    companion object {
        var instance: BittoNotifications? = null
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    fun recent(): String {
        val arr = try { activeNotifications } catch (e: Exception) { null }
        if (arr == null) return ""
        val sb = StringBuilder()
        for (n in arr.sortedByDescending { it.postTime }.take(8)) {
            if (n.packageName == packageName) continue
            val ex = n.notification.extras
            val t = ex.getCharSequence("android.title")?.toString() ?: ""
            val x = ex.getCharSequence("android.text")?.toString() ?: ""
            if (t.isBlank() && x.isBlank()) continue
            sb.append(n.packageName.substringAfterLast("."))
            sb.append(": ").append(t).append(" - ").append(x.take(100)).append(" | ")
        }
        return sb.toString()
    }
}
