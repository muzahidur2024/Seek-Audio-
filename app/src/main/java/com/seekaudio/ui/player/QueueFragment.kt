package com.seekaudio.ui.player

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.seekaudio.data.model.Song
import com.seekaudio.databinding.FragmentQueueBinding
import com.seekaudio.databinding.ItemSongBinding
import com.seekaudio.utils.formatDuration
import com.seekaudio.utils.hide
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class QueueFragment : Fragment() {

    private var _binding: FragmentQueueBinding? = null
    private val binding get() = _binding!!
    private val vm: PlayerViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = QueueAdapter(
            onItemClick   = { song -> vm.playSong(song, vm.queue.value) },
        )

        binding.rvQueue.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter  = adapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.queue.collect { queue -> adapter.submitQueue(queue) }
                }
                launch {
                    vm.currentSong
                        .map { it?.id }
                        .distinctUntilChanged()
                        .collect { songId ->
                            adapter.updatePlaybackState(songId, vm.playerState.value.isPlaying)
                        }
                }
                launch {
                    vm.playerState
                        .map { it.isPlaying }
                        .distinctUntilChanged()
                        .collect { isPlaying ->
                            adapter.updatePlaybackState(vm.currentSong.value?.id, isPlaying)
                        }
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ─── Queue Adapter ────────────────────────────────────────────────────────────

class QueueAdapter(
    private val onItemClick:   (Song) -> Unit,
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private val items = mutableListOf<Song>()
    private var activeSongId: Long? = null
    private var playbackActive: Boolean = false

    fun submitQueue(queue: List<Song>) {
        items.clear(); items.addAll(queue); notifyDataSetChanged()
    }

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
        val index = items.indexOfFirst { it.id == id }
        if (index != -1) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val b = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return QueueViewHolder(b)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) =
        holder.bind(items[position], items[position].id == activeSongId)

    override fun getItemCount() = items.size

    inner class QueueViewHolder(private val b: ItemSongBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(song: Song, isCurrent: Boolean) {
            b.tvTitle.text    = song.title
            b.tvArtist.text   = song.artist
            b.tvDuration.text = formatDuration(song.duration)
            b.tvTitle.alpha   = if (isCurrent) 1f else 0.75f
            b.btnLike.hide()
            b.btnEdit.hide()
            b.waveformIndicator.visibility =
                if (isCurrent && playbackActive) View.VISIBLE else View.GONE
            b.root.setOnClickListener { onItemClick(song) }
        }
    }
}
