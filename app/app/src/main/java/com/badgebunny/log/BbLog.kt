package com.badgebunny.log

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BbLog {
    private var writer: BufferedWriter? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile var logFile: File? = null; private set

    fun init(dir: File) {
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "relay_$ts.log")
        logFile = f
        writer = BufferedWriter(FileWriter(f, true))
        d("BB", "=== log started: ${f.absolutePath} ===")
    }

    fun d(tag: String, msg: String) { Log.d(tag, msg); write("D", tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); write("W", tag, msg) }
    fun e(tag: String, msg: String) { Log.e(tag, msg); write("E", tag, msg) }

    private fun write(level: String, tag: String, msg: String) {
        val w = writer ?: return
        try {
            synchronized(w) {
                w.write("${fmt.format(Date())} $level/$tag: $msg")
                w.newLine()
                w.flush()
            }
        } catch (_: Exception) {}
    }

    fun close() {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
    }
}
