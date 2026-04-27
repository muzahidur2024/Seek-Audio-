package com.seekaudio.utils

import com.seekaudio.data.model.LyricLine

object LrcParser {
    private val timestampRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(content: String): List<LyricLine> {
        if (content.isBlank()) return emptyList()

        val lines = mutableListOf<LyricLine>()
        content.lineSequence().forEach { raw ->
            val matches = timestampRegex.findAll(raw).toList()
            if (matches.isEmpty()) return@forEach

            val text = raw.replace(timestampRegex, "").trim()
            if (text.isBlank()) return@forEach

            matches.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                val fractionRaw = match.groupValues[3]
                val fractionMs = when (fractionRaw.length) {
                    0 -> 0L
                    1 -> fractionRaw.toLongOrNull()?.times(100) ?: 0L
                    2 -> fractionRaw.toLongOrNull()?.times(10) ?: 0L
                    else -> fractionRaw.take(3).toLongOrNull() ?: 0L
                }
                val timeMs = minutes * 60_000L + seconds * 1_000L + fractionMs
                lines += LyricLine(timeMs = timeMs, text = text)
            }
        }

        return lines
            .distinctBy { "${it.timeMs}-${it.text}" }
            .sortedBy { it.timeMs }
    }
}
