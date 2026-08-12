package com.xf8410.umamobile

import android.graphics.Typeface
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class RemoteVerificationActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SO 索引终验"
        resultText = TextView(this).apply {
            text = "正在重新分页读取 SO 文件索引并与本地 manifest 比较……"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(32, 48, 32, 48)
        }
        setContentView(resultText)
        executor.execute {
            val text = runCatching { verifyLatest() }.getOrElse { error ->
                "SO 索引终验失败\n${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            }
            runOnUiThread { resultText.text = text }
        }
    }

    private fun verifyLatest(): String {
        val local = LocalSessionVerification.verifyLatest(File(filesDir, "sessions"))
        local.error?.let { throw IllegalStateException(it) }
        val sessionRoot = requireNotNull(local.sessionRoot)
        val localReport = requireNotNull(local.report)
        if (!localReport.complete) {
            return "SO 索引终验未执行\n本地完整性验证未通过\n${local.render()}"
        }
        val remote = loadAllFiles(localReport.sessionId)
        val errors = RemoteIndexVerifier.compare(File(sessionRoot, "manifest.json"), remote)
        return buildString {
            appendLine(if (errors.isEmpty()) "SO 索引终验通过" else "SO 索引终验失败")
            appendLine("session_id=${localReport.sessionId}")
            appendLine("remote_files=${remote.size}")
            appendLine("remote_bytes=${remote.fold(0L) { total, item -> Math.addExact(total, item.byteLength) }}")
            appendLine("local_path=${sessionRoot.absolutePath}")
            if (errors.isNotEmpty()) {
                appendLine("errors=${errors.size}")
                errors.forEach { appendLine(it) }
            }
        }.trimEnd()
    }

    private fun loadAllFiles(sessionId: String): List<RemoteIndexVerifier.RemoteRecord> {
        val encoded = URLEncoder.encode(sessionId, Charsets.UTF_8.name())
        val records = ArrayList<RemoteIndexVerifier.RemoteRecord>()
        var cursor = 0L
        while (true) {
            val path = "/storage/files?session_id=$encoded&cursor=$cursor&limit=1000"
            val root = getJson(path)
            if (!root.optBoolean("ok", false)) throw IllegalStateException(root.toString())
            val files = root.optJSONArray("files") ?: JSONArray()
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                val length = item.getLong("byte_length")
                if (length < 0) throw IllegalStateException("negative byte_length for file_id=${item.getLong("file_id")}")
                records += RemoteIndexVerifier.RemoteRecord(
                    fileId = item.getLong("file_id"),
                    relativePath = item.getString("relative_path"),
                    byteLength = length,
                    sha256 = item.optString("sha256").takeIf {
                        item.has("sha256") && !item.isNull("sha256") && it.isNotEmpty()
                    },
                )
            }
            val next = root.getLong("next_cursor")
            if (files.length() == 0 || files.length() < 1000) break
            if (next <= cursor) throw IllegalStateException("file cursor did not advance: $cursor → $next")
            cursor = next
        }
        return records
    }

    private fun getJson(path: String): JSONObject {
        val connection = URL("http://127.0.0.1:18765$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("GET $path → HTTP $code\n$body")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
