package com.clipfy.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "FFmpegLibraryService"

    fun createProgressNotification(
        context: Context,
        title: String,
        progress: Float,
        iconRes: Int
    ): Notification {
        createNotificationChannel(context)

        val cancelIntent = Intent(context, CancelProcessingReceiver::class.java).apply {
            action = CancelProcessingReceiver.ACTION_CANCEL_PROCESSING
        }

        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText("%.1f%%".format(progress))
            .setProgress(100, progress.toInt(), false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Processing Speed",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            setShowBadge(true)
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}