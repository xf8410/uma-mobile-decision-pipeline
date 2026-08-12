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

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusText: TextView
    private val operationButtons = ArrayList<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Uma Collector"

        statusText = TextView(this).apply {
            text = "等待连接 http://127.0.0.1:18765"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 48)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            addView(operationButton("检查 SO 健康与状态") { checkHealth() }, matchWidth())
            addView(operationButton("按固定顺序开启 Hook") { enableHooks() }, matchWidth())
            addView(operationButton("读取 Session 与完整文件索引") { loadSessions() }, matchWidth())
            addView(operationButton("同步最新 Session 原始文件") { syncLatestSession() }, matchWidth())
            addView(statusText, matchWidth())
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun operationButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
        operationButtons += this
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun checkHealth() = runOperation("正在检查 SO……") {
        val health = getText("/health")
        val status = getText("/status")
        buildString {
            appendLine("连接成功")
            appendLine("GET /health → HTTP ${health.code}")
            appendLine(health.body)
            appendLine("GET /status → HTTP ${status.code}")
            append(status.body)
        }
    }

    private fun enableHooks() = runOperation("正在执行固定 Hook 顺序……") {
        val steps = listOf(
            "/api/sniff/diag",
            "/api/md5log/install",
            "/api/md5log",
            "/api/md5log/clear",
            "/api/sniff/toggle?enabled=1",
            "/api/sniff/diag",
        )
        val responses = steps.map { path -> getText(path).also { requireSuccess(path, it) } }
        val diagnostic = JSONObject(responses.last().body)
        val required = linkedMapOf(
            "compress.hooked" to findBoolean(diagnostic, "compress", "hooked"),
            "decompress.hooked" to findBoolean(diagnostic, "decompress", "hooked"),
            "post.hooked" to findBoolean(diagnostic, "post", "hooked"),
            "makemd5.hooked" to findBoolean(diagnostic, "makemd5", "hooked"),
            "sniff_enabled" to findBoolean(diagnostic, "sniff_enabled"),
        )
        val failed = required.filterValues { it != true }.keys
        buildString {
            responses.forEachIndexed { index, response ->
                appendLine("${index + 1}. GET ${steps[index]} → HTTP ${response.code}")
                appendLine(response.body)
            }
            required.forEach { (name, value) -> appendLine("$name=$value") }
            if (failed.isEmpty()) append("Hook 最终诊断通过")
            else append("Hook 最终诊断未通过：${failed.joinToString()}")
        }
    }

    private fun loadSessions() = runOperation("正在读取 Session 与分页文件索引……") {
        val sessions = fetchSessions()
        var allFiles = 0L
        var allBytes = 0L
        buildString {
            appendLine("Session 数量：${sessions.length()}")
            for (index in 0 until sessions.length()) {
                val session = sessions.getJSONObject(index)
                val sessionId = session.getString("session_id")
                val files = loadAllFiles(sessionId)
                allFiles += files.size
                allBytes = Math.addExact(allBytes, files.sumOf { it.byteLength })
                appendLine("[$sessionId]")
                appendLine("state=${session.optString("state", "unknown")}")
                appendLine("plugin_version=${session.optString("plugin_version", "unknown")}")
                appendLine("files=${files.size}, bytes=${files.sumOf { it.byteLength }}")
            }
            append("合计 files=$allFiles, bytes=$allBytes")
        }
    }

    private fun syncLatestSession() = runOperation("正在同步最新 Session，可中断后继续……") {
        val sessions = fetchSessions()
        if (sessions.length() == 0) throw IllegalStateException("SO 没有 Session")
        var latest = sessions.getJSONObject(0)
        for (index in 1 until sessions.length()) {
            val candidate = sessions.getJSONObject(index)
            if (candidate.optLong("started_at_ms", Long.MIN_VALUE) > latest.optLong("started_at_ms", Long.MIN_VALUE)) {
                latest = candidate
            }
        }
        val sessionId = latest.getString("session_id")
        val files = loadAllFiles(sessionId)
        val sessionRoot = File(filesDir, "sessions/$sessionId").apply { mkdirs() }
        val rawRoot = File(sessionRoot, "raw").apply { mkdirs() }
        val records = JSONArray()
        var downloaded = 0
        var resumed = 0
        var reused = 0
        var totalBytes = 0L

        for (record in files) {
            val result = syncFile(rawRoot, record)
            when (result.mode) {
                "downloaded" -> downloaded += 1
                "resumed" -> resumed += 1
                "verified_existing" -> reused += 1
            }
            totalBytes = Math.addExact(totalBytes, record.byteLength)
            records.put(JSONObject().apply {
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
            put("source", "http://127.0.0.1:18765")
            put("file_count", files.size)
            put("total_bytes", totalBytes)
            put("downloaded", downloaded)
            put("resumed", resumed)
            put("verified_existing", reused)
            put("files", records)
        }
        atomicWrite(File(sessionRoot, "manifest.json"), manifest.toString(2).toByteArray(Charsets.UTF_8))
        "同步完成\nsession_id=$sessionId\nfiles=${files.size}\nbytes=$totalBytes\ndownloaded=$downloaded\nresumed=$resumed\nverified_existing=$reused\nmanifest=${File(sessionRoot, "manifest.json").absolutePath}"
    }

    private fun fetchSessions(): JSONArray {
        val path = "/storage/sessions"
        val response = getText(path)
        requireSuccess(path, response)
        val root = JSONObject(response.body)
        if (!root.optBoolean("ok", false)) throw IllegalStateException(response.body)
        return root.optJSONArray("sessions") ?: JSONArray()
    }

    private fun loadAllFiles(sessionId: String): List<RemoteFile> {
        val encoded = URLEncoder.encode(sessionId, Charsets.UTF_8.name())
        val result = ArrayList<RemoteFile>()
        var cursor = 0L
        while (true) {
            val path = "/storage/files?session_id=$encoded&cursor=$cursor&limit=1000"
            val response = getText(path)
            requireSuccess(path, response)
            val root = JSONObject(response.body)
            if (!root.optBoolean("ok", false)) throw IllegalStateException(response.body)
            val files = root.optJSONArray("files") ?: JSONArray()
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                val length = file.getLong("byte_length")
                if (length < 0L) throw IllegalStateException("negative byte_length")
                result += RemoteFile(
                    fileId = file.getLong("file_id"),
                    relativePath = file.getString("relative_path"),
                    contentType = file.getString("content_type"),
                    byteLength = length,
                    sha256 = file.optString("sha256").takeIf { file.has("sha256") && !file.isNull("sha256") && it.isNotEmpty() },
                    createdAtMs = file.getLong("created_at_ms"),
                )
            }
            val nextCursor = root.getLong("next_cursor")
            if (files.length() == 0 || files.length() < 1000) break
            if (nextCursor <= cursor) throw IllegalStateException("file cursor did not advance: $cursor → $nextCursor")
            cursor = nextCursor
        }
        return result
    }

    private fun syncFile(rawRoot: File, record: RemoteFile): SyncResult {
        val relative = java.nio.file.Paths.get(record.relativePath)
        if (relative.isAbsolute || relative.normalize().startsWith("..")) {
            throw IllegalStateException("invalid relative_path=${record.relativePath}")
        }
        val target = File(rawRoot, record.relativePath)
        val rootPath = rawRoot.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        if (!targetPath.startsWith(rootPath)) throw IllegalStateException("path escaped raw root")
        target.parentFile?.mkdirs()

        if (target.isFile && target.length() == record.byteLength) {
            val hash = sha256(target)
            if (record.sha256 == null || hash.equals(record.sha256, ignoreCase = true)) {
                return SyncResult("verified_existing", hash)
            }
        }

        val part = File(target.parentFile, target.name + ".part")
        if (part.length() > record.byteLength) part.delete()
        var offset = part.length()
        val initialOffset = offset
        if (record.byteLength == 0L) {
            FileOutputStream(part, false).use { it.fd.sync() }
        } else {
            while (offset < record.byteLength) {
                val requested = minOf(RANGE_CHUNK_BYTES, record.byteLength - offset)
                val range = getRange(record.fileId, offset, requested)
                if (range.fileLength != record.byteLength || range.start != offset || range.endExclusive != offset + range.bytes.size) {
                    throw IllegalStateException("range metadata mismatch for file_id=${record.fileId}")
                }
                if (range.bytes.isEmpty()) throw IllegalStateException("empty range before EOF for file_id=${record.fileId}")
                FileOutputStream(part, true).use { output ->
                    output.write(range.bytes)
                    output.flush()
                    output.fd.sync()
                }
                offset += range.bytes.size
            }
        }
        if (part.length() != record.byteLength) throw IllegalStateException("local length mismatch for file_id=${record.fileId}")
        val hash = sha256(part)
        if (record.sha256 != null && !hash.equals(record.sha256, ignoreCase = true)) {
            throw IllegalStateException("SHA-256 mismatch for file_id=${record.fileId}")
        }
        moveAtomically(part, target)
        return SyncResult(if (initialOffset > 0L) "resumed" else "downloaded", hash)
    }

    private fun getRange(fileId: Long, offset: Long, length: Long): RangeResult {
        val path = "/storage/read_range?file_id=$fileId&offset=$offset&length=$length"
        val connection = open(path)
        return try {
            val code = connection.responseCode
            if (code != 206 && !(code == 200 && length == 0L)) {
                val error = connection.errorStream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                throw IllegalStateException("GET $path → HTTP $code\n$error")
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            val fileLength = requiredHeaderLong(connection, "X-HLPATCH-File-Length")
            val start = requiredHeaderLong(connection, "X-HLPATCH-Range-Start")
            val end = requiredHeaderLong(connection, "X-HLPATCH-Range-End-Exclusive")
            if (bytes.size.toLong() != connection.contentLengthLong) throw IllegalStateException("HTTP Content-Length mismatch")
            RangeResult(bytes, fileLength, start, end)
        } finally {
            connection.disconnect()
        }
    }

    private fun requiredHeaderLong(connection: HttpURLConnection, name: String): Long =
        connection.getHeaderField(name)?.toLongOrNull() ?: throw IllegalStateException("missing or invalid $name")

    private fun atomicWrite(target: File, bytes: ByteArray) {
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

    private fun requireSuccess(path: String, response: HttpResult) {
        if (response.code !in 200..299) throw IllegalStateException("GET $path → HTTP ${response.code}\n${response.body}")
    }

    private fun runOperation(progress: String, operation: () -> String) {
        setBusy(true, progress)
        executor.execute {
            val result = runCatching(operation).fold(
                onSuccess = { it },
                onFailure = { error -> "操作失败\n${error.javaClass.simpleName}: ${error.message.orEmpty()}" },
            )
            runOnUiThread { setBusy(false, result) }
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        operationButtons.forEach { it.isEnabled = !busy }
        statusText.text = message
    }

    private fun open(path: String): HttpURLConnection =
        (URL("http://127.0.0.1:18765$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 30_000
            useCaches = false
        }

    private fun getText(path: String): HttpResult {
        val connection = open(path)
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            HttpResult(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun findBoolean(root: Any?, vararg path: String): Boolean? {
        if (path.isEmpty()) return root as? Boolean
        val key = path.first()
        val remaining = path.drop(1).toTypedArray()
        when (root) {
            is JSONObject -> {
                if (root.has(key)) findBoolean(root.opt(key), *remaining)?.let { return it }
                val keys = root.keys()
                while (keys.hasNext()) findBoolean(root.opt(keys.next()), *path)?.let { return it }
            }
            is JSONArray -> for (index in 0 until root.length()) findBoolean(root.opt(index), *path)?.let { return it }
        }
        return null
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private data class HttpResult(val code: Int, val body: String)
    private data class RemoteFile(
        val fileId: Long,
        val relativePath: String,
        val contentType: String,
        val byteLength: Long,
        val sha256: String?,
        val createdAtMs: Long,
    )
    private data class RangeResult(val bytes: ByteArray, val fileLength: Long, val start: Long, val endExclusive: Long)
    private data class SyncResult(val mode: String, val sha256: String)

    companion object {
        private const val RANGE_CHUNK_BYTES = 1024L * 1024L
    }
}
