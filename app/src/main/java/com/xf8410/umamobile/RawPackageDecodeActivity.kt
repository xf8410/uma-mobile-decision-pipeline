package com.xf8410.umamobile

import android.graphics.Typeface
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

class RawPackageDecodeActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 14f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(24, 24, 24, 48) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24)
            addView(Button(context).apply { text = "解码已识别原始包为 JSON"; setOnClickListener { startDecode() } }, width())
            addView(status, width())
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun startDecode() {
        status.text = "正在解码 raw，原始文件不会修改……"
        executor.execute {
            val result = runCatching { decodeSession() }.fold({ it }, { e -> "解码失败\n${e.javaClass.simpleName}: ${e.message.orEmpty()}" })
            runOnUiThread { status.text = result }
        }
    }

    private fun decodeSession(): String {
        val id = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE).getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("尚未选择历史 Session")
        val root = File(filesDir, "sessions/$id"); val raw = File(root, "raw")
        if (!raw.isDirectory) throw IllegalStateException("缺少本地 raw/，请先下载 Session")
        val out = File(root, "decoded/files"); out.mkdirs(); val errors = StringBuilder(); var success = 0; var failed = 0
        raw.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.forEach { file ->
            val relative = raw.toPath().relativize(file.toPath()).toString()
            try {
                val original = file.readBytes(); val result = decodeBytes(original)
                val target = File(out, "$relative.json"); target.parentFile?.mkdirs()
                val meta = JSONObject().apply { put("session_id", id); put("source_raw", "raw/$relative"); put("sha256", sha256(original)); put("format", result.format); put("decode_status", "success") }
                val wrapped = JSONObject().apply { put("_meta", meta); put("data", result.value) }
                atomicWrite(target, wrapped.toString(2).toByteArray(Charsets.UTF_8)); success++
            } catch (e: Exception) {
                failed++; errors.append(JSONObject().apply { put("source_raw", "raw/$relative"); put("decode_status", "error"); put("error", "${e.javaClass.simpleName}: ${e.message.orEmpty()}") }.toString()).append('\n')
            }
        }
        atomicWrite(File(root, "decoded/decode-errors.jsonl"), errors.toString().toByteArray(Charsets.UTF_8))
        return "解码完成\nsession_id=$id\nsuccess=$success\nfailed=$failed\nraw 未修改\ndecoded/files/ 与 decode-errors.jsonl 已更新"
    }

    private fun decodeBytes(original: ByteArray): Decoded {
        if (original.isEmpty()) return Decoded("empty", JSONObject())
        val bytes = if (isGzip(original)) GZIPInputStream(original.inputStream()).use { it.readBytes() } else original
        val text = bytes.toString(Charsets.UTF_8).trimStart()
        if (text.startsWith("{") || text.startsWith("[")) {
            return if (text.startsWith("{")) Decoded("json", JSONObject(text)) else Decoded("json", JSONArray(text))
        }
        val value = MsgPack(bytes).read()
        return Decoded(if (isGzip(original)) "gzip+msgpack" else "msgpack", value)
    }

    private fun isGzip(bytes: ByteArray) = bytes.size >= 2 && bytes[0].toInt() and 255 == 0x1f && bytes[1].toInt() and 255 == 0x8b
    private fun sha256(bytes: ByteArray): String { val d = MessageDigest.getInstance("SHA-256"); d.update(bytes); return d.digest().joinToString("") { "%02x".format(it) } }
    private fun atomicWrite(target: File, bytes: ByteArray) { target.parentFile?.mkdirs(); val part = File(target.parentFile, target.name + ".part"); FileOutputStream(part).use { it.write(bytes); it.fd.sync() }; try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) } catch (_: Exception) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) } }
    private fun width() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
    private data class Decoded(val format: String, val value: Any)

    private class MsgPack(private val b: ByteArray) {
        private var p = 0
        fun read(): Any { val c = u8(); return when {
            c <= 0x7f -> c
            c >= 0xe0 -> c - 256
            c in 0xa0..0xbf -> readString(c - 0xa0)
            c in 0x90..0x9f -> readArray(c - 0x90)
            c in 0x80..0x8f -> readMap(c - 0x80)
            c == 0xc0 -> JSONObject.NULL
            c == 0xc2 -> false
            c == 0xc3 -> true
            c == 0xca -> java.lang.Float.intBitsToFloat(i32()).toDouble()
            c == 0xcb -> java.lang.Double.longBitsToDouble(i64())
            c == 0xcc -> u8()
            c == 0xcd -> u16()
            c == 0xce -> u32()
            c == 0xcf -> i64()
            c == 0xd0 -> i8()
            c == 0xd1 -> i16()
            c == 0xd2 -> i32()
            c == 0xd3 -> i64()
            c in 0xc4..0xc6 -> JSONObject().apply { put("_type", "binary"); put("base64", Base64.encodeToString(readBytes(if (c == 0xc4) u8() else if (c == 0xc5) u16() else u32().toInt()), Base64.NO_WRAP)) }
            c in 0xd9..0xdb -> readString(if (c == 0xd9) u8() else if (c == 0xda) u16() else u32().toInt())
            c in 0xdc..0xdd -> readArray(if (c == 0xdc) u16() else u32().toInt())
            c in 0xde..0xdf -> readMap(if (c == 0xde) u16() else u32().toInt())
            else -> throw IllegalStateException("unsupported MessagePack marker 0x${c.toString(16)} at $p")
        } }
        private fun readArray(n: Int): JSONArray { val a = JSONArray(); repeat(n) { a.put(read()) }; return a }
        private fun readMap(n: Int): JSONObject { val o = JSONObject(); repeat(n) { val key = read().toString(); o.put(key, read()) }; return o }
        private fun readString(n: Int): String = String(readBytes(n), Charsets.UTF_8)
        private fun readBytes(n: Int): ByteArray { if (n < 0 || p + n > b.size) throw IllegalStateException("truncated MessagePack payload"); return b.copyOfRange(p, p + n).also { p += n } }
        private fun u8() = readBytes(1)[0].toInt() and 255
        private fun i8() = readBytes(1)[0].toInt()
        private fun u16() = (u8() shl 8) or u8()
        private fun i16() = (u16() shl 16 shr 16)
        private fun u32() = ((u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8()).toInt()
        private fun i32() = u32()
        private fun i64(): Long { var x = 0L; repeat(8) { x = (x shl 8) or u8().toLong() }; return x }
    }
}
