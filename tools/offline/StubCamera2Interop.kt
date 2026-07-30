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
