package com.seekaudio.data.model

import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Core Song entity — stored in Room and scanned from device storage.
 */
@Entity(tableName = "songs")
data class Song(
    @PrimaryKey
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,         // milliseconds
    val path: String,
    val uri: String,
    val genre: String = "",
    val year: Int = 0,
    val trackNumber: Int = 0,
    val size: Long = 0L,
    val dateAdded: Long = 0L,
    val isLiked: Boolean = false,
    val playCount: Int = 0,
    val lastPlayed: Long = 0L,
) {
    val durationFormatted: String
        get() {
            val totalSecs = duration / 1000
            val mins = totalSecs / 60
            val secs = totalSecs % 60
            return "%d:%02d".format(mins, secs)
        }

    val contentUri: Uri
        get() = Uri.parse(uri)
}
