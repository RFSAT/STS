package androidx.camera.core
interface ImageProxy {
    val width: Int
    val height: Int
    val format: Int
    val planes: Array<PlaneProxy>
    interface PlaneProxy { val rowStride: Int; val pixelStride: Int; val buffer: java.nio.ByteBuffer }
}
