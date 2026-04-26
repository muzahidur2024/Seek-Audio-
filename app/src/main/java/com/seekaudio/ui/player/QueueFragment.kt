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
            getCurrentIdx = { vm.currentIndex.value },
            isPlaying     = { vm.playerState.value.isPlaying },
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
                    vm.currentIndex.collect { adapter.notifyDataSetChanged() }
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ─── Queue Adapter ────────────────────────────────────────────────────────────

class QueueAdapter(
    private val onItemClick:   (Song) -> Unit,
    private val getCurrentIdx: () -> Int,
    private val isPlaying:     () -> Boolean,
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private val items = mutableListOf<Song>()

    fun submitQueue(queue: List<Song>) {
        items.clear(); items.addAll(queue); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val b = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return QueueViewHolder(b)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) =
        holder.bind(items[position], position == getCurrentIdx())

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
                if (isCurrent && isPlaying()) View.VISIBLE else View.GONE
            b.root.setOnClickListener { onItemClick(song) }
        }
    }
}
