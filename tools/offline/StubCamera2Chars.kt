package android.hardware.camera2

/** Only the two characteristics the field-of-view provider reads. */
class CameraCharacteristics {
    class Key<T>
    operator fun <T> get(key: Key<T>): T? = null
    companion object {
        @JvmField val LENS_INFO_AVAILABLE_FOCAL_LENGTHS: Key<FloatArray> = Key()
        @JvmField val SENSOR_INFO_PHYSICAL_SIZE: Key<android.util.SizeF> = Key()
    }
}
