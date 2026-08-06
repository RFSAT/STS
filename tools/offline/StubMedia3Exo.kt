package androidx.media3.exoplayer

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

interface MediaSource

class ExoPlayer private constructor() {
    fun setVideoSurface(surface: android.view.Surface?) {}
    fun addListener(l: Player.Listener) {}
    fun setMediaSource(source: MediaSource) {}
    fun setMediaItem(item: MediaItem) {}
    fun prepare() {}
    var playWhenReady: Boolean = false
    fun release() {}

    class Builder(context: android.content.Context) {
        fun build(): ExoPlayer = ExoPlayer()
    }
}
