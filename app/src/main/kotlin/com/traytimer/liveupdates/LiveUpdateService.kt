package com.traytimer.liveupdates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.traytimer.MainActivity
import com.traytimer.R
import com.traytimer.core.Graph
import com.traytimer.core.logic.DayMath
import com.traytimer.core.notifications.Channels
import com.traytimer.core.notifications.Notifications
import com.traytimer.reminders.AlarmScheduler
import com.traytimer.widgets.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live Updates (FR-LIV-01/02): a special-use foreground service that keeps a
 * persistent, ticking wear-timer notification in the status bar / lock screen
 * with quick start/pause actions. Re-armed by BootReceiver after restart.
 */
class LiveUpdateService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopUpdating()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        postNotification()
        startTicking()
        return START_STICKY
    }

    private fun startTicking() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(60_000L) // one update per minute is enough (progress style)
                postNotification()
            }
        }
    }

    private fun stopUpdating() {
        ticker?.cancel()
        ticker = null
    }

    private suspend fun buildText(): Triple<String, String, Boolean> {
        val s = Graph.settingsStore.current()
        val zone = DayMath.zone(s.useDeviceTimeZone, s.chosenTimeZone)
        val today = java.time.LocalDate.now(zone)
        val windowStart = DayMath.dayStart(today, s.dayResetMinutes, zone)
        val sessions = Graph.timerRepository.getSessions()
        val now = System.currentTimeMillis()
        val inMillis = DayMath.inMillis(sessions, windowStart, s.dayResetMinutes, now)
        val open = Graph.timerRepository.getOpen()
        val wearing = open == null
        val title = if (wearing) {
            getString(R.string.live_wearing, DayMath.formatHm(inMillis))
        } else {
            getString(R.string.live_out, DayMath.formatHm(now - open.startEpoch))
        }
        val action = if (wearing) getString(R.string.live_action_stop) else getString(R.string.live_action_start)
        return Triple(title, action, wearing)
    }

    private fun postNotification() {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val (title, actionLabel, wearing) = buildText()
            val openIntent = PendingIntent.getActivity(
                this@LiveUpdateService, 0,
                Intent(this@LiveUpdateService, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val toggleIntent = PendingIntent.getBroadcast(
                this@LiveUpdateService, 1,
                Intent(this@LiveUpdateService, com.traytimer.reminders.ReminderReceiver::class.java)
                    .setAction(AlarmScheduler.ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val goalMillis = Graph.settingsStore.current().wearGoalHours * 3_600_000L
            val sessions = Graph.timerRepository.getSessions()
            val zone = DayMath.zone(true, "")
            val windowStart = DayMath.dayStart(java.time.LocalDate.now(zone), Graph.settingsStore.current().dayResetMinutes, zone)
            val inMillis = DayMath.inMillis(sessions, windowStart, 0, System.currentTimeMillis())
            val progress = ((inMillis.toFloat() / goalMillis.toFloat()).coerceIn(0f, 1f) * 100f).toInt()

            val n = NotificationCompat.Builder(this@LiveUpdateService, Channels.LIVE)
                .setSmallIcon(if (wearing) R.drawable.ic_wearing else R.drawable.ic_not_wearing)
                .setContentTitle(title)
                .setContentText(getString(R.string.app_name))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setProgress(100, progress, false)
                .setContentIntent(openIntent)
                .addAction(0, actionLabel, toggleIntent)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    Notifications.NOTIF_LIVE, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(Notifications.NOTIF_LIVE, n)
            }
        }
    }

    override fun onDestroy() {
        stopUpdating()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.traytimer.action.LIVE_STOP"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, LiveUpdateService::class.java)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LiveUpdateService::class.java).setAction(ACTION_STOP)
            )
        }

        /** Re-post the notification immediately after a state change (from toggles anywhere). */
        fun refresh(context: Context, wearing: Boolean) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (nm.activeNotifications.any { it.id == Notifications.NOTIF_LIVE }) {
                context.startService(Intent(context, LiveUpdateService::class.java))
            }
        }
    }
}