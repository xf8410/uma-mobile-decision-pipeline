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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors

class SelectedSessionSyncActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "准备下载已选择的 Session"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 48)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            addView(Button(context).apply {
                text = "下载已选择的 Session 原始文件"
                setOnClickListener { startDownload() }
            }, matchWidth())
            addView(status, matchWidth())
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun startDownload() {
        status.text = "正在连接 SO 并下载已选择的 Session……"
        executor.execute {
            val result = runCatching { download() }.fold(
                { value -> value },
                { error -> "下载失败\n${error.javaClass.simpleName}: ${error.message.orEmpty()}" },
            )
            runOnUiThread { status.text = result }
        }
    }

    private fun download(): String {
        val preferences = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE)
        val sessionId = preferences.getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("尚未选择历史 Session，请先返回首页选择")
        val sessionRootResponse = getJson("/storage/sessions")
        val sessions = sessionRootResponse.optJSONArray("sessions") ?: JSONArray()
        val selected = (0 until sessions.length())
            .map { index -> sessions.getJSONObject(index) }
            .firstOrNull { item -> item.optString("session_id") == sessionId }
            ?: throw IllegalStateException("已选择的 Session 不存在：$sessionId")

        val records = loadFiles(sessionId)
        val root = File(filesDir, "sessions/$sessionId")
        val rawRoot = File(root, "raw").apply { mkdirs() }
        val manifestFiles = JSONArray()
        var downloaded = 0
        var resumed = 0
        var reused = 0
        var totalBytes = 0L

        records.forEach { record ->
            val result = downloadFile(rawRoot, record)
            when (result.mode) {
                "downloaded" -> downloaded += 1
                "resumed" -> resumed += 1
                "verified_existing" -> reused += 1
            }
            totalBytes += record.byteLength
            manifestFiles.put(JSONObject().apply {
                put("file_id", record.fileId)
                put("relative_path", record.relativePath)
                put("content_type", record.contentType)
                put("byte_length", record.byteLength)
                put("indexed_sha256", record.sha256 ?: JSONObject.NULL)
                put("local_sha256", result.sha256)
                put("created_at_ms", record.createdAtMs)
                put("sync_mode", result.mode)
            })
        }

        val manifest = JSONObject().apply {
            put("schema_version", 1)
            put("session_id", sessionId)
            put("session_state", selected.optString("state", "unknown"))
            put("plugin_version", selected.optString("plugin_version", "unknown"))
            put("started_at_ms", selected.optLong("started_at_ms"))
            put("source", "http://127.0.0.1:18765")
            put("file_count", records.size)
            put("total_bytes", totalBytes)
            put("downloaded", downloaded)
            put("resumed", resumed)
            put("verified_existing", reused)
            put("files", manifestFiles)
        }
        writeAtomically(File(root, "manifest.json"), manifest.toString(2).toByteArray(Charsets.UTF_8))
        return "下载完成\nsession_id=$sessionId\nstate=${selected.optString("state", "unknown")}\nfiles=${records.size}\nbytes=$totalBytes\ndownloaded=$downloaded\nresumed=$resumed\nverified_existing=$reused"
    }

    private fun loadFiles(sessionId: String): List<RemoteFile> {
        val result = ArrayList<RemoteFile>()
        var cursor = 0L
        while (true) {
            val encoded = URLEncoder.encode(sessionId, Charsets.UTF_8.name())
            val response = getJson("/storage/files?session_id=$encoded&cursor=$cursor&limit=1000")
            val files = response.optJSONArray("files") ?: JSONArray()
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                result += RemoteFile(
                    fileId = item.getLong("file_id"),
                    relativePath = item.getString("relative_path"),
                    contentType = item.getString("content_type"),
                    byteLength = item.getLong("byte_length"),
                    sha256 = item.optString("sha256").takeIf {
                        item.has("sha256") && !item.isNull("sha256") && it.isNotEmpty()
                    },
                    createdAtMs = item.getLong("created_at_ms"),
                )
            }
            val nextCursor = response.getLong("next_cursor")
            if (files.length() < 1000) break
            if (nextCursor <= cursor) throw IllegalStateException("file cursor did not advance")
            cursor = nextCursor
        }
        return result
    }

    private fun downloadFile(rawRoot: File, record: RemoteFile): DownloadResult {
        val relative = java.nio.file.Paths.get(record.relativePath)
        if (record.relativePath.isBlank() || relative.isAbsolute || relative.normalize().startsWith("..")) {
            throw IllegalStateException("invalid relative_path=${record.relativePath}")
        }
        val target = File(rawRoot, record.relativePath)
        if (target.isFile && target.length() == record.byteLength) {
            val hash = sha256(target)
            if (record.sha256 == null || hash.equals(record.sha256, ignoreCase = true)) {
                return DownloadResult("verified_existing", hash)
            }
        }
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        if (part.length() > record.byteLength) part.delete()
        var offset = part.length()
        val initialOffset = offset
        if (record.byteLength == 0L) {
            FileOutputStream(part, false).use { output -> output.fd.sync() }
        }
        while (offset < record.byteLength) {
            val length = minOf(CHUNK_BYTES, record.byteLength - offset)
            val response = readRange(record.fileId, offset, length)
            if (response.isEmpty()) throw IllegalStateException("empty range for file_id=${record.fileId}")
            FileOutputStream(part, true).use { output ->
                output.write(response)
                output.flush()
                output.fd.sync()
            }
            offset += response.size
        }
        if (part.length() != record.byteLength) throw IllegalStateException("length mismatch for file_id=${record.fileId}")
        val hash = sha256(part)
        if (record.sha256 != null && !hash.equals(record.sha256, ignoreCase = true)) {
            throw IllegalStateException("SHA-256 mismatch for file_id=${record.fileId}")
        }
        moveAtomically(part, target)
        val mode = if (initialOffset > 0L) "resumed" else "downloaded"
        return DownloadResult(mode, hash)
    }

    private fun getJson(path: String): JSONObject {
        val connection = open(path)
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("GET $path → HTTP $code\n$body")
            JSONObject(body).also { root ->
                if (!root.optBoolean("ok", false)) throw IllegalStateException(body)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readRange(fileId: Long, offset: Long, length: Long): ByteArray {
        val connection = open("/storage/read_range?file_id=$fileId&offset=$offset&length=$length")
        return try {
            if (connection.responseCode != 206) {
                throw IllegalStateException("range HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection =
        (URL("http://127.0.0.1:18765$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 30_000
            useCaches = false
        }

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

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        FileOutputStream(part, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        moveAtomically(part, target)
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
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

    private data class RemoteFile(
        val fileId: Long,
        val relativePath: String,
        val contentType: String,
        val byteLength: Long,
        val sha256: String?,
        val createdAtMs: Long,
    )

    private data class DownloadResult(val mode: String, val sha256: String)

    companion object {
        private const val CHUNK_BYTES = 1024L * 1024L
    }
}
