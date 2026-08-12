package com.xf8410.umamobile

import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class DecodedResultsActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
        }
        status = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(16, 16, 16, 24)
        }
        content.addView(status, layoutParams())
        setContentView(ScrollView(this).apply { addView(content) })
        load()
    }

    private fun load() {
        val sessionId = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE)
            .getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)
        if (sessionId.isNullOrBlank()) {
            status.text = "尚未选择历史 Session"
            return
        }
        status.text = "正在读取解码结果……"
        executor.execute {
            var summary = ""
            var files = emptyList<File>()
            try {
                val data = readResults(sessionId)
                summary = data.summary
                files = data.files
            } catch (error: Exception) {
                summary = "读取失败\n${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            }
            val resultSummary = summary
            val resultFiles = files
            runOnUiThread {
                content.removeAllViews()
                content.addView(status, layoutParams())
                status.text = resultSummary
                resultFiles.forEach { file -> addFileButton(file) }
            }
        }
    }

    private fun readResults(sessionId: String): Results {
        val decoded = File(filesDir, "sessions/$sessionId/decoded")
        if (!decoded.isDirectory) throw IllegalStateException("缺少 decoded/，请先执行解码")
        val filesRoot = File(decoded, "files")
        val files = if (filesRoot.isDirectory) {
            filesRoot.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        } else {
            emptyList()
        }
        val errorsFile = File(decoded, "decode-errors.jsonl")
        val errorCount = if (errorsFile.isFile) {
            errorsFile.readLines().count { it.isNotBlank() }
        } else {
            0
        }
        return Results("当前 Session：$sessionId\n解码成功：${files.size}\n解码失败：$errorCount", files)
    }

    private fun addFileButton(file: File) {
        content.addView(Button(this).apply {
            isAllCaps = false
            text = file.relativeTo(File(filesDir, "sessions")).path
            setOnClickListener { showFile(file) }
        }, layoutParams())
    }

    private fun showFile(file: File) {
        val displayed = try {
            JSONObject(file.readText(Charsets.UTF_8)).toString(2)
        } catch (_: Exception) {
            file.readText(Charsets.UTF_8)
        }
        content.removeAllViews()
        content.addView(Button(this).apply {
            text = "返回解码文件列表"
            setOnClickListener { load() }
        }, layoutParams())
        content.addView(TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            text = displayed
            setPadding(16, 16, 16, 48)
        }, layoutParams())
    }

    private fun layoutParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private data class Results(val summary: String, val files: List<File>)
}
