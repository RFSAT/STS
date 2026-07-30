package android.hardware.camera2

class CaptureRequest {
    class Key<T>
    companion object {
        @JvmStatic val CONTROL_AE_LOCK = Key<Boolean>()
        @JvmStatic val CONTROL_AWB_LOCK = Key<Boolean>()
        @JvmStatic val CONTROL_AF_MODE = Key<Int>()
    }
}
object CameraMetadata { const val CONTROL_AF_MODE_OFF = 0 }
