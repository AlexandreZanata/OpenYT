package com.openyt.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.openyt.ui.OpenYtApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as OpenYtApplication).container
        setContent {
            OpenYtApp(
                engine = container.engine,
                coordinator = container.coordinator,
                downloadsDirectory = container.downloadsDirectory,
            )
        }
    }
}
