package app.clippr.core.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records a voiceover from the microphone into an AAC (.m4a) file in Clippr's private storage
 * ([MediaStorage.voiceoverDir]). The caller must hold the RECORD_AUDIO permission.
 *
 * Not thread-safe; use from the main thread.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /** Elapsed recording time in milliseconds. */
    val elapsedMs: Long get() = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAt

    /** Microphone level since the last call, in `0..1`. */
    fun level(): Float = try {
        ((recorder?.maxAmplitude ?: 0) / 32767f).coerceIn(0f, 1f)
    } catch (e: IllegalStateException) {
        0f
    }

    @Throws(IOException::class)
    fun start() {
        check(recorder == null) { "Already recording" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val out = File(MediaStorage.voiceoverDir(context), "Voiceover-$stamp.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            out.delete()
            throw IOException("The microphone couldn't start (${e.message ?: e.javaClass.simpleName})", e)
        }
        recorder = r
        file = out
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Stops and returns the recording, or null if it was too short to keep. */
    fun stop(): File? {
        val r = recorder ?: return null
        val out = file
        val duration = elapsedMs
        recorder = null
        file = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // Thrown when stopped before any audio was captured.
            false
        } finally {
            r.release()
        }
        if (!ok || out == null || duration < MIN_DURATION_MS || out.length() == 0L) {
            out?.delete()
            return null
        }
        return out
    }

    /** Stops and throws the recording away. */
    fun cancel() {
        stop()?.delete()
    }

    private companion object {
        const val MIN_DURATION_MS = 300L
    }
}
