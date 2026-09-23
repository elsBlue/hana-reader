package com.hana.reader.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.hana.reader.MainActivity
import com.hana.reader.NotificationPermission
import com.hana.reader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ReadingService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        when (intent?.action) {
            ACTION_PAUSE -> HanaPlayer.get(this).pause()
            ACTION_PLAY -> HanaPlayer.get(this).toggle()
            ACTION_NEXT -> HanaPlayer.get(this).skipChapter(1)
            ACTION_PREV -> HanaPlayer.get(this).skipChapter(-1)
            ACTION_STOP -> {
                HanaPlayer.get(this).pause()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startAsForeground()
        if (job == null) {
            job = scope.launch {
                HanaPlayer.get(this@ReadingService).state.collect { startAsForeground() }
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val snap = HanaPlayer.get(this).state.value
        val title = snap.book?.title ?: getString(R.string.app_name)
        val text = when {
            snap.downloadProgress != null -> snap.status ?: "Downloading Hana voice…"
            snap.status != null -> snap.status
            else -> snap.book?.author ?: "Hana"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPause = PendingIntent.getService(
            this, 1,
            Intent(this, ReadingService::class.java).setAction(if (snap.playing) ACTION_PAUSE else ACTION_PLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val next = PendingIntent.getService(
            this, 2, Intent(this, ReadingService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prev = PendingIntent.getService(
            this, 3, Intent(this, ReadingService::class.java).setAction(ACTION_PREV),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(snap.playing)
            .setOnlyAlertOnce(true)
            .setStyle(MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Prev", prev)
            .addAction(
                if (snap.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (snap.playing) "Pause" else "Play",
                playPause
            )
            .addAction(android.R.drawable.ic_media_next, "Next", next)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, 7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(7, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Listening", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL = "hana_listening"
        const val ACTION_PLAY = "com.hana.reader.PLAY"
        const val ACTION_PAUSE = "com.hana.reader.PAUSE"
        const val ACTION_NEXT = "com.hana.reader.NEXT"
        const val ACTION_PREV = "com.hana.reader.PREV"
        const val ACTION_STOP = "com.hana.reader.STOP"

        fun start(context: Context) {
            NotificationPermission.requestOnceIfNeeded(context)
            val intent = Intent(context, ReadingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ReadingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
