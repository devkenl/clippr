package app.clippr.core.render

import android.content.Context
import android.os.SystemClock
import android.view.SurfaceView
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
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
    /** Frames are actually advancing. */
    val isPlaying: Boolean = false,
    /**
     * The user asked to play. Stays true while the player loads or catches up, so the button
     * shows "Pause" immediately and a second tap pauses instead of being swallowed.
     */
    val playRequested: Boolean = false,
    /** Loading or catching up after a seek; shown as a spinner on the play button. */
    val isBuffering: Boolean = false,
    val positionUs: Long = 0L,
    val durationUs: Long = 0L,
    val isReady: Boolean = false,
    val isEmpty: Boolean = true,
    /** Why the preview can't play, in plain words; null when all is well. */
    val error: String? = null,
    /**
     * The error is one only a fresh app process fixes (the device's video decoders stay taken
     * after a frozen player), so the UI offers to restart Clippr.
     */
    val suggestRestart: Boolean = false,
    /**
     * The preview shows transitions as cuts because this device can't decode both clips of a
     * transition at once in real time. Exports are unaffected.
     */
    val transitionsSimplified: Boolean = false,
)

/**
 * Real-time timeline preview backed by Media3 [CompositionPlayer].
 *
 * The engine rebuilds its composition from the project's [app.clippr.core.model.render.RenderPlan]
 * whenever the timeline changes, preserving the playhead. It also guards playback:
 * - after a player error it shows the reason and rebuilds a fresh player on the next Play (a
 *   failed CompositionPlayer otherwise ignores play() until it is prepared again);
 * - a watchdog notices playback that is requested but stuck (buffering forever, or "playing"
 *   without the position moving) and nudges the player with a seek, then rebuilds it, which is
 *   what pausing and scrubbing by hand used to fix.
 *
 * Must be created and used on the main thread.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
@MainThread
class PreviewEngine(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: CompositionPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var positionJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastKey: Any? = null
    private var lastProject: Project? = null
    private var lastQuality: PreviewQuality = PreviewQuality.BALANCED
    private var failed = false
    private var muted = false
    /** Automatic rebuilds since playback last made progress; stops endless retry loops. */
    private var stallRebuilds = 0
    /** Silent restarts after a GL error since the player was last ready. */
    private var autoRecoveries = 0
    /** Decoder errors in a row (reset once playback is ready). */
    private var decoderFailures = 0
    private val budget = DecoderBudget(appContext)

    /**
     * Called before retrying after a decoder error, to free other users of the hardware video
     * decoders (e.g. the timeline's thumbnail extractors).
     */
    var onReleaseOtherDecoders: (suspend () -> Unit)? = null

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _state.update { it.copy(playRequested = playWhenReady) }
            if (playWhenReady) startPlaybackMonitoring() else stopPlaybackMonitoring()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    isReady = playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED,
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                )
            }
            if (playbackState == Player.STATE_READY) {
                autoRecoveries = 0
                decoderFailures = 0
            }
            if (playbackState == Player.STATE_ENDED) {
                player?.pause()
                syncPosition()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            failed = true
            stopPlaybackMonitoring()
            if (error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED && autoRecoveries < 1) {
                // GL pipeline hiccup (e.g. its surface couldn't be created): start over once with
                // a fresh player and surface before bothering the user.
                autoRecoveries++
                scope.launch { retry() }
                return
            }
            val decoderError = error.errorCode in DECODER_CODES
            if (decoderError) {
                decoderFailures++
                if (decoderFailures == 1) {
                    // Decoders may just be briefly taken (thumbnails, a player still shutting
                    // down). Free what we can and try once more before telling the user.
                    scope.launch {
                        releasePlayerOnly()
                        runCatching { onReleaseOtherDecoders?.invoke() }
                        delay(DECODER_RETRY_DELAY_MS)
                        retry()
                    }
                    return
                }
            }
            _state.update {
                it.copy(
                    error = describe(error),
                    suggestRestart = decoderError,
                    isPlaying = false,
                    playRequested = false,
                    isBuffering = false,
                )
            }
        }
    }

    /** How many layers the current player was built for. */
    private var playerLayers = 0

    private fun obtainPlayer(layers: Int): CompositionPlayer {
        val multiLayer = layers > 1
        player?.let { existing ->
            if (playerLayers == layers) return existing
            // A different number of layers needs a different video graph (or a graph with a
            // different number of inputs), which Media3 can't swap on a running player.
            releasePlayerOnly()
        }
        val builder = CompositionPlayer.Builder(appContext)
        if (multiLayer) builder.setVideoGraphFactory(MultipleInputVideoGraph.Factory())
        return builder.build().also {
            it.addListener(listener)
            it.volume = if (muted) 0f else 1f
            surfaceView?.let(it::setVideoSurfaceView)
            player = it
            playerLayers = layers
        }
    }

    private val _surfaceGeneration = MutableStateFlow(0)

    /**
     * Changes whenever the preview needs a brand-new SurfaceView. A window surface can only be
     * connected to one GL producer at a time; handing the same surface from a released player
     * to a new one can fail with EGL_BAD_ALLOC ("Error creating new EGL surface"). So every new
     * player gets a fresh surface: the UI recreates its SurfaceView when this value changes.
     */
    val surfaceGeneration: StateFlow<Int> = _surfaceGeneration.asStateFlow()

    fun attachSurface(view: SurfaceView) {
        surfaceView = view
        player?.setVideoSurfaceView(view)
    }

    fun detachSurface(view: SurfaceView) {
        if (surfaceView !== view) return
        surfaceView = null
        player?.clearVideoSurfaceView(view)
    }

    /**
     * Shows [project] in the preview at [quality]. Rebuilding is skipped when nothing that
     * affects rendering has changed.
     */
    fun setProject(project: Project, quality: PreviewQuality) {
        lastProject = project
        lastQuality = quality
        val (w, h) = OutputSize.forPreview(project.settings, quality)
        val full = RenderPlanner.plan(project, w, h)
        // Two 4K 60 fps clips crossing each other can need more decoding power than the device
        // has; a preview that can't keep up freezes and may hold on to the decoders. Show such
        // transitions as cuts instead (the export still renders them).
        val simplify = full.isMultiLayer && !budget.canPreview(full)
        val plan = if (simplify) RenderPlanner.plan(project, w, h, cutTransitions = true) else full
        _state.update { it.copy(transitionsSimplified = simplify) }
        val key = Triple(plan, w, h)
        if (key == lastKey && !failed) return
        lastKey = key

        val composition = CompositionFactory.create(plan, w, h, RenderResources.get(appContext))
        if (composition == null) {
            // Nothing to show: free decoders until the timeline has content again.
            release()
            lastKey = key
            _state.update { PreviewState(isEmpty = true) }
            return
        }
        if (failed) {
            // A player that reported an error stays unusable; start over with a fresh one.
            releasePlayerOnly()
            failed = false
        }
        val wasPlaying = player?.playWhenReady == true
        // The player is reused for edits; only a change in the number of layers needs a new one
        // (see obtainPlayer). Rebuilding on every edit churned through hardware decoders, and a
        // player that is slow to shut down can keep its decoders for good.
        val p = obtainPlayer(plan.layers.size)
        val resumeAtUs = _state.value.positionUs.coerceIn(0, (plan.durationUs - 1).coerceAtLeast(0))
        try {
            p.setComposition(composition, resumeAtUs / 1000)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            if (wasPlaying) p.play()
            _state.update { it.copy(durationUs = plan.durationUs, isEmpty = false, positionUs = resumeAtUs, error = null, suggestRestart = false) }
        } catch (e: RuntimeException) {
            failed = true
            _state.update { it.copy(error = "Couldn't build the preview: ${e.message ?: e.javaClass.simpleName}", isEmpty = false) }
        }
    }

    fun play() {
        if (_state.value.isEmpty) return
        if (failed || player == null) {
            // Recover from an earlier error (or released decoders) before playing.
            val project = lastProject ?: return
            lastKey = null
            setProject(project, lastQuality)
            if (failed) return
        }
        val p = player ?: return
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED || _state.value.positionUs >= _state.value.durationUs - 1_000) {
            p.seekTo(0)
            _state.update { it.copy(positionUs = 0) }
        }
        _state.update { it.copy(error = null) }
        p.play()
    }

    fun pause() {
        player?.pause()
        syncPosition()
    }

    fun togglePlayPause() = if (_state.value.playRequested) pause() else play()

    /** Seeks to a timeline time. Keep [setScrubbing] enabled while dragging for faster seeks. */
    fun seekTo(timeUs: Long) {
        val clamped = timeUs.coerceIn(0, _state.value.durationUs.coerceAtLeast(0))
        _state.update { it.copy(positionUs = clamped) }
        player?.seekTo(clamped / 1000)
    }

    /**
     * Scrubbing mode makes frequent seeks fast. Media3 suppresses playback while it is on and
     * resumes afterwards if the user was playing before.
     */
    fun setScrubbing(enabled: Boolean) {
        player?.setScrubbingModeEnabled(enabled)
    }

    /** Silences preview audio (used while recording a voiceover so the mic doesn't hear it). */
    fun setMuted(mute: Boolean) {
        muted = mute
        player?.volume = if (mute) 0f else 1f
    }

    /** Rebuilds the player and tries again after an error. */
    fun retry() {
        val project = lastProject ?: return
        releasePlayerOnly()
        failed = false
        lastKey = null
        _state.update { it.copy(error = null, suggestRestart = false) }
        setProject(project, lastQuality)
    }

    fun clearError() = _state.update { it.copy(error = null, suggestRestart = false) }

    /** Releases decoders and GL resources. The engine can be used again afterwards. */
    fun release() {
        releasePlayerOnly()
        lastKey = null
        _state.update { it.copy(isPlaying = false, playRequested = false, isBuffering = false, isReady = false) }
    }

    fun destroy() {
        release()
        scope.cancel()
    }

    private fun releasePlayerOnly() {
        stopPlaybackMonitoring()
        val p = player ?: return
        p.removeListener(listener)
        // Disconnect the output surface before releasing, so the GL pipeline lets go of it.
        surfaceView?.let { view -> runCatching { p.clearVideoSurfaceView(view) } }
        runCatching { p.stop() }
        p.release()
        player = null
        if (surfaceView != null) {
            // The next player gets its own surface (see surfaceGeneration).
            surfaceView = null
            _surfaceGeneration.value++
        }
    }

    // Playback monitoring --------------------------------------------------------------------

    private fun startPlaybackMonitoring() {
        if (positionJob == null) {
            positionJob = scope.launch {
                while (isActive) {
                    syncPosition()
                    delay(POSITION_INTERVAL_MS)
                }
            }
        }
        if (watchdogJob == null) watchdogJob = scope.launch { watchdog() }
    }

    private fun stopPlaybackMonitoring() {
        positionJob?.cancel()
        positionJob = null
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * Runs while playback is requested. If the position stops moving for [STALL_MS] although
     * the player is meant to be playing (and isn't scrubbing), it first re-seeks to the current
     * position; if that doesn't help it rebuilds the player at the same position.
     */
    private suspend fun watchdog() {
        var lastPosition = -1L
        var stalledSince = 0L
        var nudges = 0
        while (true) {
            delay(WATCHDOG_INTERVAL_MS)
            val p = player ?: return
            if (!p.playWhenReady || p.isScrubbingModeEnabled || p.playbackState == Player.STATE_ENDED) {
                stalledSince = 0L
                continue
            }
            val position = p.currentPosition
            val now = SystemClock.elapsedRealtime()
            if (position != lastPosition) {
                if (lastPosition >= 0) stallRebuilds = 0
                lastPosition = position
                stalledSince = 0L
                nudges = 0
                continue
            }
            if (stalledSince == 0L) {
                stalledSince = now
                continue
            }
            if (now - stalledSince < STALL_MS) continue
            stalledSince = 0L
            nudges++
            if (nudges <= 1) {
                p.seekTo(position)
                p.play()
            } else if (stallRebuilds >= MAX_STALL_REBUILDS || playerLayers > 1) {
                // Layered previews aren't rebuilt automatically: if several clips are too much
                // to decode, a new player would stall the same way, and every forced rebuild
                // risks leaving decoders behind.
                p.pause()
                _state.update {
                    it.copy(error = "Playback can't keep up at this point. Several high-resolution clips may be playing at once, or a file is still being downloaded by a cloud app. Your export isn't affected.")
                }
                return
            } else {
                // Still stuck: start over with a fresh player at the same position and keep playing.
                stallRebuilds++
                val project = lastProject ?: return
                _state.update { it.copy(positionUs = position * 1000) }
                releasePlayerOnly()
                lastKey = null
                setProject(project, lastQuality)
                player?.play()
                return
            }
        }
    }

    private fun syncPosition() {
        val p = player ?: return
        _state.update { it.copy(positionUs = p.currentPosition * 1000) }
    }

    private fun describe(error: PlaybackException): String {
        val detail = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.lastOrNull()
        val reason = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            -> "A media file in this project can no longer be opened. Relink it from the media panel."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            -> "A media file stopped responding. Files streamed from cloud apps (like Google Drive) do this; copy them to the device."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            -> "The device's video decoders are still taken, probably by an earlier preview that froze. Restart Clippr to free them; your project is saved."
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
            -> "The preview's graphics pipeline failed. Tap Try again; if it keeps happening, lower the preview quality."
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            -> "This device can't decode one of the clips at this size."
            else -> "Playback stopped (${error.errorCodeName})."
        }
        return if (detail != null && error.errorCode !in KNOWN_CODES) "$reason $detail" else reason
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 33L
        const val WATCHDOG_INTERVAL_MS = 400L
        const val STALL_MS = 2_500L
        const val MAX_STALL_REBUILDS = 2
        const val DECODER_RETRY_DELAY_MS = 600L
        val DECODER_CODES = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        )
        val KNOWN_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )
    }
}
