package com.seekaudio.ui.id3

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.seekaudio.databinding.FragmentId3EditorBinding
import com.seekaudio.ui.player.PlayerViewModel
import com.seekaudio.utils.formatDuration
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class Id3EditorFragment : Fragment() {

    private var _binding: FragmentId3EditorBinding? = null
    private val binding get() = _binding!!
    private val vm: PlayerViewModel by activityViewModels()

    private var songId: Long = -1L
    private var originalTitle  = ""
    private var originalArtist = ""
    private var originalAlbum  = ""
    private var originalGenre  = ""
    private var originalYear   = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentId3EditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get songId from arguments (default to current song)
        songId = arguments?.getLong("songId", -1L) ?: -1L

        viewLifecycleOwner.lifecycleScope.launch {
            val songs = vm.allSongs.value
            val song  = if (songId > 0) songs.firstOrNull { it.id == songId }
            else vm.currentSong.value

            song ?: return@launch

            // Store originals for reset
            originalTitle  = song.title
            originalArtist = song.artist
            originalAlbum  = song.album
            originalGenre  = song.genre
            originalYear   = song.year

            // Fill fields
            binding.etTitle.setText(song.title)
            binding.etArtist.setText(song.artist)
            binding.etAlbum.setText(song.album)
            binding.etGenre.setText(song.genre)
            binding.etYear.setText(if (song.year > 0) song.year.toString() else "")

            // Preview header
            binding.tvId3Title.text        = song.title
            binding.tvId3ArtistAlbum.text  = "${song.artist} · ${song.album}"
            binding.tvId3Duration.text     = formatDuration(song.duration)
            binding.tvId3Genre.text        = song.genre
        }

        binding.btnSaveTags.setOnClickListener {
            val id     = songId.takeIf { it > 0 } ?: (vm.currentSong.value?.id ?: return@setOnClickListener)
            val title  = binding.etTitle.text?.toString()?.trim()  ?: return@setOnClickListener
            val artist = binding.etArtist.text?.toString()?.trim() ?: return@setOnClickListener
            val album  = binding.etAlbum.text?.toString()?.trim()  ?: return@setOnClickListener
            val genre  = binding.etGenre.text?.toString()?.trim()  ?: ""
            val year   = binding.etYear.text?.toString()?.trim()?.toIntOrNull() ?: 0

            vm.updateTags(id, title, artist, album, genre, year)
            findNavController().navigateUp()
        }

        binding.btnResetTags.setOnClickListener {
            binding.etTitle.setText(originalTitle)
            binding.etArtist.setText(originalArtist)
            binding.etAlbum.setText(originalAlbum)
            binding.etGenre.setText(originalGenre)
            binding.etYear.setText(if (originalYear > 0) originalYear.toString() else "")
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
