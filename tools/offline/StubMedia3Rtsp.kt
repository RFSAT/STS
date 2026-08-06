package androidx.media3.exoplayer.rtsp

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.MediaSource

object RtspMediaSource {
    class Factory {
        fun setForceUseRtpTcp(force: Boolean): Factory = this
        fun setUserAgent(agent: String): Factory = this
        fun setTimeoutMs(ms: Long): Factory = this
        fun createMediaSource(item: MediaItem): MediaSource = object : MediaSource {}
    }
}
