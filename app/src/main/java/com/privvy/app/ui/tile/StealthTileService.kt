package com.privvy.app.ui.tile

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.privvy.app.security.SessionLockManager
import com.privvy.app.ui.MainActivity

class StealthTileService : TileService() {
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action in setOf(
                    SessionLockManager.ACTION_SESSION_LOCKED,
                    SessionLockManager.ACTION_SESSION_STARTED,
                )
            ) {
                refreshTile()
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        ContextCompat.registerReceiver(
            this,
            lockReceiver,
            IntentFilter().apply {
                addAction(SessionLockManager.ACTION_SESSION_LOCKED)
                addAction(SessionLockManager.ACTION_SESSION_STARTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshTile()
    }

    override fun onStopListening() {
        runCatching { unregisterReceiver(lockReceiver) }
        super.onStopListening()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        refreshTile()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        qsTile?.apply {
            val active = SessionLockManager.isSessionActive(this@StealthTileService)
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (active) "Sync active" else "Tap to sync"
            updateTile()
        }
    }
}
