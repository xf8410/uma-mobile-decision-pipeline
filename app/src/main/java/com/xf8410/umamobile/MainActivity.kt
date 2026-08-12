package com.xf8410.umamobile

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Uma Mobile Collector\n\nSO: http://127.0.0.1:18765\n\nCollector implementation starts here."
            textSize = 18f
            setPadding(32, 48, 32, 32)
        })
    }
}
