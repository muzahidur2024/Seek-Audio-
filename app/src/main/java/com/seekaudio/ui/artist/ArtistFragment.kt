package com.seekaudio.ui.artist

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.seekaudio.R
import com.seekaudio.databinding.FragmentArtistBinding
import com.seekaudio.databinding.ItemSongBinding
import com.seekaudio.ui.player.PlayerViewModel
import com.seekaudio.utils.formatDuration
import com.seekaudio.utils.hide
import com.seekaudio.utils.show
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ArtistFragment : Fragment() {

    private var _binding: FragmentArtistBinding? = null
    private val binding get() = _binding!!
    private val vm: PlayerViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentArtistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.currentSong.collect { song ->
                        song ?: return@collect
                        val artistName = song.artist
                        binding.tvArtistName.text = artistName

                        // Tip button — only when wallet connected
                        val walletState = vm.walletState.value
                        if (walletState.isConnected) {
                            binding.btnTipArtist.show()
                            binding.btnTipArtist.text = "◎ Tip $artistName with SKR"
                            binding.btnTipArtist.setOnClickListener {
                                findNavController().navigate(R.id.web3Fragment)
                            }
                        } else {
                            binding.btnTipArtist.text = getString(R.string.connect_to_tip)
                            binding.btnTipArtist.setOnClickListener {
                                findNavController().navigate(R.id.web3Fragment)
                            }
                        }
                    }
                }

                launch {
                    vm.currentSong
                        .filterNotNull()
                        .combine(vm.allSongs) { song, allSongs ->
                            allSongs.filter { it.artist == song.artist }
                        }
                        .collect { artistSongs ->
                            binding.containerArtistTracks.removeAllViews()
                            artistSongs.forEach { s ->
                                val itemBinding = ItemSongBinding.inflate(layoutInflater, binding.containerArtistTracks, false)
                                itemBinding.tvTitle.text    = s.title
                                itemBinding.tvArtist.text   = "${s.album} · ${s.year}"
                                itemBinding.tvDuration.text = formatDuration(s.duration)
                                itemBinding.btnLike.hide()
                                itemBinding.btnEdit.hide()
                                itemBinding.root.setOnClickListener {
                                    vm.playSong(s, artistSongs)
                                    findNavController().navigate(R.id.playerFragment)
                                }
                                binding.containerArtistTracks.addView(itemBinding.root)
                            }
                        }
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
