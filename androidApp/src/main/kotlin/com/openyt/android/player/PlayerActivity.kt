package com.openyt.android.player

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.openyt.ui.theme.OpenYtTheme
import java.io.File

class PlayerActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(
            0,
            (paths.size - 1).coerceAtLeast(0),
        )

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
                connected.setMediaItems(
                    paths.mapIndexed { index, path ->
                        MediaItem.Builder()
                            .setUri(Uri.fromFile(File(path)))
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(titles.getOrNull(index) ?: path)
                                    .build(),
                            )
                            .build()
                    },
                    startIndex,
                    0,
                )
                connected.prepare()
                connected.play()
                controller = connected
            },
            ContextCompat.getMainExecutor(this),
        )

        setContent {
            OpenYtTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Text(
                            titles.getOrNull(startIndex).orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                        AndroidView(
                            factory = { context -> PlayerView(context).apply { useController = true } },
                            update = { view -> view.player = controller },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATHS = "com.openyt.android.player.PATHS"
        const val EXTRA_TITLES = "com.openyt.android.player.TITLES"
        const val EXTRA_START_INDEX = "com.openyt.android.player.START_INDEX"
    }
}
