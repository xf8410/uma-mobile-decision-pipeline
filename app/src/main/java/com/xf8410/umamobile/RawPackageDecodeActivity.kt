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
                val original = file.readBytes(); val decoded = decodeBytes(original)
                val target = File(out, "$relative.json"); target.parentFile?.mkdirs()
                val meta = JSONObject().apply { put("session_id", id); put("source_raw", "raw/$relative"); put("sha256", sha256(original)); put("format", decoded.format); put("decode_status", "success") }
                val wrapped = JSONObject().apply { put("_meta", meta); put("data", decoded.value) }
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
        val compressed = isGzip(original)
        val bytes = if (compressed) GZIPInputStream(original.inputStream()).use { it.readBytes() } else original
        val text = bytes.toString(Charsets.UTF_8).trimStart()
        if (text.startsWith("{") || text.startsWith("[")) {
            return if (text.startsWith("{")) Decoded(if (compressed) "gzip+json" else "json", JSONObject(text)) else Decoded(if (compressed) "gzip+json" else "json", JSONArray(text))
        }
        return Decoded(if (compressed) "gzip+msgpack" else "msgpack", MessagePackReader(bytes).read())
    }

    private fun isGzip(bytes: ByteArray) = bytes.size >= 2 && bytes[0].toInt() and 255 == 0x1f && bytes[1].toInt() and 255 == 0x8b
    private fun sha256(bytes: ByteArray): String { val d = MessageDigest.getInstance("SHA-256"); d.update(bytes); return d.digest().joinToString("") { "%02x".format(it) } }
    private fun atomicWrite(target: File, bytes: ByteArray) { target.parentFile?.mkdirs(); val part = File(target.parentFile, target.name + ".part"); FileOutputStream(part).use { it.write(bytes); it.fd.sync() }; try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) } catch (_: Exception) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) } }
    private fun width() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
    private data class Decoded(val format: String, val value: Any)

    private class MessagePackReader(private val bytes: ByteArray) {
        private var position = 0
        fun read(): Any {
            val code = readU8()
            return when {
                code <= 0x7f -> code
                code >= 0xe0 -> code - 256
                code in 0xa0..0xbf -> readString(code - 0xa0)
                code in 0x90..0x9f -> readArray(code - 0x90)
                code in 0x80..0x8f -> readMap(code - 0x80)
                code == 0xc0 -> JSONObject.NULL
                code == 0xc2 -> false
                code == 0xc3 -> true
                code == 0xca -> Float.fromBits(readI32()).toDouble()
                code == 0xcb -> Double.fromBits(readI64())
                code == 0xcc -> readU8()
                code == 0xcd -> readU16()
                code == 0xce -> readU32()
                code == 0xcf -> readI64()
                code == 0xd0 -> readI8()
                code == 0xd1 -> readI16()
                code == 0xd2 -> readI32()
                code == 0xd3 -> readI64()
                code in 0xc4..0xc6 -> readBinary(code)
                code in 0xd9..0xdb -> readString(if (code == 0xd9) readU8() else if (code == 0xda) readU16() else readU32())
                code in 0xdc..0xdd -> readArray(if (code == 0xdc) readU16() else readU32())
                code in 0xde..0xdf -> readMap(if (code == 0xde) readU16() else readU32())
                else -> throw IllegalStateException("unsupported MessagePack marker 0x${code.toString(16)} at $position")
            }
        }
        private fun readBinary(code: Int): JSONObject { val length = if (code == 0xc4) readU8() else if (code == 0xc5) readU16() else readU32(); return JSONObject().apply { put("_type", "binary"); put("base64", Base64.encodeToString(readBytes(length), Base64.NO_WRAP)) } }
        private fun readArray(count: Int): JSONArray { if (count < 0) throw IllegalStateException("negative array length"); val result = JSONArray(); repeat(count) { result.put(read()) }; return result }
        private fun readMap(count: Int): JSONObject { if (count < 0) throw IllegalStateException("negative map length"); val result = JSONObject(); repeat(count) { result.put(read().toString(), read()) }; return result }
        private fun readString(length: Int): String = String(readBytes(length), Charsets.UTF_8)
        private fun readBytes(length: Int): ByteArray { if (length < 0 || position > bytes.size - length) throw IllegalStateException("truncated MessagePack payload"); return bytes.copyOfRange(position, position + length).also { position += length } }
        private fun readU8(): Int = readBytes(1)[0].toInt() and 255
        private fun readI8(): Int = readBytes(1)[0].toInt()
        private fun readU16(): Int = (readU8() shl 8) or readU8()
        private fun readI16(): Int = (readU16() shl 16) shr 16
        private fun readU32(): Int = ((readU8().toLong() shl 24) or (readU8().toLong() shl 16) or (readU8().toLong() shl 8) or readU8().toLong()).toInt()
        private fun readI32(): Int = readU32()
        private fun readI64(): Long { var value = 0L; repeat(8) { value = (value shl 8) or readU8().toLong() }; return value }
    }
}
