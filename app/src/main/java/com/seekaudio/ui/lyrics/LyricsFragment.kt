package com.seekaudio.ui.lyrics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.seekaudio.R
import com.seekaudio.data.model.LyricLine
import com.seekaudio.databinding.FragmentLyricsBinding
import com.seekaudio.databinding.ItemLyricLineBinding
import com.seekaudio.ui.player.PlayerViewModel
import com.seekaudio.utils.formatDuration
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

@AndroidEntryPoint
class LyricsFragment : Fragment() {

    private var _binding: FragmentLyricsBinding? = null
    private val binding get() = _binding!!
    private val vm: PlayerViewModel by activityViewModels()

    private lateinit var lyricsAdapter: LyricsAdapter
    private var syncOffsetMs = 0L
    private var activeIndex  = 0
    private var lyricsLoadJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLyricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lyricsAdapter = LyricsAdapter { line -> vm.seekTo(line.timeMs) }
        binding.rvLyrics.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = lyricsAdapter
        }

        // Playback controls in header
        binding.btnLyricsPlayPause.setOnClickListener { vm.playPause() }

        // Seek bar
        var seeking = false
        binding.seekLyrics.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                val dur = vm.playerState.value.durationMs
                vm.seekTo((sb.progress.toLong() * dur) / 100)
            }
        })

        // Sync offset
        binding.btnOffsetMinus.setOnClickListener {
            syncOffsetMs -= 500
            updateOffsetLabel()
        }
        binding.btnOffsetPlus.setOnClickListener {
            syncOffsetMs += 500
            updateOffsetLabel()
        }
        updateOffsetLabel()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.currentSong.collect { song ->
                        binding.tvLyricsTitle.text  = song?.title  ?: ""
                        binding.tvLyricsArtist.text = song?.artist ?: ""
                        loadLyrics(song)
                    }
                }

                launch {
                    vm.playerState.collect { state ->
                        binding.btnLyricsPlayPause.setImageResource(
                            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        )
                        if (state.durationMs > 0) {
                            val pct = (state.progressMs * 100 / state.durationMs).toInt()
                            binding.seekLyrics.progress = pct
                        }
                        // Highlight active lyric
                        val lines = lyricsAdapter.currentList
                        val newActive = lines.indexOfLast { it.timeMs <= state.progressMs + syncOffsetMs }
                        if (newActive != activeIndex && newActive >= 0) {
                            activeIndex = newActive
                            lyricsAdapter.setActiveIndex(activeIndex)
                            binding.rvLyrics.smoothScrollToPosition(activeIndex)
                        }
                    }
                }
            }
        }
    }

    private fun updateOffsetLabel() {
        val seconds = syncOffsetMs / 1000f
        binding.tvOffset.text = String.format("%+.1fs", seconds)
    }

    private fun loadLyrics(song: com.seekaudio.data.model.Song?) {
        lyricsLoadJob?.cancel()
        activeIndex = 0
        syncOffsetMs = 0L
        updateOffsetLabel()
        if (song == null) {
            lyricsAdapter.submitList(emptyList())
            showEmpty(getString(R.string.no_lyrics))
            return
        }

        showLoading()
        lyricsLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val lines = vm.getLyricsForSong(song)
            if (!isAdded || _binding == null) return@launch

            lyricsAdapter.submitList(lines)
            if (lines.isEmpty()) {
                showEmpty(getString(R.string.no_lyrics))
            } else {
                showLyrics()
            }
        }
    }

    private fun showLoading() {
        binding.progressLyrics.visibility = View.VISIBLE
        binding.tvNoLyrics.visibility = View.VISIBLE
        binding.tvNoLyrics.text = getString(R.string.lyrics_loading)
        binding.rvLyrics.visibility = View.INVISIBLE
    }

    private fun showEmpty(message: String) {
        binding.progressLyrics.visibility = View.GONE
        binding.tvNoLyrics.visibility = View.VISIBLE
        binding.tvNoLyrics.text = message
        binding.rvLyrics.visibility = View.INVISIBLE
    }

    private fun showLyrics() {
        binding.progressLyrics.visibility = View.GONE
        binding.tvNoLyrics.visibility = View.GONE
        binding.rvLyrics.visibility = View.VISIBLE
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ─── Lyrics Adapter ───────────────────────────────────────────────────────────

class LyricsAdapter(
    private val onLineClick: (LyricLine) -> Unit,
) : ListAdapter<LyricLine, LyricsAdapter.LyricViewHolder>(LyricDiffCallback()) {

    private var activeIndex = -1

    fun setActiveIndex(index: Int) {
        val old = activeIndex
        activeIndex = index
        if (old >= 0) notifyItemChanged(old)
        notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val binding = ItemLyricLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LyricViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        holder.bind(getItem(position), position == activeIndex)
    }

    inner class LyricViewHolder(private val b: ItemLyricLineBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(line: LyricLine, isActive: Boolean) {
            b.tvLyricText.text = line.text
            b.tvLyricTime.text = formatDuration(line.timeMs)
            b.tvLyricText.textSize    = if (isActive) 18f else 15f
            b.tvLyricText.alpha       = when {
                isActive -> 1.0f
                adapterPosition < (activeIndex.takeIf { it >= 0 } ?: 0) -> 0.42f
                else -> 0.75f
            }
            b.tvLyricText.setTextColor(
                if (isActive) b.root.context.getColor(R.color.purple_light)
                else b.root.context.getColor(R.color.dark_text)
            )
            b.root.setOnClickListener { onLineClick(line) }
        }
    }
}

class LyricDiffCallback : DiffUtil.ItemCallback<LyricLine>() {
    override fun areItemsTheSame(old: LyricLine, new: LyricLine) = old.timeMs == new.timeMs
    override fun areContentsTheSame(old: LyricLine, new: LyricLine) = old == new
}
