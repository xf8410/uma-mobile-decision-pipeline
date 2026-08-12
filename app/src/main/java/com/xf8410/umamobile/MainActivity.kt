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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusText: TextView
    private lateinit var healthButton: Button
    private lateinit var hookButton: Button
    private lateinit var sessionsButton: Button
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
        healthButton = operationButton("检查 SO 健康与状态") { checkHealth() }
        hookButton = operationButton("按固定顺序开启 Hook") { enableHooks() }
        sessionsButton = operationButton("读取 Session 与完整文件索引") { loadSessions() }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            addView(healthButton, matchWidth())
            addView(hookButton, matchWidth())
            addView(sessionsButton, matchWidth())
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
        val health = get("/health")
        val status = get("/status")
        buildString {
            appendLine("连接成功")
            appendLine()
            appendLine("GET /health → HTTP ${health.code}")
            appendLine(health.body)
            appendLine()
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
        val responses = ArrayList<HttpResult>(steps.size)
        for (path in steps) {
            val response = get(path)
            responses += response
            requireSuccess(path, response)
        }

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
                appendLine()
            }
            required.forEach { (name, value) -> appendLine("$name=$value") }
            if (failed.isEmpty()) append("Hook 最终诊断通过")
            else append("Hook 最终诊断未通过：${failed.joinToString()}")
        }
    }

    private fun loadSessions() = runOperation("正在读取 Session 与分页文件索引……") {
        val response = get("/storage/sessions")
        requireSuccess("/storage/sessions", response)
        val root = JSONObject(response.body)
        if (!root.optBoolean("ok", false)) {
            throw IllegalStateException(response.body)
        }
        val sessions = root.optJSONArray("sessions") ?: JSONArray()
        var allFiles = 0L
        var allBytes = 0L
        buildString {
            appendLine("Session 数量：${sessions.length()}")
            appendLine()
            for (index in 0 until sessions.length()) {
                val session = sessions.getJSONObject(index)
                val sessionId = session.getString("session_id")
                val files = loadAllFilePages(sessionId)
                allFiles += files.count
                allBytes += files.bytes
                appendLine("[$sessionId]")
                appendLine("state=${session.optString("state", "unknown")}")
                appendLine("plugin_version=${session.optString("plugin_version", "unknown")}")
                appendLine("files=${files.count}, bytes=${files.bytes}, pages=${files.pages}")
                appendLine("first_file_id=${files.firstFileId ?: "none"}, last_file_id=${files.lastFileId ?: "none"}")
                appendLine()
            }
            append("合计 files=$allFiles, bytes=$allBytes")
        }
    }

    private fun loadAllFilePages(sessionId: String): FileIndexSummary {
        val encoded = URLEncoder.encode(sessionId, Charsets.UTF_8.name())
        var cursor = 0L
        var pages = 0
        var count = 0L
        var bytes = 0L
        var firstFileId: Long? = null
        var lastFileId: Long? = null
        while (true) {
            val path = "/storage/files?session_id=$encoded&cursor=$cursor&limit=1000"
            val response = get(path)
            requireSuccess(path, response)
            val root = JSONObject(response.body)
            if (!root.optBoolean("ok", false)) throw IllegalStateException(response.body)
            val files = root.optJSONArray("files") ?: JSONArray()
            pages += 1
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                val fileId = file.getLong("file_id")
                val byteLength = file.getLong("byte_length")
                if (byteLength < 0L) throw IllegalStateException("negative byte_length for file_id=$fileId")
                if (firstFileId == null) firstFileId = fileId
                lastFileId = fileId
                count += 1
                bytes = Math.addExact(bytes, byteLength)
            }
            val nextCursor = root.getLong("next_cursor")
            if (files.length() == 0) break
            if (nextCursor <= cursor) throw IllegalStateException("file cursor did not advance: $cursor → $nextCursor")
            cursor = nextCursor
            if (files.length() < 1000) break
        }
        return FileIndexSummary(pages, count, bytes, firstFileId, lastFileId)
    }

    private fun requireSuccess(path: String, response: HttpResult) {
        if (response.code !in 200..299) {
            throw IllegalStateException("GET $path → HTTP ${response.code}\n${response.body}")
        }
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

    private fun get(path: String): HttpResult {
        val connection = URL("http://127.0.0.1:18765$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.useCaches = false
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
            is JSONArray -> for (index in 0 until root.length()) {
                findBoolean(root.opt(index), *path)?.let { return it }
            }
        }
        return null
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private data class HttpResult(val code: Int, val body: String)
    private data class FileIndexSummary(
        val pages: Int,
        val count: Long,
        val bytes: Long,
        val firstFileId: Long?,
        val lastFileId: Long?,
    )
}
