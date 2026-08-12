package com.xf8410.umamobile

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

class CollectorHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(TextView(context).apply { text = "手机原始 Session 采集"; textSize = 22f; setPadding(0, 0, 0, 32) }, matchWidth())
            addView(actionButton("选择历史 Session", SessionSelectionActivity::class.java), matchWidth())
            addView(actionButton("下载已选择的 Session 原始文件", SelectedSessionSyncActivity::class.java), matchWidth())
            addView(actionButton("连接 SO、开启 Hook", MainActivity::class.java), matchWidth())
            addView(actionButton("重新校验本地快照并重建索引", LocalSnapshotVerificationActivity::class.java), matchWidth())
            addView(actionButton("识别已下载 Session 原始包", RawFormatIdentificationActivity::class.java), matchWidth())
            addView(actionButton("验证最新本地 Session", VerifySessionActivity::class.java), matchWidth())
            addView(actionButton("重新读取 SO 索引并终验", RemoteVerificationActivity::class.java), matchWidth())
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }
    private fun actionButton(label: String, target: Class<*>) = Button(this).apply { text = label; setOnClickListener { startActivity(Intent(context, target)) } }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
