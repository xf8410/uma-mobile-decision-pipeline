package com.xf8410.umamobile

import android.content.ContentValues
import android.graphics.Typeface
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CompleteSessionExportActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 48)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            addView(Button(context).apply {
                text = "导出完整 Session（包含 raw）"
                setOnClickListener { export() }
            }, width())
            addView(status, width())
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun export() {
        status.text = "正在打包完整 Session（不脱敏、不删文件）……"
        executor.execute {
            val result = try { exportSelectedSession() } catch (error: Exception) {
                "导出失败\n${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            }
            runOnUiThread { status.text = result }
        }
    }

    private fun exportSelectedSession(): String {
        val sessionId = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE)
            .getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("尚未选择历史 Session")
        val sessionRoot = File(filesDir, "sessions/$sessionId")
        if (!sessionRoot.isDirectory) throw IllegalStateException("本地不存在 Session：$sessionId")

        val files = sessionRoot.walkTopDown().filter { it.isFile }.toList()
        val displayName = "uma-session-$sessionId-complete.zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建 Download 文件")
        try {
            contentResolver.openOutputStream(uri, "w")!!.use { output ->
                ZipOutputStream(output).use { zip ->
                    files.forEach { file ->
                        val relative = sessionRoot.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                        if (relative.isBlank() || relative.startsWith("/") || relative.split('/').contains("..")) {
                            throw IllegalStateException("非法 Session 文件路径：$relative")
                        }
                        zip.putNextEntry(ZipEntry(relative))
                        file.inputStream().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
                    val manifest = JSONObject().apply {
                        put("schema_version", 1)
                        put("session_id", sessionId)
                        put("export_type", "complete_session")
                        put("raw_included", true)
                        put("decoded_included", File(sessionRoot, "decoded").isDirectory)
                        put("redaction", "none")
                        put("filtering", "none")
                        put("file_count", files.size)
                        put("exported_at_ms", System.currentTimeMillis())
                    }
                    zip.putNextEntry(ZipEntry("EXPORT-MANIFEST.json"))
                    zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
        return "完整 Session 导出完成\nsession_id=$sessionId\nfiles=${files.size}\nraw=包含\n脱敏=无\n过滤=无\n已保存到 Download/$displayName"
    }

    private fun width() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
