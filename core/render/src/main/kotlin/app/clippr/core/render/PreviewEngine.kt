package app.clippr.core.render

import android.content.Context
import android.view.SurfaceView
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import app.clippr.core.model.Project
import app.clippr.core.model.render.RenderPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PreviewState(
    val isPlaying: Boolean = false,
    val positionUs: Long = 0L,
    val durationUs: Long = 0L,
    val isReady: Boolean = false,
    val isEmpty: Boolean = true,
    val error: String? = null,
)

/**
 * Real-time timeline preview backed by Media3 [CompositionPlayer].
 *
 * The engine rebuilds its composition from the project's [app.clippr.core.model.render.RenderPlan]
 * whenever the timeline changes, preserving the playhead. Must be created and used on the main
 * thread.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
@MainThread
class PreviewEngine(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: CompositionPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var positionJob: Job? = null
    private var lastKey: Any? = null

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update { it.copy(isReady = playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) }
            if (playbackState == Player.STATE_ENDED) {
                player?.pause()
                syncPosition()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = error.message ?: "Playback error", isPlaying = false) }
        }
    }

    private fun obtainPlayer(): CompositionPlayer =
        player ?: CompositionPlayer.Builder(appContext).build().also {
            it.addListener(listener)
            surfaceView?.let(it::setVideoSurfaceView)
            player = it
        }

    fun attachSurface(view: SurfaceView) {
        surfaceView = view
        player?.setVideoSurfaceView(view)
    }

    fun detachSurface(view: SurfaceView) {
        if (surfaceView === view) surfaceView = null
        player?.clearVideoSurfaceView(view)
    }

    /**
     * Shows [project] in the preview at [quality]. Rebuilding is skipped when nothing that
     * affects rendering has changed.
     */
    fun setProject(project: Project, quality: PreviewQuality) {
        val (w, h) = OutputSize.forPreview(project.settings, quality)
        val plan = RenderPlanner.plan(project, w, h)
        val key = Triple(plan, w, h)
        if (key == lastKey) return
        lastKey = key

        val composition = CompositionFactory.create(plan, w, h)
        if (composition == null) {
            // Nothing to show: free decoders until the timeline has content again.
            release()
            lastKey = key
            _state.update { PreviewState(isEmpty = true) }
            return
        }
        val p = obtainPlayer()
        val wasPlaying = p.isPlaying
        val resumeAtUs = _state.value.positionUs.coerceIn(0, (plan.durationUs - 1).coerceAtLeast(0))
        try {
            p.setComposition(composition, resumeAtUs / 1000)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            if (wasPlaying) p.play()
            _state.update { it.copy(durationUs = plan.durationUs, isEmpty = false, positionUs = resumeAtUs, error = null) }
        } catch (e: RuntimeException) {
            _state.update { it.copy(error = e.message ?: "Could not build preview", isEmpty = false) }
        }
    }

    fun play() {
        val p = player ?: return
        if (_state.value.isEmpty) return
        if (p.playbackState == Player.STATE_ENDED || _state.value.positionUs >= _state.value.durationUs - 1_000) {
            p.seekTo(0)
        }
        p.play()
    }

    fun pause() {
        player?.pause()
        syncPosition()
    }

    fun togglePlayPause() = if (_state.value.isPlaying) pause() else play()

    /** Seeks to a timeline time. Keep [setScrubbing] enabled while dragging for faster seeks. */
    fun seekTo(timeUs: Long) {
        val clamped = timeUs.coerceIn(0, _state.value.durationUs.coerceAtLeast(0))
        _state.update { it.copy(positionUs = clamped) }
        player?.seekTo(clamped / 1000)
    }

    fun setScrubbing(enabled: Boolean) {
        player?.setScrubbingModeEnabled(enabled)
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Releases decoders and GL resources. The engine can be used again afterwards. */
    fun release() {
        stopPositionUpdates()
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        lastKey = null
        _state.update { it.copy(isPlaying = false, isReady = false) }
    }

    fun destroy() {
        release()
        scope.cancel()
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                syncPosition()
                delay(POSITION_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun syncPosition() {
        val p = player ?: return
        _state.update { it.copy(positionUs = p.currentPosition * 1000) }
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 33L
    }
}
