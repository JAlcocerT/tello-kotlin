package tech.jalcocer.tello.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

class TelloVideoPipeline(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onVideoReady: () -> Unit,
) {
    private val parser = H264AnnexBParser()
    private val recorder = VideoRecorder(context)
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var firstFrame = true
    private var recordingRequested = false

    @Synchronized
    fun setSurface(value: Surface?) {
        if (surface == value) return
        releaseCodec()
        surface = value
        maybeStartCodec()
    }

    @Synchronized
    fun accept(packet: ByteArray, length: Int) {
        parser.append(packet, length).forEach { nal ->
            when (nal.type) {
                7 -> sps = nal.bytes
                8 -> pps = nal.bytes
            }
            maybeStartCodec()
            maybeStartRecording()
            if (nal.type != 7 && nal.type != 8) queue(nal)
            if (recorder.isRecording) runCatching { recorder.write(nal) }
                .onFailure { onStatus("Recording stopped: ${it.message}"); recorder.abort(); recordingRequested = false }
        }
    }

    @Synchronized
    fun toggleRecording(): Boolean {
        if (recordingRequested || recorder.isRecording) {
            recordingRequested = false
            runCatching { recorder.stop() }.onFailure { onStatus("Could not finish recording: ${it.message}") }
            return false
        }
        recordingRequested = true
        maybeStartRecording()
        return true
    }

    @Synchronized
    fun reset() {
        recordingRequested = false
        if (recorder.isRecording) runCatching { recorder.stop() }
        releaseCodec()
        parser.reset()
        sps = null
        pps = null
        firstFrame = true
    }

    private fun maybeStartRecording() {
        if (!recordingRequested || recorder.isRecording) return
        val currentSps = sps ?: return
        val currentPps = pps ?: return
        runCatching { recorder.start(currentSps, currentPps) }
            .onSuccess { onStatus("Recording started") }
            .onFailure { recordingRequested = false; onStatus("Could not start recording: ${it.message}") }
    }

    private fun maybeStartCodec() {
        if (codec != null) return
        val target = surface?.takeIf { it.isValid } ?: return
        val currentSps = sps ?: return
        val currentPps = pps ?: return
        runCatching {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                VideoRecorder.VIDEO_WIDTH,
                VideoRecorder.VIDEO_HEIGHT,
            ).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(currentSps))
                setByteBuffer("csd-1", ByteBuffer.wrap(currentPps))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
            }
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                it.configure(format, target, null, 0)
                it.start()
                codec = it
                onStatus("H.264 decoder ready")
            }
        }.onFailure { onStatus("Video decoder failed: ${it.message}") }
    }

    private fun queue(nal: NalUnit) {
        val active = codec ?: return
        try {
            val inputIndex = active.dequeueInputBuffer(5_000)
            if (inputIndex >= 0) {
                active.getInputBuffer(inputIndex)?.apply {
                    clear()
                    if (remaining() < nal.bytes.size) {
                        active.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                        return
                    }
                    put(nal.bytes)
                }
                active.queueInputBuffer(inputIndex, 0, nal.bytes.size, System.nanoTime() / 1_000, 0)
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outputIndex = active.dequeueOutputBuffer(info, 0)
                if (outputIndex < 0) break
                active.releaseOutputBuffer(outputIndex, true)
                if (firstFrame) {
                    firstFrame = false
                    onVideoReady()
                }
            }
        } catch (error: Throwable) {
            onStatus("Video decoder stopped: ${error.message}")
            releaseCodec()
        }
    }

    private fun releaseCodec() {
        val active = codec ?: return
        codec = null
        runCatching { active.stop() }
        runCatching { active.release() }
    }
}
