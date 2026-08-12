package com.xf8410.umamobile

import android.graphics.Typeface
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File
import java.util.concurrent.Executors

class VerifySessionActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Session 完整性验证"
        resultText = TextView(this).apply {
            text = "正在重新读取全部本地文件并计算 SHA-256……"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(32, 48, 32, 48)
        }
        setContentView(resultText)
        executor.execute {
            val text = runCatching {
                LocalSessionVerification.verifyLatest(File(filesDir, "sessions")).render()
            }.getOrElse { error ->
                "完整性验证失败\n${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            }
            runOnUiThread { resultText.text = text }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
