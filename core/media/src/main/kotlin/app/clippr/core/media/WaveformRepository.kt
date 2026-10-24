package app.clippr.core.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.clippr.core.model.MediaAsset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

/** Peak amplitudes of an audio track, [bucketsPerSecond] values per second, each 0..255. */
class Waveform(val bucketsPerSecond: Int, val peaks: ByteArray) {

    /** Highest normalized peak (0..1) between two source times. */
    fun peakBetween(startUs: Long, endUs: Long): Float {
        if (peaks.isEmpty()) return 0f
        val a = (startUs * bucketsPerSecond / 1_000_000L).toInt().coerceIn(0, peaks.lastIndex)
        val b = (endUs * bucketsPerSecond / 1_000_000L).toInt().coerceIn(a, peaks.lastIndex)
        var max = 0
        for (i in a..b) max = maxOf(max, peaks[i].toInt() and 0xFF)
        return max / 255f
    }
}

/**
 * Decodes audio once per asset to compute a compact peak envelope, then caches it on disk
 * (`files/waveforms`). Decoding is sequential (one file at a time) and cancellable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaveformRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "waveforms").apply { mkdirs() }
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val memory = HashMap<String, Waveform>()
    private val lock = Mutex()

    fun peek(asset: MediaAsset): Waveform? = synchronized(memory) { memory[asset.id] }

    suspend fun waveform(asset: MediaAsset): Waveform? {
        if (!asset.hasAudio) return null
        peek(asset)?.let { return it }
        return withContext(dispatcher) {
            lock.withLock {
                peek(asset)?.let { return@withLock it }
                val file = File(dir, "${asset.id}.wf")
                val wf = readFile(file) ?: extract(Uri.parse(asset.uri))?.also { writeFile(it, file) }
                wf?.also { synchronized(memory) { memory[asset.id] = it } }
            }
        }
    }

    private suspend fun extract(uri: Uri): Waveform? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(appContext, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val bucketCount = ((durationUs * BUCKETS_PER_SECOND) / 1_000_000L).toInt().coerceAtLeast(1) + 1
            val peaks = IntArray(bucketCount)

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIndex >= 0 -> {
                        val out = codec.getOutputBuffer(outIndex)
                        if (out != null && info.size > 0) {
                            // Decoders output 16-bit PCM unless float output is requested.
                            val shorts = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val start = info.offset / 2
                            val frames = info.size / 2 / channels.coerceAtLeast(1)
                            for (f in 0 until frames) {
                                val timeUs = info.presentationTimeUs + f * 1_000_000L / sampleRate
                                val bucket = (timeUs * BUCKETS_PER_SECOND / 1_000_000L).toInt()
                                if (bucket !in peaks.indices) continue
                                var peak = 0
                                for (c in 0 until channels) {
                                    val idx = start + f * channels + c
                                    if (idx < shorts.limit()) peak = maxOf(peak, abs(shorts.get(idx).toInt()))
                                }
                                if (peak > peaks[bucket]) peaks[bucket] = peak
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val bytes = ByteArray(peaks.size) { i -> (peaks[i] * 255 / Short.MAX_VALUE).coerceIn(0, 255).toByte() }
            return Waveform(BUCKETS_PER_SECOND, bytes)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun readFile(file: File): Waveform? {
        if (!file.exists()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) {
                    null
                } else {
                    val bps = input.readInt()
                    val n = input.readInt()
                    val bytes = ByteArray(n)
                    input.readFully(bytes)
                    Waveform(bps, bytes)
                }
            }
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private fun writeFile(wf: Waveform, file: File) {
        try {
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(wf.bucketsPerSecond)
                out.writeInt(wf.peaks.size)
                out.write(wf.peaks)
            }
        } catch (e: Exception) {
            file.delete()
        }
    }

    companion object {
        const val BUCKETS_PER_SECOND = 50
        private const val TIMEOUT_US = 10_000L
        private const val MAGIC = 0x434C5746 // "CLWF"
    }
}
