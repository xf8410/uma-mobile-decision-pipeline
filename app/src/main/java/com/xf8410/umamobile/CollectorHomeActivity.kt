package com.xf8410.umamobile

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

class CollectorHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Uma Collector"
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(TextView(context).apply {
                text = "手机原始 Session 采集"
                textSize = 22f
                setPadding(0, 0, 0, 32)
            }, matchWidth())
            addView(actionButton("连接 SO、开启 Hook 与同步", MainActivity::class.java), matchWidth())
            addView(actionButton("验证最新本地 Session", VerifySessionActivity::class.java), matchWidth())
            addView(actionButton("重新读取 SO 索引并终验", RemoteVerificationActivity::class.java), matchWidth())
        }
        setContentView(layout)
    }

    private fun actionButton(label: String, target: Class<*>) = Button(this).apply {
        text = label
        setOnClickListener { startActivity(Intent(context, target)) }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
