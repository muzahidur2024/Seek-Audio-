package com.seekaudio.ui.player

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
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
    private data class PendingPlaybackRequest(
        val song: Song,
        val queue: List<Song>,
    )

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
    private val playbackPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private var restoredState = false
    private var lastPersistMs = 0L
    private var pendingPlaybackRequest: PendingPlaybackRequest? = null
    private var lastPlayCountSongId: Long? = null
    private var syncCurrentSongJob: Job? = null
    private var lastObservedMediaItemKey: String? = null

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
            syncPlayerStateFromController()
            syncCurrentSongFromController()
            restoreLastPlaybackIfNeeded()
            processPendingPlaybackIfAny()
            startProgressTracking()
        }, MoreExecutors.directExecutor())
    }

    private fun syncPlayerStateFromController() {
        val ctrl = controller ?: return
        val repeat = when (ctrl.repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
        val progress = ctrl.currentPosition.coerceAtLeast(0L)
        val duration = ctrl.duration.takeIf { it > 0L } ?: 0L
        _progressMs.value = progress
        _playerState.update {
            it.copy(
                isPlaying = ctrl.isPlaying,
                repeatMode = repeat,
                shuffleEnabled = ctrl.shuffleModeEnabled,
                progressMs = progress,
                durationMs = duration,
                volume = ctrl.volume,
                playbackSpeed = ctrl.playbackParameters.speed,
            )
        }
    }

    @androidx.media3.common.util.UnstableApi
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)
            ) {
                syncCurrentSongFromController()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            _playerState.update { it.copy(audioSessionId = audioSessionId) }
            initAudioEffects(audioSessionId)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playerState.update { it.copy(isPlaying = isPlaying) }
            persistPlaybackState(force = true)
            if (isPlaying) {
                startProgressTracking()
                initAudioEffects()
            } else {
                stopProgressTracking()
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncCurrentSongFromController()
            persistPlaybackState(force = true)
            if (_walletState.value.isConnected) earnSkr(2)
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            syncCurrentSongFromController()
            persistPlaybackState(force = true)
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

    private suspend fun syncCurrentSong() {
        val ctrl = controller ?: return
        val idx = ctrl.currentMediaItemIndex.coerceAtLeast(0)
        _currentIndex.value = idx

        val currentItem = ctrl.currentMediaItem
        val resolved = currentItem?.let { resolveSongFromMediaItem(it) }
        val fallback = currentItem?.let { buildFallbackSong(it, ctrl) }
        val song = resolved ?: fallback

        if (song != null) {
            _currentSong.value = song
            if (song.id > 0L && lastPlayCountSongId != song.id) {
                lastPlayCountSongId = song.id
                mediaRepository.incrementPlayCount(song.id)
            }
        }
    }

    private fun syncCurrentSongFromController() {
        val ctrl = controller ?: return
        syncCurrentSongJob?.cancel()
        syncCurrentSongJob = viewModelScope.launch {
            if (_queue.value.isEmpty()) {
                val controllerItems = (0 until ctrl.mediaItemCount).map { index ->
                    ctrl.getMediaItemAt(index)
                }
                val restoredQueue = controllerItems.mapNotNull { item ->
                    resolveSongFromMediaItem(item)
                }
                if (restoredQueue.isNotEmpty()) {
                    _queue.value = restoredQueue
                }
            }

            syncCurrentSong()
            lastObservedMediaItemKey = buildCurrentMediaItemKey(ctrl)
        }
    }

    private fun buildCurrentMediaItemKey(ctrl: MediaController): String {
        val idx = ctrl.currentMediaItemIndex
        val mediaId = ctrl.currentMediaItem?.mediaId.orEmpty()
        return "$idx|$mediaId"
    }

    private suspend fun resolveSongFromMediaItem(item: MediaItem): Song? {
        val id = item.mediaId.toLongOrNull()
        if (id != null && id > 0L) {
            mediaRepository.getSongById(id)?.let { return it }
        }
        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        if (uri.isNotBlank()) {
            mediaRepository.getSongByUri(uri)?.let { return it }
        }
        return null
    }

    private fun buildFallbackSong(item: MediaItem, ctrl: MediaController): Song {
        val md = item.mediaMetadata
        return Song(
            id = item.mediaId.toLongOrNull() ?: -1L,
            title = md.title?.toString().orEmpty().ifBlank { "Unknown Title" },
            artist = md.artist?.toString().orEmpty().ifBlank { "Unknown Artist" },
            album = md.albumTitle?.toString().orEmpty().ifBlank { "Unknown Album" },
            albumId = 0L,
            duration = ctrl.duration.takeIf { it > 0 } ?: 0L,
            path = "",
            uri = item.localConfiguration?.uri?.toString().orEmpty(),
        )
    }

    private fun startProgressTracking() {
        stopProgressTracking()
        progressJob = viewModelScope.launch {
            while (isActive) {
                val pos = controller?.currentPosition ?: 0L
                val dur = controller?.duration?.takeIf { it > 0 } ?: 0L
                _progressMs.value = pos
                _playerState.update { it.copy(progressMs = pos, durationMs = dur) }
                controller?.let { ctrl ->
                    val currentKey = buildCurrentMediaItemKey(ctrl)
                    if (currentKey != lastObservedMediaItemKey) {
                        syncCurrentSongFromController()
                    }
                }
                persistPlaybackState()
                delay(500)
            }
        }
    }

    private fun stopProgressTracking() { progressJob?.cancel(); progressJob = null }

    // ─────────────────────────────────────────────────────────────────────────
    // PLAYBACK COMMANDS
    // ─────────────────────────────────────────────────────────────────────────

    fun playSong(song: Song, queue: List<Song> = listOf(song)): Boolean {
        val ctrl = controller
        if (ctrl == null) {
            pendingPlaybackRequest = PendingPlaybackRequest(song = song, queue = queue)
            return false
        }
        playSongInternal(ctrl, song, queue)
        return true
    }

    private fun playSongInternal(ctrl: MediaController, song: Song, queue: List<Song>) {
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
        persistPlaybackState(force = true)
    }

    private fun processPendingPlaybackIfAny() {
        val request = pendingPlaybackRequest ?: return
        pendingPlaybackRequest = null
        val ctrl = controller ?: return
        playSongInternal(ctrl, request.song, request.queue)
    }

    fun playPause()  { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() {
        controller?.let { ctrl ->
            val count = ctrl.mediaItemCount
            if (count <= 1) {
                moveByLibraryOffset(+1)
                return
            }
            val current = ctrl.currentMediaItemIndex.coerceAtLeast(0)
            val target = when {
                current < count - 1 -> current + 1
                ctrl.repeatMode == Player.REPEAT_MODE_ALL -> 0
                else -> current
            }
            if (target != current) ctrl.seekToDefaultPosition(target)
        }
    }
    fun previous() {
        controller?.let { ctrl ->
            if (ctrl.currentPosition > 3000L) {
                ctrl.seekTo(0L)
                return
            }
            val count = ctrl.mediaItemCount
            if (count <= 1) {
                moveByLibraryOffset(-1)
                return
            }
            val current = ctrl.currentMediaItemIndex.coerceAtLeast(0)
            val target = when {
                current > 0 -> current - 1
                ctrl.repeatMode == Player.REPEAT_MODE_ALL -> count - 1
                else -> current
            }
            if (target != current) ctrl.seekToDefaultPosition(target) else ctrl.seekTo(0L)
        }
    }

    private fun moveByLibraryOffset(offset: Int) {
        viewModelScope.launch {
            val songs = mediaRepository.getAllSongsSnapshot()
                .sortedBy { it.title.lowercase() }
            if (songs.isEmpty()) return@launch
            val current = _currentSong.value
            val currentIndex = songs.indexOfFirst { song ->
                song.id == current?.id || (current != null && song.uri == current.uri)
            }.takeIf { it >= 0 } ?: 0
            val target = (currentIndex + offset).coerceIn(0, songs.lastIndex)
            if (target != currentIndex) {
                playSong(songs[target], songs)
            } else if (offset < 0) {
                seekTo(0L)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _progressMs.value = positionMs
        persistPlaybackState(force = true)
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
        val liked = !song.isLiked
        viewModelScope.launch {
            mediaRepository.setLiked(song.id, liked)
            if (_currentSong.value?.id == song.id) {
                _currentSong.update { it?.copy(isLiked = liked) }
            }
        }
    }

    fun enqueueSong(song: Song): Boolean {
        val ctrl = controller ?: return false
        val item = MediaItem.Builder()
            .setUri(song.contentUri)
            .setMediaId(song.id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .build()
            ).build()
        return runCatching {
            ctrl.addMediaItem(item)
            viewModelScope.launch {
                syncQueueWithController()
            }
            persistPlaybackState(force = true)
            true
        }.getOrDefault(false)
    }

    private suspend fun syncQueueWithController() {
        val ctrl = controller ?: return
        val rebuiltQueue = (0 until ctrl.mediaItemCount).mapNotNull { index ->
            resolveSongFromMediaItem(ctrl.getMediaItemAt(index))
        }
        if (rebuiltQueue.isNotEmpty()) {
            _queue.value = rebuiltQueue
        }
        syncCurrentSong()
    }

    private fun persistPlaybackState(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistMs < 2000L) return
        val song = _currentSong.value ?: return
        val ctrl = controller ?: return
        val positionMs = ctrl.currentPosition.coerceAtLeast(0L)
        playbackPrefs.edit()
            .putLong(KEY_LAST_SONG_ID, song.id)
            .putString(KEY_LAST_SONG_URI, song.uri)
            .putLong(KEY_LAST_POSITION_MS, positionMs)
            .putBoolean(KEY_LAST_WAS_PLAYING, ctrl.isPlaying)
            .apply()
        lastPersistMs = now
    }

    fun savePlaybackStateNow() {
        persistPlaybackState(force = true)
    }

    private fun restoreLastPlaybackIfNeeded() {
        if (restoredState) return
        restoredState = true
        if (pendingPlaybackRequest != null) return

        val ctrl = controller ?: return
        if (ctrl.mediaItemCount > 0) return

        val savedPosition = playbackPrefs.getLong(KEY_LAST_POSITION_MS, 0L).coerceAtLeast(0L)
        val savedSongId = playbackPrefs.getLong(KEY_LAST_SONG_ID, -1L)
        val savedSongUri = playbackPrefs.getString(KEY_LAST_SONG_URI, null).orEmpty()
        if (savedSongId <= 0L && savedSongUri.isBlank()) return

        viewModelScope.launch {
            val restoredSong = when {
                savedSongId > 0L -> mediaRepository.getSongById(savedSongId)
                savedSongUri.isNotBlank() -> mediaRepository.getSongByUri(savedSongUri)
                else -> null
            } ?: return@launch

            _queue.value = listOf(restoredSong)
            _currentSong.value = restoredSong
            _currentIndex.value = 0

            val item = MediaItem.Builder()
                .setUri(restoredSong.contentUri)
                .setMediaId(restoredSong.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(restoredSong.title)
                        .setArtist(restoredSong.artist)
                        .setAlbumTitle(restoredSong.album)
                        .build()
                )
                .build()

            ctrl.setMediaItem(item, savedPosition)
            ctrl.prepare()
            if (savedPosition > 0L) _progressMs.value = savedPosition
            // Always restore in paused state on app launch.
            ctrl.pause()
        }
    }

    suspend fun deleteSongFromDeviceAndLibrary(song: Song): Boolean {
        return mediaRepository.deleteFromDeviceAndLibrary(song)
    }

    suspend fun deleteSongFromLibrary(songId: Long) {
        mediaRepository.deleteFromLibrary(songId)
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
        val preferredSession = audioSessionId ?: _playerState.value.audioSessionId
        val candidateSessions = buildList {
            if (preferredSession > 0) add(preferredSession)
            if (_playerState.value.audioSessionId > 0) add(_playerState.value.audioSessionId)
            // Fallback for devices where player session is not exposed reliably.
            add(0)
        }.distinct()

        var initialized = false
        var lastError: Exception? = null

        for (sessionId in candidateSessions) {
            try {
                val forceReinit = audioSessionId != null
                if (equalizer == null || forceReinit) {
                    equalizer?.release()
                    equalizer = Equalizer(0, sessionId)
                }
                if (bassBoostFx == null || forceReinit) {
                    bassBoostFx?.release()
                    bassBoostFx = BassBoost(0, sessionId)
                }
                if (virtualizerFx == null || forceReinit) {
                    virtualizerFx?.release()
                    virtualizerFx = Virtualizer(0, sessionId)
                }
                applyEqToEffect()
                equalizer?.enabled = _eqState.value.enabled
                bassBoostFx?.enabled = _eqState.value.bassBoost
                virtualizerFx?.enabled = _eqState.value.virtualizer
                if (_eqState.value.bassBoost) bassBoostFx?.setStrength(500)
                if (_eqState.value.virtualizer) virtualizerFx?.setStrength(500)
                initialized = true
                break
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (!initialized) {
            android.util.Log.e("PlayerViewModel", "Failed to init audio effects on all session candidates", lastError)
        }
    }

    private fun applyEqToEffect() {
        val eq    = equalizer ?: return
        val bands = _eqState.value.bands
        try {
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val minMb = range.getOrNull(0)?.toInt() ?: -1500
            val maxMb = range.getOrNull(1)?.toInt() ?: 1500
            bands.take(numBands).forEachIndexed { i, db ->
                val milliDb = (db * 100).toInt().coerceIn(minMb, maxMb).toShort()
                eq.setBandLevel(i.toShort(), milliDb)
            }
        } catch (e: Exception) {
            android.util.Log.w("PlayerViewModel", "Failed applying EQ band levels", e)
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
        setBassBoostEnabled(!_eqState.value.bassBoost)
    }

    fun toggleVirtualizer() {
        setVirtualizerEnabled(!_eqState.value.virtualizer)
    }

    fun toggleEq() {
        setEqEnabled(!_eqState.value.enabled)
    }

    fun setEqEnabled(enabled: Boolean) {
        _eqState.update { it.copy(enabled = enabled) }
        equalizer?.enabled = enabled
    }

    fun setBassBoostEnabled(enabled: Boolean) {
        _eqState.update { it.copy(bassBoost = enabled) }
        bassBoostFx?.let {
            it.enabled = enabled
            if (enabled) it.setStrength(500)
        }
    }

    fun setVirtualizerEnabled(enabled: Boolean) {
        _eqState.update { it.copy(virtualizer = enabled) }
        virtualizerFx?.let {
            it.enabled = enabled
            if (enabled) it.setStrength(500)
        }
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
        persistPlaybackState(force = true)
        stopProgressTracking()
        sleepJob?.cancel()
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        equalizer?.release()
        bassBoostFx?.release()
        virtualizerFx?.release()
    }

    private companion object {
        const val PREFS_NAME = "playback_state"
        const val KEY_LAST_SONG_ID = "last_song_id"
        const val KEY_LAST_SONG_URI = "last_song_uri"
        const val KEY_LAST_POSITION_MS = "last_position_ms"
        const val KEY_LAST_WAS_PLAYING = "last_was_playing"
    }
}
