package android.media

class AudioRecord(source: Int, rate: Int, channel: Int, format: Int, bytes: Int) {
    val state: Int = STATE_INITIALIZED
    fun startRecording() {}
    fun stop() {}
    fun release() {}
    fun read(buffer: ShortArray, offset: Int, size: Int): Int = 0
    companion object { const val STATE_INITIALIZED = 1
        @JvmStatic fun getMinBufferSize(r: Int, c: Int, f: Int): Int = 4096 }
}
object MediaRecorder { object AudioSource { const val UNPROCESSED = 9; const val MIC = 1 } }
object AudioFormat {
    const val CHANNEL_IN_MONO = 16
    const val ENCODING_PCM_16BIT = 2
}
class MediaPlayer {
    fun setDataSource(p: String) {}
    fun prepareAsync() {}
    fun start() {}
    fun stop() {}
    fun release() {}
    fun setSurface(s: android.view.Surface?) {}
    var isPlaying: Boolean = false
    fun setOnErrorListener(l: ((MediaPlayer, Int, Int) -> Boolean)?) {}
    fun setOnPreparedListener(l: ((MediaPlayer) -> Unit)?) {}
    fun setOnVideoSizeChangedListener(l: ((MediaPlayer, Int, Int) -> Unit)?) {}
    val videoWidth: Int = 0
    val videoHeight: Int = 0
}
