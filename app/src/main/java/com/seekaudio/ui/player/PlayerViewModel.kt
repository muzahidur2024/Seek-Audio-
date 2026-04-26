package com.seekaudio.ui.player

import android.content.ComponentName
import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.seekaudio.data.model.*
import com.seekaudio.data.repository.MediaRepository
import com.seekaudio.service.PlaybackService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
) : ViewModel() {

    // ── MediaController ───────────────────────────────────────────────────────
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    // ── Audio Effects (applied to audio session) ──────────────────────────────
    private var equalizer:   Equalizer?   = null
    private var bassBoostFx: BassBoost?   = null
    private var virtualizerFx: Virtualizer? = null

    // ── Player state ──────────────────────────────────────────────────────────
    private val _playerState   = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _currentSong   = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _queue         = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex  = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _progressMs    = MutableStateFlow(0L)
    val progressMs: StateFlow<Long> = _progressMs.asStateFlow()

    private var progressJob: Job? = null

    // ── EQ ───────────────────────────────────────────────────────────────────
    private val _eqState = MutableStateFlow(EqState())
    val eqState: StateFlow<EqState> = _eqState.asStateFlow()

    // ── Sleep Timer ───────────────────────────────────────────────────────────
    private val _sleepTimer = MutableStateFlow(SleepTimerState())
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()
    private var sleepJob: Job? = null

    // ── Library flows (from Room) ─────────────────────────────────────────────
    val allSongs       = mediaRepository.getAllSongs()       .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val likedSongs     = mediaRepository.getLikedSongs()     .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allArtists     = mediaRepository.getAllArtists()     .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allAlbums      = mediaRepository.getAllAlbums()      .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val mostPlayed     = mediaRepository.getMostPlayed()     .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val recentlyPlayed = mediaRepository.getRecentlyPlayed().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val searchResults = _searchQuery
        .debounce(300)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else mediaRepository.searchSongs(q)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Web3 ─────────────────────────────────────────────────────────────────
    private val _walletState = MutableStateFlow(WalletState())
    val walletState: StateFlow<WalletState> = _walletState.asStateFlow()

    private val _skrActivity = MutableStateFlow(listOf<SkrActivity>())
    val skrActivity: StateFlow<List<SkrActivity>> = _skrActivity.asStateFlow()

    private val _skrEarned = MutableStateFlow(0L)
    val skrEarned: StateFlow<Long> = _skrEarned.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // INIT — connect to service
    // ─────────────────────────────────────────────────────────────────────────

    @androidx.media3.common.util.UnstableApi
    fun initController() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            controller?.addListener(playerListener)
            startProgressTracking()
        }, MoreExecutors.directExecutor())
    }

    @androidx.media3.common.util.UnstableApi
    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            _playerState.update { it.copy(audioSessionId = audioSessionId) }
            initAudioEffects(audioSessionId)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playerState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                startProgressTracking()
                initAudioEffects()
            } else {
                stopProgressTracking()
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncCurrentSong()
            if (_walletState.value.isConnected) earnSkr(2)
        }
        override fun onRepeatModeChanged(repeatMode: Int) {
            _playerState.update {
                it.copy(repeatMode = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    else                   -> RepeatMode.OFF
                })
            }
        }
        override fun onShuffleModeEnabledChanged(enabled: Boolean) {
            _playerState.update { it.copy(shuffleEnabled = enabled) }
        }
    }

    private fun syncCurrentSong() {
        val ctrl = controller ?: return
        val idx  = ctrl.currentMediaItemIndex
        val q    = _queue.value
        if (idx in q.indices) {
            _currentSong.value  = q[idx]
            _currentIndex.value = idx
            viewModelScope.launch { mediaRepository.incrementPlayCount(q[idx].id) }
        }
    }

    private fun startProgressTracking() {
        stopProgressTracking()
        progressJob = viewModelScope.launch {
            while (isActive) {
                val pos = controller?.currentPosition ?: 0L
                val dur = controller?.duration?.takeIf { it > 0 } ?: 0L
                _progressMs.value = pos
                _playerState.update { it.copy(progressMs = pos, durationMs = dur) }
                delay(500)
            }
        }
    }

    private fun stopProgressTracking() { progressJob?.cancel(); progressJob = null }

    // ─────────────────────────────────────────────────────────────────────────
    // PLAYBACK COMMANDS
    // ─────────────────────────────────────────────────────────────────────────

    fun playSong(song: Song, queue: List<Song> = listOf(song)) {
        val ctrl = controller ?: return
        _queue.value = queue
        val items = queue.map { s ->
            MediaItem.Builder()
                .setUri(s.contentUri)
                .setMediaId(s.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist)
                        .setAlbumTitle(s.album)
                        .build()
                ).build()
        }
        val startIndex = queue.indexOf(song).coerceAtLeast(0)
        ctrl.setMediaItems(items, startIndex, 0L)
        ctrl.prepare()
        ctrl.play()
        _currentSong.value  = song
        _currentIndex.value = startIndex
    }

    fun playPause()  { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next()       { controller?.seekToNextMediaItem() }
    fun previous()   { controller?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() } }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _progressMs.value = positionMs
    }

    fun setVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
        _playerState.update { it.copy(volume = volume) }
    }

    fun setPlaybackSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        _playerState.update { it.copy(playbackSpeed = speed) }
    }

    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }

    fun cycleRepeat() {
        val ctrl = controller ?: return
        ctrl.repeatMode = when (ctrl.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else                   -> Player.REPEAT_MODE_OFF
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LIBRARY
    // ─────────────────────────────────────────────────────────────────────────

    fun setSearch(query: String) { _searchQuery.value = query }

    fun toggleLike(song: Song) {
        viewModelScope.launch { mediaRepository.setLiked(song.id, !song.isLiked) }
    }

    fun updateTags(songId: Long, title: String, artist: String, album: String, genre: String, year: Int) {
        viewModelScope.launch { mediaRepository.updateTags(songId, title, artist, album, genre, year) }
    }

    fun scanDevice() { viewModelScope.launch { mediaRepository.scanDevice() } }

    // ─────────────────────────────────────────────────────────────────────────
    // AUDIO EFFECTS (EQ, Bass Boost, Virtualizer)
    // ─────────────────────────────────────────────────────────────────────────

    @androidx.media3.common.util.UnstableApi
    private fun initAudioEffects(audioSessionId: Int? = null) {
        val sessionId = audioSessionId ?: _playerState.value.audioSessionId
        if (sessionId <= 0) return

        try {
            // If a specific audioSessionId is provided, we should re-initialize
            val forceReinit = audioSessionId != null && audioSessionId > 0

            if (equalizer == null || forceReinit) {
                equalizer?.release()
                equalizer = Equalizer(0, sessionId).apply { enabled = _eqState.value.enabled }
            }
            if (bassBoostFx == null || forceReinit) {
                bassBoostFx?.release()
                bassBoostFx = BassBoost(0, sessionId).apply { enabled = _eqState.value.bassBoost }
            }
            if (virtualizerFx == null || forceReinit) {
                virtualizerFx?.release()
                virtualizerFx = Virtualizer(0, sessionId).apply { enabled = _eqState.value.virtualizer }
            }
            applyEqToEffect()
        } catch (e: Exception) {
            android.util.Log.e("PlayerViewModel", "Failed to init audio effects", e)
        }
    }

    private fun applyEqToEffect() {
        val eq    = equalizer ?: return
        val bands = _eqState.value.bands
        try {
            val numBands = eq.numberOfBands.toInt()
            bands.take(numBands).forEachIndexed { i, db ->
                val milliDb = (db * 100).toInt().toShort() // convert dB to millibels
                eq.setBandLevel(i.toShort(), milliDb)
            }
        } catch (e: Exception) {
            // Suppress — some devices don't support all bands
        }
    }

    fun applyEqPreset(name: String) {
        val bands = EQ_PRESETS[name] ?: return
        _eqState.update { it.copy(bands = bands, presetName = name) }
        applyEqToEffect()
    }

    fun setEqBand(index: Int, value: Float) {
        val bands = _eqState.value.bands.toMutableList()
        if (index in bands.indices) bands[index] = value
        _eqState.update { it.copy(bands = bands, presetName = "Custom") }
        applyEqToEffect()
    }

    fun toggleBassBoost() {
        val enabled = !_eqState.value.bassBoost
        _eqState.update { it.copy(bassBoost = enabled) }
        bassBoostFx?.let {
            it.enabled = enabled
            if (enabled) it.setStrength(500)
        }
    }

    fun toggleVirtualizer() {
        val enabled = !_eqState.value.virtualizer
        _eqState.update { it.copy(virtualizer = enabled) }
        virtualizerFx?.let {
            it.enabled = enabled
            if (enabled) it.setStrength(500)
        }
    }

    fun toggleEq() {
        val enabled = !_eqState.value.enabled
        _eqState.update { it.copy(enabled = enabled) }
        equalizer?.enabled = enabled
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SLEEP TIMER
    // ─────────────────────────────────────────────────────────────────────────

    fun startSleepTimer(durationMs: Long, fadeOut: Boolean = true) {
        sleepJob?.cancel()
        val endTime = System.currentTimeMillis() + durationMs
        _sleepTimer.update { SleepTimerState(isActive = true, remainingMs = durationMs, fadeOut = fadeOut) }
        sleepJob = viewModelScope.launch {
            while (isActive) {
                val remaining = endTime - System.currentTimeMillis()
                if (remaining <= 0) {
                    controller?.pause()
                    _sleepTimer.update { SleepTimerState() }
                    break
                }
                _sleepTimer.update { it.copy(remainingMs = remaining) }
                delay(1000)
            }
        }
    }

    fun cancelSleepTimer() { sleepJob?.cancel(); _sleepTimer.update { SleepTimerState() } }

    fun startSleepAfterCurrentTrack() {
        val remaining = (controller?.duration ?: 0L) - (controller?.currentPosition ?: 0L)
        if (remaining > 0) startSleepTimer(remaining)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WEB3 / WALLET
    // ─────────────────────────────────────────────────────────────────────────

    fun connectWallet(walletName: String, address: String, solBalance: Double, skrBalance: Long, skrPending: Long) {
        _walletState.update { WalletState(
            isConnected  = true,
            walletName   = walletName,
            address      = address,
            solBalance   = solBalance,
            skrBalance   = skrBalance,
            skrPending   = skrPending,
        )}
        addSkrActivity(SkrActivity(type = SkrActivityType.EARN, label = "Wallet connected", amount = "+0 SKR", timeLabel = "just now", icon = "🔗"))
    }

    fun disconnectWallet() {
        _walletState.update { WalletState() }
        _skrEarned.value = 0
    }

    fun claimPendingSkr() {
        val pending = _walletState.value.skrPending
        if (pending > 0) {
            _walletState.update { it.copy(skrBalance = it.skrBalance + pending, skrPending = 0) }
            addSkrActivity(SkrActivity(type = SkrActivityType.EARN, label = "Claimed pending SKR", amount = "+$pending SKR", timeLabel = "just now", icon = "✅"))
        }
    }

    fun sendTip(artistName: String, amountSkr: Long) {
        if (!_walletState.value.isConnected || _walletState.value.skrBalance < amountSkr) return
        _walletState.update { it.copy(skrBalance = it.skrBalance - amountSkr) }
        addSkrActivity(SkrActivity(type = SkrActivityType.TIP, label = "Tipped $artistName", amount = "-$amountSkr SKR", timeLabel = "just now", icon = "💜"))
    }

    private fun earnSkr(amount: Long) {
        if (!_walletState.value.isConnected) return
        _skrEarned.update { it + amount }
        _walletState.update { it.copy(skrBalance = it.skrBalance + amount) }
    }

    private fun addSkrActivity(activity: SkrActivity) {
        _skrActivity.update { listOf(activity) + it }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLEANUP
    // ─────────────────────────────────────────────────────────────────────────

    @androidx.media3.common.util.UnstableApi
    override fun onCleared() {
        super.onCleared()
        stopProgressTracking()
        sleepJob?.cancel()
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        equalizer?.release()
        bassBoostFx?.release()
        virtualizerFx?.release()
    }
}
