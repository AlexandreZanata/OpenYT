package com.openyt.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import com.openyt.core.application.DownloadNotifier

class AndroidDownloadNotifier(
    private val context: Context,
    private val request: () -> Unit = {},
) : DownloadNotifier {
    override fun requestPermission() = request()

    override fun show(id: String, title: String, message: String, failed: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "openyt-downloads"
        manager.createNotificationChannel(NotificationChannel(channel, "OpenYT", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(id.hashCode(), Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("OpenYT · $message")
            .setContentText(title)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build())
    }
}
