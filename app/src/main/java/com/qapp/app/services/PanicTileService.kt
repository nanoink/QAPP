package com.qapp.app.services

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class PanicTileService : TileService() {
    override fun onClick() {
        super.onClick()
        CoreSecurityService.triggerPanic(applicationContext, "tile")
        qsTile?.state = Tile.STATE_ACTIVE
        qsTile?.updateTile()
    }
}
