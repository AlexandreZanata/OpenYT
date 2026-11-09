package com.openyt.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.openyt.core.application.FileAvailability
import com.openyt.android.diagnostics.AndroidUrlOpener
import com.openyt.android.importer.AndroidMediaImporter
import com.openyt.android.share.AndroidMediaActions
import com.openyt.core.application.ShareTextParser
import com.openyt.ui.OpenYtApp
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val sharedUrl = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i(
            "OpenYT",
            "onCreate action=${intent?.action} hasText=${intent?.hasExtra(Intent.EXTRA_TEXT)} state=${savedInstanceState != null}",
        )
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }

        val container = (application as OpenYtApplication).container
        val mediaActions = AndroidMediaActions(this)
        val mediaImporter = AndroidMediaImporter(this, container.downloadsDirectory)
        val urlOpener = AndroidUrlOpener(this)
        setContent {
            val pendingSharedUrl by sharedUrl.collectAsState()
            OpenYtApp(
                engine = container.engine,
                coordinator = container.coordinator,
                settingsController = container.settingsController,
                libraryController = container.libraryController,
                mediaProbe = container.mediaProbe,
                mediaActions = mediaActions,
                mediaImporter = mediaImporter,
                diagnosticsProvider = container.diagnosticsProvider,
                updateChecker = container.updateChecker,
                urlOpener = urlOpener,
                downloadsDirectory = container.downloadsDirectory,
                fileAvailability = FileAvailability { java.io.File(it).isFile },
                initialSharedUrl = pendingSharedUrl,
                onSharedUrlConsumed = { sharedUrl.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        android.util.Log.i("OpenYT", "onNewIntent action=${intent.action} hasText=${intent.hasExtra(Intent.EXTRA_TEXT)}")
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val url = ShareTextParser.firstUrl(text)
        android.util.Log.i(
            "OpenYT",
            "share handled: hasText=${text != null} hasUrl=${url != null}",
        )
        sharedUrl.value = url
    }
}
