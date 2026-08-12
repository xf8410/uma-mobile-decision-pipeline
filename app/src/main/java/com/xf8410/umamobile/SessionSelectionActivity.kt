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

class SessionSelectionActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 48)
        }
        status = TextView(this).apply {
            text = "正在读取 SO 历史 Session……"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(16, 16, 16, 24)
        }
        list.addView(status, matchWidth())
        setContentView(ScrollView(this).apply { addView(list) })
        loadSessions()
    }

    private fun loadSessions() {
        executor.execute {
            val result = runCatching { fetchSessions() }
            runOnUiThread {
                result.onSuccess(::renderSessions).onFailure { error ->
                    status.text = "读取失败\n${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                }
            }
        }
    }

    private fun renderSessions(sessions: JSONArray) {
        list.removeAllViews()
        val selected = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString(KEY_SELECTED_SESSION, null)
        list.addView(TextView(this).apply {
            text = "选择要同步的历史 Session\n优先选择 interrupted；open 仍在增长，不能做最终一致性验收。"
            textSize = 16f
            setPadding(16, 16, 16, 24)
        }, matchWidth())

        val values = (0 until sessions.length())
            .map { sessions.getJSONObject(it) }
            .sortedWith(
                compareBy<JSONObject> { it.optString("state") == "open" }
                    .thenByDescending { it.optLong("started_at_ms") }
            )
        values.forEach { session ->
            val id = session.getString("session_id")
            val state = session.optString("state", "unknown")
            val marker = if (id == selected) "✓ 已选择\n" else ""
            list.addView(Button(this).apply {
                isAllCaps = false
                text = buildString {
                    append(marker)
                    append("$id\n")
                    append("state=$state  version=${session.optString("plugin_version", "unknown")}\n")
                    append("started_at_ms=${session.optLong("started_at_ms")}")
                }
                setOnClickListener {
                    getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                        .putString(KEY_SELECTED_SESSION, id)
                        .putString(KEY_SELECTED_STATE, state)
                        .apply()
                    statusMessage(id, state)
                }
            }, matchWidth())
        }
        if (values.isEmpty()) {
            list.addView(TextView(this).apply { text = "SO 没有 Session" }, matchWidth())
        }
    }

    private fun statusMessage(id: String, state: String) {
        val message = if (state == "open") {
            "已选择 $id\n注意：该 Session 仍在写入，只适合增量同步。"
        } else {
            "已选择 $id\n该 Session 已停止写入，适合完整同步与终验。"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Session 已保存")
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> recreate() }
            .show()
    }

    private fun fetchSessions(): JSONArray {
        val path = "/storage/sessions"
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
            val root = JSONObject(body)
            if (!root.optBoolean("ok", false)) throw IllegalStateException(body)
            root.optJSONArray("sessions") ?: JSONArray()
        } finally {
            connection.disconnect()
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

    companion object {
        const val PREFERENCES = "collector_sessions"
        const val KEY_SELECTED_SESSION = "selected_session_id"
        const val KEY_SELECTED_STATE = "selected_session_state"
    }
}
