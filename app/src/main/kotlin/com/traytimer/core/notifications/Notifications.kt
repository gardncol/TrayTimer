package com.traytimer.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.traytimer.MainActivity
import com.traytimer.R
import com.traytimer.alarm.AlarmActivity
import com.traytimer.core.logic.Analytics
import com.traytimer.core.logic.DayMath

object Channels {
    const val REMINDERS = "reminders"
    const val ALARMS = "wearing_alarms"
    const val LIVE = "live_updates"

    fun createAll(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val reminders = NotificationChannel(
            REMINDERS,
            context.getString(R.string.channel_reminders),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_reminders_desc)
            enableVibration(true)
        }
        val alarms = NotificationChannel(
            ALARMS,
            context.getString(R.string.channel_alarms),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_alarms_desc)
            enableVibration(true)
            setSound(defaultAlarmSound(context), null)
        }
        val live = NotificationChannel(
            LIVE,
            context.getString(R.string.channel_live),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_live_desc)
            setShowBadge(false)
            setSound(null, null)
        }
        nm.createNotificationChannels(listOf(reminders, alarms, live))
    }

    fun defaultAlarmSound(context: Context): Uri =
        android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
            ?: android.provider.Settings.System.DEFAULT_NOTIFICATION_URI

    /** Resolves the user-selected alarm sound, falling back to the system alarm. */
    fun alarmSound(context: Context, uriString: String?): Uri {
        if (!uriString.isNullOrBlank()) {
            runCatching { return Uri.parse(uriString) }
        }
        return defaultAlarmSound(context)
    }

    /**
     * Android preserves a channel's sound if the same channel id is recreated, so a
     * custom sound gets its own channel id. Returns [ALARMS] for the default sound.
     */
    fun alarmChannelId(context: Context, uriString: String?): String {
        if (uriString.isNullOrBlank()) return ALARMS
        val uri = alarmSound(context, uriString)
        if (uri == defaultAlarmSound(context)) return ALARMS
        return "$ALARMS-${Integer.toHexString(uriString.hashCode())}"
    }

    /** Ensures the channel for the chosen alarm sound exists (and prunes old ones). */
    fun ensureAlarmSound(context: Context, uriString: String?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = alarmChannelId(context, uriString)
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    id,
                    context.getString(R.string.channel_alarms),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.channel_alarms_desc)
                    enableVibration(true)
                    setSound(alarmSound(context, uriString), null)
                }
            )
        }
        nm.notificationChannels
            .filter { it.id.startsWith("$ALARMS-") && it.id != id }
            .forEach { nm.deleteNotificationChannel(it.id) }
    }
}

object Notifications {

    const val NOTIF_PUT_BACK = 2001
    const val NOTIF_TRAY_CHANGE = 2002
    const val NOTIF_TRAY_TOTAL = 2003
    const val NOTIF_SELFIE = 2004
    const val NOTIF_APPOINTMENT_BASE = 2100
    const val NOTIF_LIVE = 3000

    fun contentIntent(context: Context, deepLink: String? = null): PendingIntent {
        val uri = android.net.Uri.parse(deepLink ?: "traytimer://app.traytimer.com/home")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(context.packageName)
            .setClass(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context, deepLink.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun builder(
        context: Context,
        channel: String,
        timeSensitive: Boolean
    ): NotificationCompat.Builder {
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_wearing)
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
        if (timeSensitive) {
            b.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
        }
        return b
    }

    fun post(context: Context, id: Int, notification: android.app.Notification) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, notification)
        Analytics.log("notification_posted", mapOf("id" to id))
    }

    fun cancel(context: Context, id: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(id)
    }

    /**
     * The put-back alarm uses a full-screen intent (FR-REM-06): shows over the lock
     * screen with the screen turned on; when the user is actively using the device it
     * arrives as a heads-up notification.
     */
    fun postPutBackAlarm(context: Context, outDuration: Long, showFullScreen: Boolean, channelId: String) {
        val launch = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(AlarmActivity.EXTRA_KIND, AlarmActivity.KIND_PUT_BACK)
        }
        val fullScreen = PendingIntent.getActivity(
            context, 4242, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun action(action: String, rc: Int) = PendingIntent.getBroadcast(
            context, rc,
            Intent(context, com.traytimer.reminders.ReminderReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = builder(context, channelId, true)
            .setContentTitle(context.getString(R.string.alarm_title))
            .setContentText("Trays have been out for ${DayMath.formatHm(outDuration)}.")
            .setContentIntent(fullScreen)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(0, "Start wearing", action(com.traytimer.reminders.AlarmScheduler.ACTION_START_WEARING, 5001))
            .addAction(0, "Add 5 min", action(com.traytimer.reminders.AlarmScheduler.ACTION_ADD_5, 5002))
            .addAction(0, "Add 10 min", action(com.traytimer.reminders.AlarmScheduler.ACTION_ADD_10, 5003))
        if (showFullScreen) {
            n.setFullScreenIntent(fullScreen, true)
        }
        post(context, NOTIF_PUT_BACK, n.build())
    }

    fun postTrayChange(context: Context, nextLabel: String, timeSensitive: Boolean) {
        val change = PendingIntent.getBroadcast(
            context, 5101,
            Intent(context, com.traytimer.reminders.ReminderReceiver::class.java)
                .setAction(com.traytimer.reminders.AlarmScheduler.ACTION_CHANGE_TRAY_CONFIRM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = builder(context, Channels.REMINDERS, timeSensitive)
            .setContentTitle(context.getString(R.string.rem_tray_change_title))
            .setContentText("Switch to aligner $nextLabel now.")
            .addAction(0, "Change tray", change)
            .build()
        post(context, NOTIF_TRAY_CHANGE, n)
    }

    fun postTrayTotal(context: Context, timeSensitive: Boolean) {
        val n = builder(context, Channels.REMINDERS, timeSensitive)
            .setContentTitle(context.getString(R.string.rem_tray_total_title))
            .setContentText(context.getString(R.string.rem_tray_total_body))
            .build()
        post(context, NOTIF_TRAY_TOTAL, n)
    }

    fun postSelfie(context: Context, timeSensitive: Boolean) {
        val n = builder(context, Channels.REMINDERS, timeSensitive)
            .setContentTitle(context.getString(R.string.rem_selfie_title))
            .setContentText(context.getString(R.string.rem_selfie_body))
            .setContentIntent(contentIntent(context, "traytimer://app.traytimer.com/take.photo"))
            .build()
        post(context, NOTIF_SELFIE, n)
    }

    fun postAppointment(context: Context, title: String, whenText: String, notifId: Int, timeSensitive: Boolean) {
        val n = builder(context, Channels.REMINDERS, timeSensitive)
            .setContentTitle(context.getString(R.string.rem_appointment_title))
            .setContentText(context.getString(R.string.rem_appointment_body, title, whenText))
            .build()
        post(context, notifId, n)
    }

    fun postDst(context: Context) {
        val n = builder(context, Channels.REMINDERS, true)
            .setContentTitle("TrayTimer")
            .setContentText(context.getString(R.string.alarm_dst))
            .build()
        post(context, 2999, n)
    }
}