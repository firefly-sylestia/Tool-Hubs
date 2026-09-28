package com.sylestia.toolhubs.networkswitcher

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class NetworkReconnectTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        val running = getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE).getBoolean(AutoReconnectService.PREF_AUTO_ENABLED, false)
        if (running) {
            stopService(Intent(this, AutoReconnectService::class.java).apply {
                action = AutoReconnectService.ACTION_STOP
            })
        } else {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AutoReconnectService::class.java).apply {
                    action = AutoReconnectService.ACTION_START
                }
            )
        }
        tile.state = if (running) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }

    private fun updateTile() {
        qsTile?.apply {
            state = if (getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE).getBoolean(AutoReconnectService.PREF_AUTO_ENABLED, false)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "5G Reconnect"
            subtitle = if (state == Tile.STATE_ACTIVE) "On" else "Off"
            updateTile()
        }
    }
}
