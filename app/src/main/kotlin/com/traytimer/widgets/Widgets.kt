package com.traytimer.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.traytimer.R
import com.traytimer.core.Graph
import com.traytimer.core.logic.DayMath
import com.traytimer.core.logic.PlanEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Widget glue shared by the small (1x1 status) and medium (4x1 timer) widgets.
 * Push-refresh via APPWIDGET_REFRESH keeps them near-real-time (FR-WID-04) and the
 * 30-minute system updatePeriod is the fallback.
 */
object WidgetRefresher {

    const val ACTION_REFRESH = "com.traytimer.APPWIDGET_REFRESH"
    const val ACTION_TOGGLE = "com.traytimer.action.WIDGET_TOGGLE"
    const val ACTION_CHANGE_TRAY = "com.traytimer.action.WIDGET_CHANGE_TRAY"

    fun pushAll(context: Context) {
        val small = Intent(context, SmallWidgetReceiver::class.java).setAction(ACTION_REFRESH)
        val medium = Intent(context, MediumWidgetReceiver::class.java).setAction(ACTION_REFRESH)
        listOf(small, medium).forEach {
            it.setPackage(context.packageName)
            context.sendBroadcast(it)
        }
    }

    suspend fun renderSmall(context: Context, views: RemoteViews) {
        val open = Graph.timerRepository.getOpen()
        views.setImageViewResource(R.id.img_status, if (open == null) R.drawable.ic_wearing else R.drawable.ic_not_wearing)
    }

    suspend fun renderMedium(context: Context, views: RemoteViews) {
        val s = Graph.settingsStore.current()
        val zone = DayMath.zone(s.useDeviceTimeZone, s.chosenTimeZone)
        val today = java.time.LocalDate.now(zone)
        val windowStart = DayMath.dayStart(today, s.dayResetMinutes, zone)
        val sessions = Graph.timerRepository.getSessions()
        val now = System.currentTimeMillis()
        val open = Graph.timerRepository.getOpen()
        val wearing = open == null

        if (wearing) {
            val inMillis = DayMath.inMillis(sessions, windowStart, s.dayResetMinutes, now)
            views.setTextViewText(R.id.txt_state, context.getString(R.string.wd_wearing))
            views.setTextColor(R.id.txt_state, 0xFF5EEAD4.toInt())
            if (s.showTimeLeft) {
                val goal = s.wearGoalHours * 3_600_000L
                val left = (goal - inMillis).coerceAtLeast(0)
                views.setTextViewText(R.id.txt_time, context.getString(R.string.wd_time_left, DayMath.formatHm(left)))
            } else {
                views.setTextViewText(R.id.txt_time, DayMath.formatHm(inMillis))
            }
            views.setImageViewResource(R.id.img_status, R.drawable.ic_wearing)
            views.setImageViewResource(R.id.btn_toggle, R.drawable.ic_widget_pause)
        } else {
            val outFor = now - open!!.startEpoch
            views.setTextViewText(R.id.txt_state, context.getString(R.string.wd_not_wearing))
            views.setTextColor(R.id.txt_state, 0xFFEF5350.toInt())
            views.setTextViewText(R.id.txt_time, DayMath.formatHm(outFor))
            views.setImageViewResource(R.id.img_status, R.drawable.ic_not_wearing)
            views.setImageViewResource(R.id.btn_toggle, R.drawable.ic_wearing)
        }

        val trays = Graph.treatmentRepository.getTrays()
        val todayMidnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val current = PlanEngine.currentTray(trays, todayMidnight)
        views.setTextViewText(
            R.id.txt_tray,
            current?.let { context.getString(R.string.wd_tray, it.label, PlanEngine.daysLeftInTray(trays, todayMidnight)) }
                ?: context.getString(R.string.wd_tray_default)
        )
    }
}

/** Handles button presses from both widgets (toggle / change tray). */
class WidgetActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    WidgetRefresher.ACTION_TOGGLE -> {
                        Graph.timerRepository.toggle()
                        com.traytimer.reminders.AlarmScheduler.rescheduleAll(context)
                    }
                    WidgetRefresher.ACTION_CHANGE_TRAY -> {
                        val s = Graph.settingsStore.current()
                        com.traytimer.reminders.ReminderReceiver().changeToNextTray(context, s)
                    }
                }
                WidgetRefresher.pushAll(context)
            } finally {
                @Suppress("DEPRECATION")
                pending.finish()
            }
        }
    }
}

abstract class BaseWidgetReceiver : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetRefresher.ACTION_REFRESH ||
            intent.action == AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED
        ) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val mgr = AppWidgetManager.getInstance(context)
                    val ids = mgr.getAppWidgetIds(ComponentName(context, this::class.java))
                    onUpdate(context, mgr, ids)
                } finally {
                    @Suppress("DEPRECATION")
                    pending.finish()
                }
            }
        }
    }
}

/** Small 1x1 status widget — single glyph, tap to toggle (FR-WID-01/03). */
class SmallWidgetReceiver : BaseWidgetReceiver() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        CoroutineScope(Dispatchers.IO).launch {
            appWidgetIds.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_small)
                WidgetRefresher.renderSmall(context, views)
                views.setOnClickPendingIntent(
                    R.id.widget_small_root,
                    actionPendingIntent(context, WidgetRefresher.ACTION_TOGGLE, 10)
                )
                appWidgetManager.updateAppWidget(id, views)
            }
        }
    }

    private fun actionPendingIntent(context: Context, action: String, rc: Int) =
        android.app.PendingIntent.getBroadcast(
            context, rc,
            Intent(context, WidgetActionReceiver::class.java).setAction(action),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
}

/** Medium 4x1 resizable timer widget with actions (FR-WID-02/03). */
class MediumWidgetReceiver : BaseWidgetReceiver() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        CoroutineScope(Dispatchers.IO).launch {
            appWidgetIds.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_medium)
                WidgetRefresher.renderMedium(context, views)
                views.setOnClickPendingIntent(
                    R.id.btn_toggle,
                    actionPendingIntent(context, WidgetRefresher.ACTION_TOGGLE, 11)
                )
                views.setOnClickPendingIntent(
                    R.id.btn_change_tray,
                    actionPendingIntent(context, WidgetRefresher.ACTION_CHANGE_TRAY, 12)
                )
                views.setOnClickPendingIntent(
                    R.id.widget_medium_root,
                    android.app.PendingIntent.getActivity(
                        context, 13,
                        Intent(context, com.traytimer.MainActivity::class.java),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                )
                appWidgetManager.updateAppWidget(id, views)
            }
        }
    }

    private fun actionPendingIntent(context: Context, action: String, rc: Int) =
        android.app.PendingIntent.getBroadcast(
            context, rc,
            Intent(context, WidgetActionReceiver::class.java).setAction(action),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
}