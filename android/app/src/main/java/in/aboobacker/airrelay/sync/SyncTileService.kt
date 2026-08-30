package `in`.aboobacker.airrelay.sync

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile toggling the sync connection to the Mac on/off.
 */
class SyncTileService : TileService() {

    override fun onStartListening() {
        updateState()
    }

    override fun onClick() {
        val running = SyncService.instance != null
        if (running) {
            SyncService.stop(this)
        } else {
            SyncService.start(this)
        }
        qsTile?.apply {
            state = if (running) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            updateTile()
        }
    }

    private fun updateState() {
        qsTile?.apply {
            state = if (SyncService.instance != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
