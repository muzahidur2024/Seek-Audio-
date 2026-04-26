package com.seekaudio.ui.library

import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.seekaudio.R
import com.seekaudio.data.model.Song
import com.seekaudio.databinding.FragmentLibraryBinding
import com.seekaudio.databinding.ItemSongBinding
import com.seekaudio.ui.player.PlayerViewModel
import com.seekaudio.utils.formatDuration
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ─── Filter tabs ─────────────────────────────────────────────────────────────

enum class LibraryTab { ALL, LIKED, RECENT, MOST_PLAYED }

// ─── Fragment ─────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val vm: PlayerViewModel by activityViewModels()

    private lateinit var songAdapter: SongAdapter
    private var currentTab = LibraryTab.ALL
    private var libraryJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSearch()
        setupTabs()
        observeLibrary()
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick  = { song, allSongs -> vm.playSong(song, allSongs); findNavController().navigate(R.id.action_library_to_player) },
            onLikeClick  = { song -> vm.toggleLike(song) },
            onEditClick  = { song -> findNavController().navigate(R.id.action_library_to_id3editor, Bundle().apply { putLong("songId", song.id) }) },
            currentSong  = { vm.currentSong.value },
            isPlaying    = { vm.playerState.value.isPlaying },
        )
        binding.rvSongs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = songAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String) = true
            override fun onQueryTextChange(q: String): Boolean { vm.setSearch(q); return true }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.searchResults.collect { results ->
                    if (binding.searchView.query.isNotBlank()) {
                        songAdapter.submitList(results)
                        binding.tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun setupTabs() {
        listOf(
            binding.tabAll        to LibraryTab.ALL,
            binding.tabLiked      to LibraryTab.LIKED,
            binding.tabRecent     to LibraryTab.RECENT,
            binding.tabMostPlayed to LibraryTab.MOST_PLAYED,
        ).forEach { (tab, type) ->
            tab.setOnClickListener { switchTab(type) }
        }
    }

    private fun switchTab(tab: LibraryTab) {
        currentTab = tab
        observeLibrary()
    }

    private fun observeLibrary() {
        libraryJob?.cancel()
        libraryJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val flow = when (currentTab) {
                    LibraryTab.ALL         -> vm.allSongs
                    LibraryTab.LIKED       -> vm.likedSongs
                    LibraryTab.RECENT      -> vm.recentlyPlayed
                    LibraryTab.MOST_PLAYED -> vm.mostPlayed
                }
                flow.collect { songs ->
                    if (binding.searchView.query.isBlank()) {
                        songAdapter.submitList(songs)
                        binding.tvEmpty.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
                        binding.tvSongCount.text = "${songs.size} songs"
                    }
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ─── RecyclerView Adapter ─────────────────────────────────────────────────────

class SongAdapter(
    private val onSongClick: (Song, List<Song>) -> Unit,
    private val onLikeClick: (Song) -> Unit,
    private val onEditClick: (Song) -> Unit,
    private val currentSong: () -> Song?,
    private val isPlaying: () -> Boolean,
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position), currentItems = currentList)
    }

    private fun currentItems() = currentList

    inner class SongViewHolder(private val b: ItemSongBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(song: Song, currentItems: List<Song>) {
            b.tvTitle.text    = song.title
            b.tvArtist.text   = "${song.artist} · ${song.album}"
            b.tvDuration.text = formatDuration(song.duration)

            val isActive = song.id == currentSong()?.id
            b.tvTitle.alpha       = if (isActive) 1f else 0.87f
            b.waveformIndicator.visibility = if (isActive && isPlaying()) View.VISIBLE else View.GONE

            b.btnLike.setImageResource(if (song.isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            b.btnEdit.setOnClickListener { onEditClick(song) }
            b.btnLike.setOnClickListener { onLikeClick(song) }
            b.root.setOnClickListener   { onSongClick(song, currentItems) }

            // Album art
            Glide.with(b.root.context)
                .load(R.drawable.ic_music_note)
                .centerCrop()
                .into(b.ivAlbumArt)
        }
    }
}

class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
    override fun areItemsTheSame(old: Song, new: Song) = old.id == new.id
    override fun areContentsTheSame(old: Song, new: Song) = old == new
}
