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
        content.addView(status, width())
        setContentView(ScrollView(this).apply { addView(content) })
        load()
    }

    private fun load() {
        val id = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE)
            .getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)
        if (id.isNullOrBlank()) {
            status.text = "尚未选择历史 Session"
            return
        }
        status.text = "正在读取解码结果……"
        executor.execute {
            val result: ViewData = runCatching { buildView(id, File(filesDir, "sessions/$id/decoded")) }
                .fold(
                    onSuccess = { it },
                    onFailure = { error -> ViewData("读取失败\n${error.javaClass.simpleName}: ${error.message.orEmpty()}", emptyList()) },
                )
            runOnUiThread {
                content.removeAllViews()
                content.addView(status, width())
                status.text = result.summary
                result.files.forEach { addFileButton(it) }
            }
        }
    }

    private fun buildView(id: String, root: File): ViewData {
        if (!root.isDirectory) throw IllegalStateException("缺少 decoded/，请先执行解码")
        val filesRoot = root.resolve("files")
        val files = if (filesRoot.isDirectory) {
            filesRoot.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        } else {
            emptyList()
        }
        val errors = root.resolve("decode-errors.jsonl")
            .takeIf { it.isFile }
            ?.readLines()
            ?.count { it.isNotBlank() }
            ?: 0
        return ViewData("当前 Session：$id\n解码成功：${files.size}\n解码失败：$errors", files)
    }

    private fun addFileButton(file: File) {
        content.addView(Button(this).apply {
            isAllCaps = false
            text = file.relativeTo(File(filesDir, "sessions")).path
            setOnClickListener { showFile(file) }
        }, width())
    }

    private fun showFile(file: File) {
        val text = runCatching {
            JSONObject(file.readText(Charsets.UTF_8)).toString(2)
        }.getOrElse {
            file.readText(Charsets.UTF_8)
        }
        content.removeAllViews()
        content.addView(Button(this).apply {
            text = "返回解码文件列表"
            setOnClickListener { load() }
        }, width())
        content.addView(TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            this.text = text
            setPadding(16, 16, 16, 48)
        }, width())
    }

    private fun width() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private data class ViewData(val summary: String, val files: List<File>)
}
