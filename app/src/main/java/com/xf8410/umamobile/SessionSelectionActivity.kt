package com.xf8410.umamobile

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.io.File

class SessionSelectionActivity : ComponentActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 48)
        }
        setContentView(ScrollView(this).apply { addView(list) })
        renderLocalSessions()
    }

    private fun renderLocalSessions() {
        list.removeAllViews()
        val selected = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString(KEY_SELECTED_SESSION, null)
        list.addView(TextView(this).apply {
            text = "选择手机本地 Session\n该列表只读取 App 已下载的数据，不连接 SO。"
            textSize = 16f
            setPadding(16, 16, 16, 24)
        }, matchWidth())

        val sessionsRoot = File(filesDir, "sessions")
        val sessions = sessionsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.map { directory -> LocalSession(directory, readManifest(directory)) }
            ?.sortedWith(compareByDescending<LocalSession> { it.startedAtMs }.thenByDescending { it.directory.lastModified() })
            .orEmpty()

        sessions.forEach { session ->
            val id = session.directory.name
            val state = session.state
            val marker = if (id == selected) "✓ 已选择\n" else ""
            list.addView(Button(this).apply {
                isAllCaps = false
                text = buildString {
                    append(marker)
                    append(id)
                    append('\n')
                    append("state=$state")
                    append("  files=${session.fileCount}")
                    append("  bytes=${session.totalBytes}")
                }
                setOnClickListener {
                    getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                        .putString(KEY_SELECTED_SESSION, id)
                        .putString(KEY_SELECTED_STATE, state)
                        .apply()
                    android.app.AlertDialog.Builder(this@SessionSelectionActivity)
                        .setTitle("已选择本地 Session")
                        .setMessage(id)
                        .setPositiveButton("确定") { _, _ -> renderLocalSessions() }
                        .show()
                }
            }, matchWidth())
        }

        if (sessions.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "手机本地没有已下载 Session\n请先在 SO 可连接时下载一个 Session。"
                setPadding(16, 16, 16, 24)
            }, matchWidth())
        }
    }

    private fun readManifest(directory: File): JSONObject? = runCatching {
        val file = File(directory, "manifest.json")
        if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else null
    }.getOrNull()

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private data class LocalSession(
        val directory: File,
        val manifest: JSONObject?,
    ) {
        val state: String get() = manifest?.optString("session_state", "local")?.takeIf { it.isNotBlank() } ?: "local"
        val startedAtMs: Long get() = manifest?.optLong("started_at_ms", directory.lastModified()) ?: directory.lastModified()
        val fileCount: Int get() = manifest?.optInt("file_count", countRawFiles()) ?: countRawFiles()
        val totalBytes: Long get() = manifest?.optLong("total_bytes", sumRawBytes()) ?: sumRawBytes()
        private fun countRawFiles(): Int {
            val raw = File(directory, "raw")
            return if (raw.isDirectory) raw.walkTopDown().count { it.isFile && !it.name.endsWith(".part") } else 0
        }
        private fun sumRawBytes(): Long {
            val raw = File(directory, "raw")
            return if (raw.isDirectory) raw.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.sumOf { it.length() } else 0L
        }
    }

    companion object {
        const val PREFERENCES = "collector_sessions"
        const val KEY_SELECTED_SESSION = "selected_session_id"
        const val KEY_SELECTED_STATE = "selected_session_state"
    }
}
