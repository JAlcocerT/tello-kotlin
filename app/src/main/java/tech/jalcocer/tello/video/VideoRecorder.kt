package tech.jalcocer.tello.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class VideoRecorder(private val context: Context) {
    private var muxer: MediaMuxer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var uri: android.net.Uri? = null
    private var track = -1
    private var startedAtUs = 0L
    private var lastPtsUs = -1L
    private var sampleCount = 0
    private var waitingForKeyframe = true

    val isRecording: Boolean get() = muxer != null

    fun start(sps: ByteArray, pps: ByteArray) {
        if (isRecording) return
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "tello_video_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Tello Native")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val created = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create recording")
        uri = created
        descriptor = context.contentResolver.openFileDescriptor(created, "w")
            ?: error("Could not open recording")
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            }
            muxer = MediaMuxer(descriptor!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                track = it.addTrack(format)
                it.start()
            }
            startedAtUs = System.nanoTime() / 1_000
            lastPtsUs = -1L
            sampleCount = 0
            waitingForKeyframe = true
        } catch (error: Throwable) {
            abort()
            throw error
        }
    }

    fun write(nal: NalUnit) {
        if (nal.type != 1 && nal.type != 5) return
        if (waitingForKeyframe && nal.type != 5) return
        if (nal.type == 5) waitingForKeyframe = false
        val active = muxer ?: return
        val now = System.nanoTime() / 1_000 - startedAtUs
        val pts = maxOf(now, lastPtsUs + 1)
        lastPtsUs = pts
        val info = MediaCodec.BufferInfo().apply {
            set(0, nal.bytes.size, pts, if (nal.type == 5) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        active.writeSampleData(track, ByteBuffer.wrap(nal.bytes), info)
        sampleCount++
    }

    fun stop() {
        val active = muxer ?: return
        if (sampleCount == 0) {
            abort()
            return
        }
        try {
            active.stop()
            active.release()
            muxer = null
            descriptor?.close()
            descriptor = null
            uri?.let { saved ->
                context.contentResolver.update(saved, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            }
            uri = null
        } catch (error: Throwable) {
            runCatching { active.release() }
            muxer = null
            runCatching { descriptor?.close() }
            descriptor = null
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            uri = null
            throw error
        }
    }

    fun abort() {
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        runCatching { descriptor?.close() }
        descriptor = null
        uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        uri = null
    }

    companion object {
        const val VIDEO_WIDTH = 960
        const val VIDEO_HEIGHT = 720
    }
}
