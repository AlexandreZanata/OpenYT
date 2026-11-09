package com.clipbau.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.clipbau.ui.ClipBauApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val engine = (application as ClipBauApplication).container.engine
        setContent {
            ClipBauApp(engine = engine)
        }
    }
}
