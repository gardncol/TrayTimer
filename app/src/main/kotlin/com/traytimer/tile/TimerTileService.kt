package com.traytimer.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.traytimer.core.Graph
import com.traytimer.core.logic.Analytics
import com.traytimer.reminders.AlarmScheduler
import com.traytimer.widgets.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Quick Settings tile (FR-QST-01..04): labeled "TrayTimer", toggleable, reflects
 * live wearing status and round-trips through the same repository as the app,
 * widgets and Live Updates.
 */
class TimerTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        refreshTile()
    }

    override fun onClick() {
        scope.launch {
            try {
                val wearing = Graph.timerRepository.toggle()
                Analytics.log("timer_toggled_from_tile", mapOf("wearing" to wearing))
                AlarmScheduler.rescheduleAll(applicationContext)
                WidgetRefresher.pushAll(applicationContext)
                refreshTile()
            } finally {
            }
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        scope.launch {
            val wearing = Graph.timerRepository.getOpen() == null
            if (wearing) {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Wearing"
                tile.icon = android.graphics.drawable.Icon.createWithResource(
                    applicationContext, com.traytimer.R.drawable.ic_wearing
                )
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Trays out"
                tile.icon = android.graphics.drawable.Icon.createWithResource(
                    applicationContext, com.traytimer.R.drawable.ic_not_wearing
                )
            }
            tile.updateTile()
        }
    }
}