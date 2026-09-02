package net.wastu.binderclip

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class BinderClipTileService : TileService() {
    companion object {
        fun requestUpdate(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, BinderClipTileService::class.java))
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, SendClipboardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                201,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val state = AppRuntime.state.value
        val isConnected = state.connectionPhase == ConnectionPhase.Connected
        tile.icon = Icon.createWithResource(this, R.drawable.ic_binder_clip)
        tile.label = getString(R.string.tile_send_clipboard)
        if (isConnected) {
            tile.state = Tile.STATE_ACTIVE
            tile.subtitle = state.peer?.name?.takeIf { it.isNotBlank() } ?: getString(R.string.status_connected)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = if (state.peer != null) getString(R.string.status_connecting) else getString(R.string.tile_not_paired)
        }
        tile.updateTile()
    }
}
