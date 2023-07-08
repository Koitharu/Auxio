/*
 * Copyright (c) 2021 Auxio Project
 * PlaybackPanelFragment.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.abs
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentPlaybackPanelBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.detail.Show
import org.oxycblt.auxio.music.MusicParent
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.carousel.CoverCarouselAdapter
import org.oxycblt.auxio.playback.queue.QueueViewModel
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.ui.StyledSeekBar
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.logD
import org.oxycblt.auxio.util.share
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.systemBarInsetsCompat

/**
 * A [ViewBindingFragment] more information about the currently playing song, alongside all
 * available controls.
 *
 * @author Alexander Capehart (OxygenCobalt)
 *
 * TODO: Improve flickering situation on play button
 */
@AndroidEntryPoint
class PlaybackPanelFragment :
    ViewBindingFragment<FragmentPlaybackPanelBinding>(),
    Toolbar.OnMenuItemClickListener,
    StyledSeekBar.Listener {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val queueModel: QueueViewModel by activityViewModels()
    private var equalizerLauncher: ActivityResultLauncher<Intent>? = null
    private var coverAdapter: CoverCarouselAdapter? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentPlaybackPanelBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentPlaybackPanelBinding,
        savedInstanceState: Bundle?
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        // AudioEffect expects you to use startActivityForResult with the panel intent. There is no
        // contract analogue for this intent, so the generic contract is used instead.
        equalizerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                // Nothing to do
            }

        // --- UI SETUP ---
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsetsCompat
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.playbackToolbar.apply {
            setNavigationOnClickListener { playbackModel.openMain() }
            setOnMenuItemClickListener(this@PlaybackPanelFragment)
        }

        // cover carousel adapter
        coverAdapter = CoverCarouselAdapter()
        binding.playbackCoverPager.apply {
            adapter = coverAdapter
            registerOnPageChangeCallback(OnCoverChangedCallback(queueModel))
        }

        // Set up marquee on song information, alongside click handlers that navigate to each
        // respective item.
        binding.playbackSong.apply {
            isSelected = true
            setOnClickListener {
                playbackModel.song.value?.let {
                    detailModel.showAlbum(it)
                    playbackModel.openMain()
                }
            }
        }
        binding.playbackArtist.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentArtist() }
        }
        binding.playbackAlbum.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentAlbum() }
        }

        binding.playbackSeekBar.listener = this

        // Set up actions
        // TODO: Add better playback button accessibility
        binding.playbackRepeat.setOnClickListener { playbackModel.toggleRepeatMode() }
        binding.playbackSkipPrev.setOnClickListener { playbackModel.prev() }
        binding.playbackPlayPause.setOnClickListener { playbackModel.togglePlaying() }
        binding.playbackSkipNext.setOnClickListener { playbackModel.next() }
        binding.playbackShuffle.setOnClickListener { playbackModel.toggleShuffled() }

        // --- VIEWMODEL SETUP --
        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(playbackModel.parent, ::updateParent)
        collectImmediately(playbackModel.positionDs, ::updatePosition)
        collectImmediately(playbackModel.repeatMode, ::updateRepeat)
        collectImmediately(playbackModel.isPlaying, ::updatePlaying)
        collectImmediately(playbackModel.isShuffled, ::updateShuffled)
        collectImmediately(queueModel.queue, ::updateQueue)
        collectImmediately(queueModel.index, ::updateQueuePosition)
        collect(detailModel.toShow.flow, ::handleShow)
    }

    override fun onDestroyBinding(binding: FragmentPlaybackPanelBinding) {
        equalizerLauncher = null
        coverAdapter = null
        binding.playbackToolbar.setOnMenuItemClickListener(null)
        // Marquee elements leak if they are not disabled when the views are destroyed.
        binding.playbackSong.isSelected = false
        binding.playbackArtist.isSelected = false
        binding.playbackAlbum.isSelected = false
    }

    override fun onMenuItemClick(item: MenuItem) =
        when (item.itemId) {
            R.id.action_open_equalizer -> {
                // Launch the system equalizer app, if possible.
                logD("Launching equalizer")
                val equalizerIntent =
                    Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                        // Provide audio session ID so the equalizer can show options for this app
                        // in particular.
                        .putExtra(
                            AudioEffect.EXTRA_AUDIO_SESSION, playbackModel.currentAudioSessionId)
                        // Signal music type so that the equalizer settings are appropriate for
                        // music playback.
                        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                try {
                    requireNotNull(equalizerLauncher) {
                            "Equalizer panel launcher was not available"
                        }
                        .launch(equalizerIntent)
                } catch (e: ActivityNotFoundException) {
                    requireContext().showToast(R.string.err_no_app)
                }
                true
            }
            R.id.action_artist_details -> {
                navigateToCurrentArtist()
                true
            }
            R.id.action_album_details -> {
                navigateToCurrentAlbum()
                true
            }
            R.id.action_playlist_add -> {
                playbackModel.song.value?.let(musicModel::addToPlaylist)
                true
            }
            R.id.action_detail -> {
                playbackModel.song.value?.let(detailModel::showSong)
                true
            }
            R.id.action_share -> {
                playbackModel.song.value?.let { requireContext().share(it) }
                true
            }
            else -> false
        }

    override fun onSeekConfirmed(positionDs: Long) {
        playbackModel.seekTo(positionDs)
    }

    private fun updateQueue(queue: List<Song>) {
        coverAdapter?.update(queue, queueModel.queueInstructions.flow.value)
    }

    private fun updateQueuePosition(position: Int) {
        val pager = requireBinding().playbackCoverPager
        val distance = abs(pager.currentItem - position)
        if (distance != 0) {
            pager.setCurrentItem(position, distance == 1)
        }
    }

    private fun updateSong(song: Song?) {
        if (song == null) {
            // Nothing to do.
            return
        }

        val binding = requireBinding()
        val context = requireContext()
        logD("Updating song display: $song")
        //        binding.playbackCover.bind(song)
        binding.playbackSong.text = song.name.resolve(context)
        binding.playbackArtist.text = song.artists.resolveNames(context)
        binding.playbackAlbum.text = song.album.name.resolve(context)
        binding.playbackSeekBar.durationDs = song.durationMs.msToDs()
    }

    private fun updateParent(parent: MusicParent?) {
        val binding = requireBinding()
        val context = requireContext()
        binding.playbackToolbar.subtitle =
            parent?.run { name.resolve(context) } ?: context.getString(R.string.lbl_all_songs)
    }

    private fun updatePosition(positionDs: Long) {
        requireBinding().playbackSeekBar.positionDs = positionDs
    }

    private fun updateRepeat(repeatMode: RepeatMode) {
        requireBinding().playbackRepeat.apply {
            setIconResource(repeatMode.icon)
            isActivated = repeatMode != RepeatMode.NONE
        }
    }

    private fun updatePlaying(isPlaying: Boolean) {
        requireBinding().playbackPlayPause.isActivated = isPlaying
    }

    private fun updateShuffled(isShuffled: Boolean) {
        requireBinding().playbackShuffle.isActivated = isShuffled
    }

    private fun handleShow(show: Show?) {
        when (show) {
            is Show.SongAlbumDetails,
            is Show.ArtistDetails,
            is Show.AlbumDetails -> playbackModel.openMain()
            is Show.SongDetails,
            is Show.SongArtistDecision,
            is Show.AlbumArtistDecision,
            is Show.GenreDetails,
            is Show.PlaylistDetails,
            null -> {}
        }
    }

    private fun navigateToCurrentArtist() {
        playbackModel.song.value?.let(detailModel::showArtist)
    }

    private fun navigateToCurrentAlbum() {
        playbackModel.song.value?.let { detailModel.showAlbum(it.album) }
    }

    private class OnCoverChangedCallback(private val viewModel: QueueViewModel) :
        OnPageChangeCallback() {

        private var targetPosition = RecyclerView.NO_POSITION

        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)
            targetPosition = position
        }

        override fun onPageScrollStateChanged(state: Int) {
            super.onPageScrollStateChanged(state)
            if (state == ViewPager2.SCROLL_STATE_IDLE &&
                targetPosition != RecyclerView.NO_POSITION &&
                targetPosition != viewModel.index.value) {
                viewModel.goto(targetPosition)
            }
        }
    }
}
