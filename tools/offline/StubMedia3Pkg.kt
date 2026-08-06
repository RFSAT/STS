// Media3 / ExoPlayer: only what RtspFrameSource touches.
//
// A stub weaker than the thing it stands in for hides the errors it exists to
// catch — that has been true four times in this project — so these carry the
// real signatures: the listener method takes a PlaybackException, the factory
// setters return the factory, and the annotation exists because the RTSP
// source is marked unstable and the compiler insists on the opt-in.
package androidx.media3.common

class MediaItem {
    companion object { @JvmStatic fun fromUri(uri: String): MediaItem = MediaItem() }
}

open class PlaybackException(message: String? = null) : Exception(message) {
    val errorCodeName: String = ""
}

class VideoSize {
    val width: Int = 0
    val height: Int = 0
}

interface Player {
    interface Listener {
        fun onPlayerError(error: PlaybackException) {}
        fun onPlaybackStateChanged(state: Int) {}
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onVideoSizeChanged(videoSize: VideoSize) {}
        fun onRenderedFirstFrame() {}
    }
}
