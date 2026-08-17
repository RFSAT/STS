package android.media

/** Only what the RTSP client touches. Real signatures, so a wrong call fails
 *  here rather than in CI. */
class MediaCodec private constructor() {
    class BufferInfo {
        var size: Int = 0
        var offset: Int = 0
        var flags: Int = 0
        var presentationTimeUs: Long = 0
        fun set(offset: Int, size: Int, ptsUs: Long, flags: Int) {}
    }
    fun configure(format: MediaFormat, surface: android.view.Surface?, crypto: Any?, flags: Int) {}
    fun start() {}
    fun stop() {}
    fun release() {}
    fun dequeueInputBuffer(timeoutUs: Long): Int = -1
    fun getInputBuffer(index: Int): java.nio.ByteBuffer? = null
    fun queueInputBuffer(index: Int, offset: Int, size: Int, ptsUs: Long, flags: Int) {}
    fun dequeueOutputBuffer(info: BufferInfo, timeoutUs: Long): Int = -1
    fun releaseOutputBuffer(index: Int, render: Boolean) {}
    fun getOutputBuffer(index: Int): java.nio.ByteBuffer? = null
    fun getOutputImage(index: Int): Image? = null
    fun getOutputFormat(): MediaFormat = MediaFormat()
    fun flush() {}
    companion object {
        const val BUFFER_FLAG_KEY_FRAME = 1
        const val BUFFER_FLAG_END_OF_STREAM = 4
        const val INFO_TRY_AGAIN_LATER = -1
        const val INFO_OUTPUT_FORMAT_CHANGED = -2
        const val INFO_OUTPUT_BUFFERS_CHANGED = -3
        @JvmStatic fun createDecoderByType(mime: String): MediaCodec = MediaCodec()
    }
}

class MediaFormat {
    fun setByteBuffer(name: String, value: java.nio.ByteBuffer) {}
    fun setInteger(name: String, value: Int) {}
    fun getInteger(name: String): Int = 0
    fun getString(name: String): String? = null
    fun containsKey(name: String): Boolean = false
    companion object {
        const val MIMETYPE_VIDEO_AVC = "video/avc"
        const val KEY_MIME = "mime"
        const val KEY_ROTATION = "rotation-degrees"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        @JvmStatic fun createVideoFormat(mime: String, w: Int, h: Int): MediaFormat = MediaFormat()
    }
}

/** Demuxing a recorded clip, for the camera-download path. */
class MediaExtractor {
    val trackCount: Int = 0
    val sampleFlags: Int = 0
    val sampleTime: Long = 0
    fun setDataSource(path: String) {}
    fun getTrackFormat(index: Int): MediaFormat = MediaFormat()
    fun selectTrack(index: Int) {}
    fun readSampleData(buffer: java.nio.ByteBuffer, offset: Int): Int = -1
    fun advance(): Boolean = false
    fun seekTo(timeUs: Long, mode: Int) {}
    fun release() {}
    companion object {
        const val SEEK_TO_CLOSEST_SYNC = 2
        const val SEEK_TO_PREVIOUS_SYNC = 0
    }
}

class MediaMetadataRetriever {
    fun setDataSource(path: String) {}
    fun extractMetadata(key: Int): String? = null
    fun getFrameAtTime(timeUs: Long, option: Int): android.graphics.Bitmap? = null
    fun release() {}
    companion object {
        const val METADATA_KEY_DURATION = 9
        const val METADATA_KEY_VIDEO_ROTATION = 24
        const val OPTION_CLOSEST_SYNC = 2
    }
}

/** A decoded frame handed back through an ImageReader. */
class Image {
    class Plane {
        val buffer: java.nio.ByteBuffer = java.nio.ByteBuffer.allocate(0)
        val rowStride: Int = 0
        val pixelStride: Int = 0
    }
    val planes: Array<Plane> = emptyArray()
    val width: Int = 0
    val height: Int = 0
    fun close() {}
}

class ImageReader private constructor() {
    val surface: android.view.Surface = android.view.Surface(null)
    fun acquireLatestImage(): Image? = null
    fun acquireNextImage(): Image? = null
    fun setOnImageAvailableListener(l: Any?, handler: Any?) {}
    fun close() {}
    companion object {
        @JvmStatic fun newInstance(w: Int, h: Int, format: Int, maxImages: Int): ImageReader =
            ImageReader()
    }
}
