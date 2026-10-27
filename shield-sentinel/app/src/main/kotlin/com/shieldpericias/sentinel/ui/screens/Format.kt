package com.shieldpericias.sentinel.ui.screens

import java.text.DateFormat
import java.util.Date

internal fun fmtDate(ms: Long?): String = if (ms == null) "—" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

internal fun fmtDuration(ms: Long?): String {
    if (ms == null) return "—"
    val totalMin = ms / 60_000
    val d = totalMin / (24 * 60)
    val h = (totalMin % (24 * 60)) / 60
    val m = totalMin % 60
    return buildString {
        if (d > 0) append("${d}d ")
        if (h > 0 || d > 0) append("${h}h ")
        append("${m}min")
    }
}
