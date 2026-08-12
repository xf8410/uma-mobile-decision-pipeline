package com.xf8410.umamobile

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

class SelectedSessionSyncActivity : ComponentActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 48)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            addView(status, matchWidth())
            addView(Button(context).apply {
                text = "重新选择历史 Session"
                setOnClickListener { startActivity(Intent(context, SessionSelectionActivity::class.java)) }
            }, matchWidth())
            addView(Button(context).apply {
                text = "下载已选择的 Session 原始文件"
                setOnClickListener { startDownload() }
            }, matchWidth())
        }
        setContentView(ScrollView(this).apply { addView(content) })
        renderSelection()
    }

    override fun onResume() {
        super.onResume()
        renderSelection()
    }

    private fun renderSelection() {
        val preferences = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE)
        val id = preferences.getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)
        val state = preferences.getString(SessionSelectionActivity.KEY_SELECTED_STATE, null)
        status.text = if (id.isNullOrBlank()) {
            "当前没有选择 Session\n请先点击“重新选择历史 Session”"
        } else {
            buildString {
                appendLine("当前选择的 Session")
                appendLine("session_id=$id")
                appendLine("state=${state ?: "unknown"}")
                if (state == "open") append("说明：open 只能做增量快照")
                else append("说明：非 open 适合完整同步与终验")
            }
        }
    }

    private fun startDownload() {
        val id = getSharedPreferences(SessionSelectionActivity.PREFERENCES, MODE_PRIVATE)
            .getString(SessionSelectionActivity.KEY_SELECTED_SESSION, null)
        if (id.isNullOrBlank()) {
            status.text = "尚未选择历史 Session，请先选择"
            return
        }
        status.text = "正在连接 SO 并下载已选择的 Session……"
        // Download implementation will be restored below without changing the selection UI.
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
