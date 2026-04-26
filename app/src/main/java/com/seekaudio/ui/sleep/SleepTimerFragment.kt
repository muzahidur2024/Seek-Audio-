package com.seekaudio.ui.sleep

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.seekaudio.databinding.FragmentSleepTimerBinding
import com.seekaudio.ui.player.PlayerViewModel
import com.seekaudio.utils.formatDuration
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SleepTimerFragment : Fragment() {

    private var _binding: FragmentSleepTimerBinding? = null
    private val binding get() = _binding!!
    private val vm: PlayerViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSleepTimerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSleep5.setOnClickListener     { vm.startSleepTimer(5  * 60_000L) }
        binding.btnSleep15.setOnClickListener    { vm.startSleepTimer(15 * 60_000L) }
        binding.btnSleep30.setOnClickListener    { vm.startSleepTimer(30 * 60_000L) }
        binding.btnSleep45.setOnClickListener    { vm.startSleepTimer(45 * 60_000L) }
        binding.btnSleep60.setOnClickListener    { vm.startSleepTimer(60 * 60_000L) }
        binding.btnSleepTrack.setOnClickListener { vm.startSleepAfterCurrentTrack() }
        binding.btnCancelTimer.setOnClickListener{ vm.cancelSleepTimer() }

        val fadeMap = mapOf(
            binding.btnFadeNone to 0L,
            binding.btnFade5s   to 5_000L,
            binding.btnFade15s  to 15_000L,
            binding.btnFade30s  to 30_000L,
        )
        fadeMap.forEach { (btn, _) ->
            btn.setOnClickListener {
                fadeMap.keys.forEach { it.isSelected = false }
                btn.isSelected = true
            }
        }
        binding.btnFade15s.isSelected = true

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.sleepTimer.collect { state ->
                    binding.layoutActiveTimer.visibility = if (state.isActive) View.VISIBLE else View.GONE
                    if (state.isActive) binding.tvRemaining.text = formatDuration(state.remainingMs)
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
