package com.seekaudio.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

// ─── Playlist ─────────────────────────────────────────────────────────────────

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val songIds: String = "",   // comma-separated song IDs
)

// ─── Playlist-Song cross ref ──────────────────────────────────────────────────

@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "songId"])
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: Long,
    val position: Int = 0,
)

// ─── Audio NFT (Solana) ───────────────────────────────────────────────────────

data class AudioNft(
    val id: String,
    val name: String,
    val artistName: String,
    val collection: String,
    val mintAddress: String,
    val audioUrl: String,
    val imageUrl: String,
    val durationMs: Long,
    val rarity: String,
)

// ─── Player State ─────────────────────────────────────────────────────────────

data class PlayerState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val progressMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val volume: Float = 1.0f,
    val audioSessionId: Int = 0,
)

enum class RepeatMode { OFF, ALL, ONE }

// ─── EQ State ────────────────────────────────────────────────────────────────

data class EqState(
    val enabled: Boolean = true,
    val bands: List<Float> = List(10) { 0f },
    val presetName: String = "Flat",
    val bassBoost: Boolean = false,
    val virtualizer: Boolean = false,
)

val EQ_PRESETS: Map<String, List<Float>> = mapOf(
    "Flat"       to listOf(0f,  0f,  0f,  0f,  0f,  0f,  0f,  0f,  0f,  0f),
    "Bass"       to listOf(6f,  5f,  4f,  2f,  0f,  0f,  0f,  0f,  0f,  0f),
    "Treble"     to listOf(0f,  0f,  0f,  0f,  2f,  4f,  5f,  6f,  6f,  5f),
    "Vocal"      to listOf(-2f, 0f,  2f,  4f,  4f,  4f,  2f,  0f,  0f,  0f),
    "Rock"       to listOf(4f,  3f,  2f,  0f, -1f,  0f,  2f,  3f,  4f,  4f),
    "Electronic" to listOf(5f,  4f,  0f, -2f, -2f,  0f,  4f,  4f,  5f,  5f),
    "Hip-Hop"    to listOf(5f,  3f,  0f,  1f, -1f,  0f,  0f,  2f,  3f,  3f),
    "Classical"  to listOf(0f,  0f,  0f,  0f,  0f,  0f, -1f, -2f, -2f, -3f),
)

// ─── Sleep Timer State ────────────────────────────────────────────────────────

data class SleepTimerState(
    val isActive: Boolean = false,
    val remainingMs: Long = 0L,
    val fadeOut: Boolean = true,
    val fadeOutDurationMs: Long = 15_000L,
)

// ─── Wallet State ─────────────────────────────────────────────────────────────

data class WalletState(
    val isConnected: Boolean = false,
    val walletName: String = "",
    val address: String = "",
    val solBalance: Double = 0.0,
    val skrBalance: Long = 0L,
    val skrPending: Long = 0L,
)

// ─── SKR Activity ─────────────────────────────────────────────────────────────

data class SkrActivity(
    val id: Long = System.currentTimeMillis(),
    val type: SkrActivityType,
    val label: String,
    val amount: String,
    val timeLabel: String,
    val icon: String,
)

enum class SkrActivityType { EARN, TIP }
