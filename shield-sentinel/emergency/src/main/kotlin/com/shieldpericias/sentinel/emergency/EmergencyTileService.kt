package com.shieldpericias.sentinel.emergency

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings Tile. Por padrão abre a tela de confirmação do app (proteção
 * contra toque acidental). Com a tela bloqueada só executa diretamente se o
 * proprietário habilitou "permitir da tela de bloqueio" e sem confirmação.
 */
class EmergencyTileService : TileService() {

    override fun onStartListening() {
        val engine = EmergencyEntryPoint.provider?.invoke(applicationContext)
        val armed = engine?.config()?.armed == true
        qsTile?.apply {
            state = if (armed) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            label = getString(R.string.tile_label)
            subtitle = getString(if (armed) R.string.tile_subtitle_armed else R.string.tile_subtitle_disarmed)
            updateTile()
        }
    }

    override fun onClick() {
        val engine = EmergencyEntryPoint.provider?.invoke(applicationContext) ?: return
        val cfg = engine.config()
        if (!cfg.armed) return
        val configStore = EmergencyEntryPoint.configStoreProvider?.invoke(applicationContext)
        if (isLocked && configStore?.allowFromLockScreen == true && !cfg.requireConfirmation) {
            Thread { engine.trigger(TriggerSource.QUICK_SETTINGS_TILE, confirmed = true) }.start()
            return
        }
        val intent = Intent(EmergencyIntents.ACTION_EMERGENCY)
            .setPackage(packageName)
            .putExtra(EmergencyIntents.EXTRA_SOURCE, TriggerSource.QUICK_SETTINGS_TILE.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = Runnable {
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(open) else open.run()
    }
}
