package com.xf8410.umamobile

import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

class RawFormatIdentificationActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 14f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(24, 24, 24, 48) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24)
            addView(Button(context).apply { text = "识别已下载 Session 原始包"; setOnClickListener { identify() } }, width())
            addView(status, width())
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun identify() {
        status.text = "正在扫描手机本地 raw……"
        executor.execute {
            val result = runCatching { runIdentification() }.fold({ it }, { e -> "识别失败\n${e.javaClass.simpleName}: ${e.message.orEmpty()}" })
            runOnUiThread { status.text = result }
        }
    }

    private fun runIdentification(): String {
        val id = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE)
            .getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("尚未选择历史 Session")
        val root = File(filesDir, "sessions/$id")
        val raw = File(root, "raw")
        if (!raw.isDirectory) throw IllegalStateException("缺少本地 raw/，请先下载 Session")
        val report = JSONArray()
        var count = 0
        raw.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.forEach { file ->
            report.put(inspect(raw, file)); count++
        }
        val output = JSONObject().apply {
            put("schema_version", 1); put("session_id", id); put("raw_preserved", true)
            put("file_count", count); put("files", report); put("identified_at_ms", System.currentTimeMillis())
        }
        atomicWrite(File(root, "decoded/format-report.json"), output.toString(2).toByteArray(Charsets.UTF_8))
        return "原始包格式识别完成\nsession_id=$id\nfiles=$count\nraw 未修改\ndecoded/format-report.json 已生成"
    }

    private fun inspect(raw: File, file: File): JSONObject {
        val bytes = file.inputStream().use { it.readNBytes(64) }
        val relative = raw.toPath().relativize(file.toPath()).toString()
        val gzip = bytes.size >= 2 && bytes[0].toInt() and 255 == 0x1f && bytes[1].toInt() and 255 == 0x8b
        val text = runCatching { file.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText(64 * 1024) } }.getOrNull()
        val trimmed = text?.trimStart()
        val json = trimmed?.let { it.startsWith("{") || it.startsWith("[") }
        val first = bytes.firstOrNull()?.toInt()?.and(255)
        val msgpackType = when {
            first == null -> "empty"
            first in 0x80..0x8f -> "map"
            first in 0x90..0x9f -> "array"
            first in 0xa0..0xbf -> "string"
            first in 0xc0..0xc1 -> "nil/boolean"
            first in 0xc4..0xc6 -> "binary"
            first in 0xca..0xcb -> "float"
            first in 0xcc..0xcf -> "integer"
            first in 0xd9..0xdb -> "string"
            first in 0xdc..0xdd -> "array"
            first in 0xde..0xdf -> "map"
            else -> null
        }
        val format = when { gzip -> "gzip"; json == true -> "json"; msgpackType != null -> "msgpack"; bytes.isEmpty() -> "empty"; else -> "unknown" }
        return JSONObject().apply {
            put("relative_path", relative); put("byte_length", file.length()); put("sha256", sha256(file))
            put("format", format); put("compression", if (gzip) "gzip" else "none")
            put("top_level_type", if (json == true) if (trimmed!!.startsWith("[")) "array" else "map" else msgpackType ?: JSONObject.NULL)
            put("decode_status", if (format == "unknown") "unknown" else "identified")
            put("raw_preserved", true)
        }
    }

    private fun sha256(file: File): String { val d = MessageDigest.getInstance("SHA-256"); file.inputStream().buffered().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }; return d.digest().joinToString("") { "%02x".format(it) } }
    private fun atomicWrite(target: File, bytes: ByteArray) { target.parentFile?.mkdirs(); val part = File(target.parentFile, target.name + ".part"); FileOutputStream(part).use { it.write(bytes); it.fd.sync() }; try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) } catch (_: Exception) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) } }
    private fun width() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
