package com.traytimer.alarm

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.traytimer.R
import com.traytimer.core.logic.Analytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Rings while a full-screen wearing reminder is active (FR-REM-07) — a
 * mediaPlayback foreground service so the alarm stays audible even when the
 * app is backgrounded. Stops when the reminder is dismissed/snoozed (NFR-05).
 */
class AlarmRingingService : android.app.Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var pulseJob: Job? = null

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRinging()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startRinging()
        }
        return START_STICKY
    }

    private fun startRinging() {
        startForeground(
            Notifications.N_NOTIF_RINGING,
            NotificationCompat.Builder(this, com.traytimer.core.notifications.Channels.ALARMS)
                .setSmallIcon(R.drawable.ic_not_wearing)
                .setContentTitle(getText(R.string.alarm_title))
                .setContentText(getText(R.string.alarm_body))
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .build()
        )
        if (player == null) {
            val storedSound = kotlinx.coroutines.runBlocking {
                runCatching { com.traytimer.core.Graph.settingsStore.current().alarmSoundUri }
                    .getOrDefault("")
            }
            fun makePlayer(uri: android.net.Uri): MediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmRingingService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            val chosen = com.traytimer.core.notifications.Channels.alarmSound(this, storedSound)
            player = runCatching { makePlayer(chosen) }.getOrElse {
                // The selected file may have been removed; fall back to the system alarm.
                runCatching { makePlayer(com.traytimer.core.notifications.Channels.defaultAlarmSound(this)) }
                    .getOrNull()
            }
        }
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        pulseJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 600), -1))
                delay(1000)
            }
        }
        Analytics.log("alarm_ringing_started")
    }

    private fun stopRinging() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        pulseJob?.cancel()
        pulseJob = null
        vibrator?.cancel()
        Analytics.log("alarm_ringing_stopped")
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STOP = "stop"
        const val ACTION_START = "com.traytimer.action.RING_START"
        const val ACTION_STOP = "com.traytimer.action.RING_STOP"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, AlarmRingingService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AlarmRingingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

private object Notifications {
    const val N_NOTIF_RINGING = 1999
}