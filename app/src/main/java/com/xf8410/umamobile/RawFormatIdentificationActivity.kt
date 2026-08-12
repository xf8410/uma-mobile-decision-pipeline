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

class RawFormatIdentificationActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 48)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            addView(Button(context).apply {
                text = "识别已下载 Session 原始包"
                setOnClickListener { identify() }
            }, matchWidth())
            addView(status, matchWidth())
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun identify() {
        status.text = "正在扫描手机本地 raw……"
        executor.execute {
            val result = runCatching { runIdentification() }.fold(
                { it },
                { error -> "识别失败\n${error.javaClass.simpleName}: ${error.message.orEmpty()}" },
            )
            runOnUiThread { status.text = result }
        }
    }

    private fun runIdentification(): String {
        val sessionId = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE)
            .getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("尚未选择历史 Session")
        val root = File(filesDir, "sessions/$sessionId")
        val rawRoot = File(root, "raw")
        if (!rawRoot.isDirectory) throw IllegalStateException("缺少本地 raw/，请先下载 Session")
        val files = rawRoot.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.toList()
        val report = JSONArray()
        files.forEach { report.put(inspect(rawRoot, it)) }
        val output = JSONObject().apply {
            put("schema_version", 1)
            put("session_id", sessionId)
            put("raw_preserved", true)
            put("file_count", files.size)
            put("files", report)
            put("identified_at_ms", System.currentTimeMillis())
        }
        atomicWrite(File(root, "decoded/format-report.json"), output.toString(2).toByteArray(Charsets.UTF_8))
        return "原始包格式识别完成\nsession_id=$sessionId\nfiles=${files.size}\nraw 未修改\ndecoded/format-report.json 已生成"
    }

    private fun inspect(rawRoot: File, file: File): JSONObject {
        val header = file.inputStream().use { it.readNBytes(64) }
        val relative = rawRoot.toPath().relativize(file.toPath()).toString()
        val gzip = header.size >= 2 &&
            header[0].toInt() and 255 == 0x1f && header[1].toInt() and 255 == 0x8b
        val text = readUtf8Prefix(file)
        val trimmed = text?.trimStart()
        val isJson = trimmed?.let { it.startsWith("{") || it.startsWith("[") } == true
        val first = header.firstOrNull()?.toInt()?.and(255)
        val msgpackType = when {
            first == null -> "empty"
            first in 0x80..0x8f -> "map"
            first in 0x90..0x9f -> "array"
            first in 0xa0..0xbf -> "string"
            first == 0xc0 || first == 0xc2 || first == 0xc3 -> "nil/boolean"
            first in 0xc4..0xc6 -> "binary"
            first in 0xca..0xcb -> "float"
            first in 0xcc..0xcf -> "integer"
            first in 0xd9..0xdb -> "string"
            first in 0xdc..0xdd -> "array"
            first in 0xde..0xdf -> "map"
            else -> null
        }
        val format = when {
            gzip -> "gzip"
            isJson -> "json"
            msgpackType != null -> "msgpack"
            header.isEmpty() -> "empty"
            else -> "unknown"
        }
        return JSONObject().apply {
            put("relative_path", relative)
            put("byte_length", file.length())
            put("sha256", sha256(file))
            put("format", format)
            put("compression", if (gzip) "gzip" else "none")
            put("top_level_type", if (isJson) {
                if (trimmed!!.startsWith("[")) "array" else "map"
            } else {
                msgpackType ?: JSONObject.NULL
            })
            put("decode_status", if (format == "unknown") "unknown" else "identified")
            put("raw_preserved", true)
        }
    }

    private fun readUtf8Prefix(file: File): String? = runCatching {
        file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(64 * 1024)
            val count = reader.read(buffer)
            if (count <= 0) "" else String(buffer, 0, count)
        }
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        FileOutputStream(part, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
