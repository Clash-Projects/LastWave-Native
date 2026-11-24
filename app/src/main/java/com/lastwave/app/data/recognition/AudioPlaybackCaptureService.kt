package com.lastwave.app.data.recognition

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lastwave.app.R
import kotlinx.coroutines.flow.MutableStateFlow

class AudioPlaybackCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType)
            isForegroundReady.value = true
        } catch (e: Exception) {
            isForegroundReady.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isForegroundReady.value = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Recognition",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Active while capturing audio for music recognition"
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_logo)
            .setContentTitle(getString(R.string.recognize_music))
            .setContentText(getString(R.string.recognize_listening))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "music_recognition_playback"
        private const val NOTIFICATION_ID = 8842

        val isForegroundReady = MutableStateFlow(false)

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, AudioPlaybackCaptureService::class.java))
            }
        }
    }
}
