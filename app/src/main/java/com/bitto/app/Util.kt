package com.bitto.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object Prefs {
    fun sp(c: Context) = c.getSharedPreferences("bitto", Context.MODE_PRIVATE)
    fun key(c: Context): String = sp(c).getString("key", "") ?: ""
    fun name(c: Context): String = sp(c).getString("name", "BITTO") ?: "BITTO"
    fun model(c: Context): String = sp(c).getString("gmodel", "gemini-3.5-flash") ?: "gemini-3.5-flash"
}

object ModelStore {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    fun dir(c: Context) = File(c.filesDir, "model")
    fun ready(c: Context) = File(dir(c), "am").exists()

    fun download(c: Context, progress: (String) -> Unit) {
        val zip = File(c.cacheDir, "model.zip")
        val tmp = File(c.filesDir, "model_tmp")
        tmp.deleteRecursively()
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        val total = conn.contentLength.toLong()
        var done = 0L
        var lastMb = -1L
        conn.inputStream.use { input ->
            zip.outputStream().use { out ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val mb = done / 1000000
                    if (mb != lastMb) {
                        lastMb = mb
                        progress("Download: " + mb + " MB" + (if (total > 0) " / " + (total / 1000000) + " MB" else ""))
                    }
                }
            }
        }
        progress("Unzip ho raha hai...")
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val out = File(tmp, e.name)
                if (!out.canonicalPath.startsWith(tmp.canonicalPath)) throw SecurityException("bad zip")
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
                e = zin.nextEntry
            }
        }
        val inner = tmp.listFiles()?.firstOrNull { it.isDirectory } ?: throw Exception("model folder nahi mila")
        dir(c).deleteRecursively()
        inner.renameTo(dir(c))
        tmp.deleteRecursively()
        zip.delete()
        progress("Model ready")
    }
}
