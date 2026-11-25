package com.privvy.app.ui.tile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.privvy.app.security.SessionLockManager
import com.privvy.app.ui.MainActivity

class StealthTileService : TileService() {
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SessionLockManager.ACTION_SESSION_LOCKED) refreshTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        ContextCompat.registerReceiver(
            this,
            lockReceiver,
            IntentFilter(SessionLockManager.ACTION_SESSION_LOCKED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshTile()
    }

    override fun onStopListening() {
        runCatching { unregisterReceiver(lockReceiver) }
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        refreshTile()
        startActivityAndCollapse(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun refreshTile() {
        qsTile?.apply {
            val locked = SessionLockManager.isLocked(this@StealthTileService)
            state = if (locked) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            subtitle = if (locked) "Tap to sync" else "Sync active"
            updateTile()
        }
    }
}
