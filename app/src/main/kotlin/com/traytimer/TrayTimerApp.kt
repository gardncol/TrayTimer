package com.traytimer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.app.NotificationManager
import com.traytimer.core.Graph
import com.traytimer.core.notifications.Channels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TrayTimerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Channels.createAll(this)
        // Android keeps a channel's sound immutable, so make sure the channel that
        // matches the stored alarm sound exists (custom sounds use their own id).
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val settings = Graph.settingsStore.current()
                Channels.ensureAlarmSound(this@TrayTimerApp, settings.alarmSoundUri)
            }
        }
        registerNotificationBlockReceiver()
    }

    /**
     * Blocked-channel awareness (FR-REM-15): react to app/channel block state changes
     * so the troubleshooting surface can prompt re-enable.
     */
    private fun registerNotificationBlockReceiver() {
        if (Build.VERSION.SDK_INT < 28) return
        val receiver = BlockStateReceiver()
        val filter = IntentFilter().apply {
            addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    class BlockStateReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            com.traytimer.core.logic.Analytics.log(
                "notification_block_state_changed",
                mapOf("action" to (intent?.action ?: ""))
            )
        }
    }
}