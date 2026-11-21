package com.vanta.ffaimbot

import android.content.Context
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {

    private const val FILE_NAME = "aimbot.log"
    private const val MAX_LINES = 800

    private val lines = mutableListOf<String>()
    private var writer: BufferedWriter? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(ctx: Context) {
        try {
            val fos = ctx.openFileOutput(FILE_NAME, Context.MODE_APPEND)
            writer = BufferedWriter(OutputStreamWriter(fos))
            log("Logger", "--- session start ---")
        } catch (e: Exception) {
            // can't log if logger itself fails
        }
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "[${fmt.format(Date())}] [$tag] $msg"
        android.util.Log.d("FFAimbot", line)
        lines.add(line)
        if (lines.size > MAX_LINES) lines.removeAt(0)
        try {
            writer?.write(line)
            writer?.newLine()
            writer?.flush()
        } catch (e: Exception) { /* ignore */ }
    }

    @Synchronized
    fun getAll(): String = lines.joinToString("\n")

    @Synchronized
    fun clear(ctx: Context) {
        lines.clear()
        try {
            writer?.close()
            val fos = ctx.openFileOutput(FILE_NAME, Context.MODE_PRIVATE)
            writer = BufferedWriter(OutputStreamWriter(fos))
            log("Logger", "--- logs cleared ---")
        } catch (e: Exception) { /* ignore */ }
    }
}
