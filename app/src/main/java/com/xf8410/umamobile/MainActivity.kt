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
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusText: TextView
    private lateinit var healthButton: Button
    private lateinit var hookButton: Button

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
        healthButton = Button(this).apply {
            text = "检查 SO 健康与状态"
            setOnClickListener { checkHealth() }
        }
        hookButton = Button(this).apply {
            text = "按固定顺序开启 Hook"
            setOnClickListener { enableHooks() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            addView(healthButton, matchWidth())
            addView(hookButton, matchWidth())
            addView(statusText, matchWidth())
        }
        setContentView(ScrollView(this).apply { addView(content) })
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
            if (response.code !in 200..299) {
                throw IllegalStateException("GET $path → HTTP ${response.code}\n${response.body}")
            }
        }

        val finalBody = responses.last().body
        val diagnostic = JSONObject(finalBody)
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
            if (failed.isEmpty()) {
                append("Hook 最终诊断通过")
            } else {
                append("Hook 最终诊断未通过：${failed.joinToString()}")
            }
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
        healthButton.isEnabled = !busy
        hookButton.isEnabled = !busy
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
                if (root.has(key)) {
                    findBoolean(root.opt(key), *remaining)?.let { return it }
                }
                val keys = root.keys()
                while (keys.hasNext()) {
                    findBoolean(root.opt(keys.next()), *path)?.let { return it }
                }
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
}
