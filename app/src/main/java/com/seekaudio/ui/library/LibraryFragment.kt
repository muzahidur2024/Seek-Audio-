package com.seekaudio.ui.library

import android.os.Bundle
import android.provider.Settings
import android.media.RingtoneManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.*
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private var pendingDeviceDeleteSong: Song? = null
    private val deviceDeleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val song = pendingDeviceDeleteSong ?: return@registerForActivityResult
        pendingDeviceDeleteSong = null
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewLifecycleOwner.lifecycleScope.launch {
                vm.deleteSongFromLibrary(song.id)
                toast(getString(R.string.deleted_success))
            }
        } else {
            toast(getString(R.string.deleted_failed))
        }
    }

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
        observePlaybackState()
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick  = { song, allSongs -> vm.playSong(song, allSongs) },
            onLikeClick  = { song -> vm.toggleLike(song) },
            onOptionsClick = { anchor, song -> showSongOptions(anchor, song) },
        )
        binding.rvSongs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = songAdapter
            setHasFixedSize(true)
        }
    }

    private fun observePlaybackState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.currentSong
                        .map { it?.id }
                        .distinctUntilChanged()
                        .collect { songId ->
                            songAdapter.updatePlaybackState(songId, vm.playerState.value.isPlaying)
                        }
                }
                launch {
                    vm.playerState
                        .map { it.isPlaying }
                        .distinctUntilChanged()
                        .collect { isPlaying ->
                            songAdapter.updatePlaybackState(vm.currentSong.value?.id, isPlaying)
                        }
                }
            }
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
        updateTabSelection(LibraryTab.ALL)
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
        if (currentTab == tab) return
        currentTab = tab
        updateTabSelection(tab)
        observeLibrary()
    }

    private fun updateTabSelection(tab: LibraryTab) {
        binding.tabAll.isChecked = tab == LibraryTab.ALL
        binding.tabLiked.isChecked = tab == LibraryTab.LIKED
        binding.tabRecent.isChecked = tab == LibraryTab.RECENT
        binding.tabMostPlayed.isChecked = tab == LibraryTab.MOST_PLAYED
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

    private fun showSongOptions(anchor: View, song: Song) {
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.song_options_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_enqueue -> {
                        if (vm.enqueueSong(song)) {
                            toast(getString(R.string.enqueued))
                        } else {
                            toast(getString(R.string.player_not_ready))
                        }
                        true
                    }
                    R.id.action_share -> {
                        shareSong(song)
                        true
                    }
                    R.id.action_set_ringtone -> {
                        setAsRingtone(song)
                        true
                    }
                    R.id.action_delete -> {
                        confirmDelete(song)
                        true
                    }
                    else -> false
                }
            }
        }.show()
    }

    private fun shareSong(song: Song) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, song.contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.menu_share)))
    }

    private fun setAsRingtone(song: Song) {
        if (!Settings.System.canWrite(requireContext())) {
            toast(getString(R.string.ringtone_permission_needed))
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${requireContext().packageName}")
                )
            )
            return
        }
        runCatching {
            RingtoneManager.setActualDefaultRingtoneUri(
                requireContext(),
                RingtoneManager.TYPE_RINGTONE,
                song.contentUri
            )
        }.onSuccess {
            toast(getString(R.string.ringtone_set))
        }.onFailure {
            toast(getString(R.string.ringtone_failed))
        }
    }

    private fun confirmDelete(song: Song) {
        val checkbox = CheckBox(requireContext()).apply {
            text = getString(R.string.delete_from_device_checkbox)
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(checkbox)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_song_title)
            .setMessage(R.string.delete_song_message)
            .setView(content)
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    if (checkbox.isChecked) {
                        requestDeviceDelete(song)
                    } else {
                        vm.deleteSongFromLibrary(song.id)
                        toast(getString(R.string.deleted_from_list))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestDeviceDelete(song: Song) {
        val resolver = requireContext().contentResolver
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingDeviceDeleteSong = song
                val request = MediaStore.createDeleteRequest(resolver, listOf(song.contentUri))
                deviceDeleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                return
            }

            val rows = resolver.delete(song.contentUri, null, null)
            if (rows > 0) {
                viewLifecycleOwner.lifecycleScope.launch {
                    vm.deleteSongFromLibrary(song.id)
                    toast(getString(R.string.deleted_success))
                }
            } else {
                toast(getString(R.string.deleted_failed))
            }
        } catch (_: Exception) {
            toast(getString(R.string.deleted_failed))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ─── RecyclerView Adapter ─────────────────────────────────────────────────────

class SongAdapter(
    private val onSongClick: (Song, List<Song>) -> Unit,
    private val onLikeClick: (Song) -> Unit,
    private val onOptionsClick: (View, Song) -> Unit,
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {
    private var activeSongId: Long? = null
    private var playbackActive: Boolean = false

    fun updatePlaybackState(songId: Long?, isPlaying: Boolean) {
        val oldSongId = activeSongId
        val oldPlaying = playbackActive

        activeSongId = songId
        playbackActive = isPlaying

        if (oldSongId != songId) {
            notifySongChanged(oldSongId)
            notifySongChanged(songId)
        } else if (oldPlaying != isPlaying) {
            notifySongChanged(songId)
        }
    }

    private fun notifySongChanged(songId: Long?) {
        val id = songId ?: return
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) notifyItemChanged(index)
    }

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

            val isActive = song.id == activeSongId
            b.tvTitle.alpha       = if (isActive) 1f else 0.87f
            b.waveformIndicator.visibility = if (isActive && playbackActive) View.VISIBLE else View.GONE

            b.btnLike.setImageResource(if (song.isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            b.btnEdit.setOnClickListener { onOptionsClick(b.btnEdit, song) }
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
