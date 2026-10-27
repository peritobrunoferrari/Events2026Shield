package com.shieldpericias.sentinel.coresecurity

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.shieldpericias.sentinel.core.time.ClockSnapshot
import com.shieldpericias.sentinel.core.time.ClockSource

/** Relógio real: parede, monotônico e contador de boots do sistema. */
class AndroidClockSource(context: Context) : ClockSource {
    private val resolver = context.applicationContext.contentResolver

    override fun snapshot(): ClockSnapshot = ClockSnapshot(
        epochMs = System.currentTimeMillis(),
        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        bootCount = runCatching { Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1),
    )
}
