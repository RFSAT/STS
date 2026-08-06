package android.media

/** Only what the RTSP client touches. Real signatures, so a wrong call fails
 *  here rather than in CI. */
class MediaCodec private constructor() {
    class BufferInfo {
        var size: Int = 0
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
    companion object {
        const val BUFFER_FLAG_KEY_FRAME = 1
        @JvmStatic fun createDecoderByType(mime: String): MediaCodec = MediaCodec()
    }
}

class MediaFormat {
    fun setByteBuffer(name: String, value: java.nio.ByteBuffer) {}
    companion object {
        const val MIMETYPE_VIDEO_AVC = "video/avc"
        @JvmStatic fun createVideoFormat(mime: String, w: Int, h: Int): MediaFormat = MediaFormat()
    }
}
