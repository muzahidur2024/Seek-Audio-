package com.seekaudio.ui.equalizer

import android.os.Bundle
import android.view.*
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.seekaudio.R
import com.seekaudio.data.model.EQ_PRESETS
import com.seekaudio.databinding.FragmentEqualizerBinding
import com.seekaudio.ui.player.PlayerViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EqualizerFragment : Fragment() {

    private var _binding: FragmentEqualizerBinding? = null
    private val binding get() = _binding!!
    private val vm: PlayerViewModel by activityViewModels()

    private val bandSeekBars = mutableListOf<SeekBar>()
    private var updatingFromState = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEqualizerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupEqBands()
        setupPresets()
        setupToggles()
        observeEqState()
    }

    private fun setupEqBands() {
        // The 10 vertical SeekBars are defined in the layout
        bandSeekBars.addAll(listOf(
            binding.seekBand0, binding.seekBand1, binding.seekBand2, binding.seekBand3,
            binding.seekBand4, binding.seekBand5, binding.seekBand6, binding.seekBand7,
            binding.seekBand8, binding.seekBand9,
        ))

        val bandLabels = listOf(
            binding.tvBand0, binding.tvBand1, binding.tvBand2, binding.tvBand3,
            binding.tvBand4, binding.tvBand5, binding.tvBand6, binding.tvBand7,
            binding.tvBand8, binding.tvBand9,
        )
        val freqNames = listOf("60Hz","170Hz","310Hz","600Hz","1kHz","3kHz","6kHz","12kHz","14kHz","16kHz")

        bandSeekBars.forEachIndexed { i, seekBar ->
            bandLabels[i].text = freqNames[i]
            seekBar.max     = 24   // -12 to +12, offset by 12
            seekBar.progress = 12  // 0dB default

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && !updatingFromState) {
                        val dB = progress - 12f
                        vm.setEqBand(i, dB)
                    }
                }
            })
        }
    }

    private fun setupPresets() {
        EQ_PRESETS.keys.forEach { presetName ->
            val chip = Chip(requireContext()).apply {
                text = presetName
                isCheckable = true
                setOnClickListener { vm.applyEqPreset(presetName) }
            }
            binding.chipGroupPresets.addView(chip)
        }
    }

    private fun setupToggles() {
        binding.switchEq.setOnCheckedChangeListener       { _, _ -> vm.toggleEq() }
        binding.switchBassBoost.setOnCheckedChangeListener { _, _ -> vm.toggleBassBoost() }
        binding.switchVirtualizer.setOnCheckedChangeListener { _, _ -> vm.toggleVirtualizer() }
    }

    private fun observeEqState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.eqState.collect { eq ->
                    updatingFromState = true
                    eq.bands.forEachIndexed { i, dB ->
                        if (i < bandSeekBars.size) {
                            bandSeekBars[i].progress = (dB + 12).toInt().coerceIn(0, 24)
                        }
                    }
                    binding.switchEq.isChecked         = eq.enabled
                    binding.switchBassBoost.isChecked   = eq.bassBoost
                    binding.switchVirtualizer.isChecked = eq.virtualizer
                    // Highlight active preset chip
                    for (i in 0 until binding.chipGroupPresets.childCount) {
                        val chip = binding.chipGroupPresets.getChildAt(i) as? Chip
                        chip?.isChecked = chip?.text == eq.presetName
                    }
                    updatingFromState = false
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
