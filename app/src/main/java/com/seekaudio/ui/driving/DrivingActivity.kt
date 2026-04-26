package com.seekaudio.ui.driving

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.seekaudio.R
import com.seekaudio.databinding.ActivityDrivingBinding
import com.seekaudio.data.model.RepeatMode
import com.seekaudio.service.PlaybackService
import com.seekaudio.ui.player.PlayerViewModel
import com.seekaudio.utils.formatDuration
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DrivingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDrivingBinding
    private val vm: PlayerViewModel by viewModels()

    companion object {
        fun start(context: Context) =
            context.startActivity(Intent(context, DrivingActivity::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDrivingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on while driving
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        vm.initController()
        setupControls()
        observeState()
    }

    private fun setupControls() {
        binding.btnExitDrive.setOnClickListener  { finish() }
        binding.btnDrivePlayPause.setOnClickListener { vm.playPause() }
        binding.btnDriveNext.setOnClickListener  { vm.next() }
        binding.btnDrivePrev.setOnClickListener  { vm.previous() }
        binding.btnDriveShuffle.setOnClickListener { vm.toggleShuffle() }
        binding.btnDriveRepeat.setOnClickListener  { vm.cycleRepeat() }
        binding.btnDriveSleep.setOnClickListener   {
            if (vm.sleepTimer.value.isActive) vm.cancelSleepTimer()
            else vm.startSleepTimer(30 * 60_000L)
        }

        binding.seekDrive.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val dur = vm.playerState.value.durationMs
                vm.seekTo((sb.progress.toLong() * dur) / 100)
            }
        })
    }

    private fun observeState() {
        lifecycleScope.launch {
            vm.currentSong.collect { song ->
                binding.tvDriveTitle.text  = song?.title  ?: ""
                binding.tvDriveArtist.text = song?.artist ?: ""
            }
        }

        lifecycleScope.launch {
            vm.playerState.collect { state ->
                binding.btnDrivePlayPause.setImageResource(
                    if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                if (state.durationMs > 0) {
                    binding.seekDrive.progress = ((state.progressMs * 100) / state.durationMs).toInt()
                    binding.tvDriveCurrent.text = formatDuration(state.progressMs)
                    binding.tvDriveTotal.text   = formatDuration(state.durationMs)
                }
                binding.btnDriveShuffle.alpha = if (state.shuffleEnabled) 1f else 0.45f
                binding.btnDriveRepeat.alpha  = if (state.repeatMode != RepeatMode.OFF) 1f else 0.45f
            }
        }

        lifecycleScope.launch {
            vm.sleepTimer.collect { timer ->
                binding.btnDriveSleep.alpha = if (timer.isActive) 1f else 0.45f
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
