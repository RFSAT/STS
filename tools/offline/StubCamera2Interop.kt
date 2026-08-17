package androidx.camera.camera2.interop

@RequiresOptIn
annotation class ExperimentalCamera2Interop

class CaptureRequestOptions private constructor() {
    class Builder {
        fun <T : Any> setCaptureRequestOption(key: android.hardware.camera2.CaptureRequest.Key<T>, value: T): Builder = this
        fun build(): CaptureRequestOptions = CaptureRequestOptions()
    }
}
class Camera2CameraControl private constructor() {
    fun setCaptureRequestOptions(o: CaptureRequestOptions): Any? = null
    companion object {
        @JvmStatic fun from(c: androidx.camera.core.CameraControl): Camera2CameraControl = Camera2CameraControl()
    }
}

/** The camera's own characteristics, as CameraX hands them over. */
class Camera2CameraInfo private constructor() {
    fun <T> getCameraCharacteristic(key: android.hardware.camera2.CameraCharacteristics.Key<T>): T? = null
    companion object {
        @JvmStatic fun from(info: androidx.camera.core.CameraInfo): Camera2CameraInfo =
            Camera2CameraInfo()
    }
}
