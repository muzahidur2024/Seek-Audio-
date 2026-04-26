package com.seekaudio.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.seekaudio.R
import com.seekaudio.data.model.RepeatMode
import com.seekaudio.data.repository.MediaRepository
import com.seekaudio.databinding.FragmentPlayerBinding
import com.seekaudio.ui.driving.DrivingActivity
import com.seekaudio.utils.formatDuration
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private val vm: PlayerViewModel by activityViewModels()

    @Inject lateinit var mediaRepository: MediaRepository

    private var isSeeking = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupControls()
        observeState()
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener      { findNavController().navigateUp() }
        binding.btnPlayPause.setOnClickListener { vm.playPause() }
        binding.btnNext.setOnClickListener      { vm.next() }
        binding.btnPrevious.setOnClickListener  { vm.previous() }
        binding.btnShuffle.setOnClickListener   { vm.toggleShuffle() }
        binding.btnRepeat.setOnClickListener    { vm.cycleRepeat() }
        binding.btnLike.setOnClickListener      { vm.currentSong.value?.let { vm.toggleLike(it) } }
        binding.btnQueue.setOnClickListener     { findNavController().navigate(R.id.action_player_to_queue) }
        binding.btnEqualizer.setOnClickListener { findNavController().navigate(R.id.action_player_to_equalizer) }
        binding.btnSleep.setOnClickListener     { findNavController().navigate(R.id.action_player_to_sleep) }
        binding.btnLyrics.setOnClickListener    { findNavController().navigate(R.id.action_player_to_lyrics) }
        binding.tvArtistName.setOnClickListener { findNavController().navigate(R.id.action_player_to_artist) }

        // Speed button — cycles through speeds
        val speeds  = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        var speedIdx = speeds.indexOf(1.0f)
        binding.btnSpeed.setOnClickListener {
            speedIdx = (speedIdx + 1) % speeds.size
            val s = speeds[speedIdx]
            vm.setPlaybackSpeed(s)
            binding.btnSpeed.text = if (s == 1.0f) "1.0× Speed" else "${s}× Speed"
        }

        // Seek bar
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                isSeeking = false
                vm.seekTo((sb.progress.toLong() * vm.playerState.value.durationMs) / 100)
            }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = formatDuration(
                        (progress.toLong() * vm.playerState.value.durationMs) / 100
                    )
                }
            }
        })

        // Volume
        binding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) vm.setVolume(p / 100f)
            }
        })
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.currentSong.collect { song ->
                        song ?: return@collect
                        binding.tvSongTitle.text  = song.title
                        binding.tvArtistName.text = song.artist
                        binding.tvAlbumName.text  = " · ${song.album}"
                        binding.tvTotalTime.text  = formatDuration(song.duration)
                        binding.btnLike.setImageResource(
                            if (song.isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
                        )
                        // Load album art from MediaStore
                        val artUri = mediaRepository.getAlbumArtUri(song.albumId)
                        Glide.with(this@PlayerFragment)
                            .load(artUri)
                            .placeholder(R.drawable.ic_music_note)
                            .error(R.drawable.ic_music_note)
                            .centerCrop()
                            .into(binding.ivAlbumArt)
                    }
                }

                launch {
                    vm.playerState.collect { state ->
                        binding.btnPlayPause.setImageResource(
                            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        )
                        if (!isSeeking && state.durationMs > 0) {
                            binding.seekBar.progress    = ((state.progressMs * 100) / state.durationMs).toInt()
                            binding.tvCurrentTime.text  = formatDuration(state.progressMs)
                        }
                        binding.btnShuffle.alpha = if (state.shuffleEnabled) 1.0f else 0.35f
                        binding.btnRepeat.setImageResource(
                            if (state.repeatMode == RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
                        )
                        binding.btnRepeat.alpha = if (state.repeatMode != RepeatMode.OFF) 1.0f else 0.35f
                    }
                }

                launch {
                    vm.walletState.collect { wallet ->
                        binding.btnTip.visibility = if (wallet.isConnected) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    vm.sleepTimer.collect { timer ->
                        binding.btnSleep.alpha = if (timer.isActive) 1.0f else 0.6f
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
