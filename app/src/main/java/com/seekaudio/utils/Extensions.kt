package com.seekaudio.utils

import android.view.View

// ─── View visibility helpers ──────────────────────────────────────────────────

fun View.show() { visibility = View.VISIBLE }
fun View.hide() { visibility = View.GONE    }
fun View.invisible() { visibility = View.INVISIBLE }

// ─── Duration formatting ──────────────────────────────────────────────────────

/**
 * Format milliseconds as "M:SS" string.
 * e.g. 247_000ms → "4:07"
 */
fun formatDuration(ms: Long): String {
    val totalSecs = (ms / 1000).coerceAtLeast(0)
    val mins      = totalSecs / 60
    val secs      = totalSecs % 60
    return "%d:%02d".format(mins, secs)
}

/**
 * Format milliseconds as "H:MM:SS" for long durations.
 */
fun formatDurationLong(ms: Long): String {
    val totalSecs = (ms / 1000).coerceAtLeast(0)
    val hours     = totalSecs / 3600
    val mins      = (totalSecs % 3600) / 60
    val secs      = totalSecs % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, mins, secs)
    else "%d:%02d".format(mins, secs)
}

// ─── Number helpers ───────────────────────────────────────────────────────────

fun Long.formatCount(): String = when {
    this >= 1_000_000 -> "${"%.1f".format(this / 1_000_000.0)}M"
    this >= 1_000     -> "${this / 1_000}K"
    else              -> toString()
}

fun String.toShortAddress(): String =
    if (length > 10) "${take(6)}...${takeLast(4)}" else this
