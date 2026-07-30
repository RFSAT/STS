package androidx.camera.core

import android.util.Size

interface ImageProxy {
    val width: Int
    val height: Int
    val format: Int
    val planes: Array<PlaneProxy>
    val imageInfo: ImageInfo
    fun close()
    interface PlaneProxy { val rowStride: Int; val pixelStride: Int; val buffer: java.nio.ByteBuffer }
}
interface ImageInfo { val rotationDegrees: Int }

class Camera { val cameraControl: CameraControl = CameraControl(); val cameraInfo: CameraInfo = CameraInfo() }
class CameraInfo { val sensorRotationDegrees: Int = 0 }
class CameraControl {
    fun startFocusAndMetering(a: FocusMeteringAction): Any? = null
    fun cancelFocusAndMetering(): Any? = null
    fun setLinearZoom(z: Float): Any? = null
    fun enableTorch(on: Boolean): Any? = null
}
class MeteringPoint
class MeteringPointFactory { fun createPoint(x: Float, y: Float): MeteringPoint = MeteringPoint() }
class FocusMeteringAction private constructor() {
    class Builder(point: MeteringPoint, flags: Int) {
        constructor(point: MeteringPoint) : this(point, FLAG_AF)
        fun disableAutoCancel(): Builder = this
        fun build(): FocusMeteringAction = FocusMeteringAction()
    }
    companion object { const val FLAG_AF = 1; const val FLAG_AE = 2; const val FLAG_AWB = 4 }
}

class CameraSelector { companion object { @JvmStatic val DEFAULT_BACK_CAMERA = CameraSelector()
                                          @JvmStatic val DEFAULT_FRONT_CAMERA = CameraSelector() } }

open class UseCase
class Preview : UseCase() {
    fun setSurfaceProvider(p: Any?) {}
    class Builder { fun build(): Preview = Preview() }
}
class ImageAnalysis : UseCase() {
    fun setAnalyzer(e: java.util.concurrent.Executor, a: (ImageProxy) -> Unit) {}
    class Builder {
        fun setResolutionSelector(s: androidx.camera.core.resolutionselector.ResolutionSelector): Builder = this
        fun setBackpressureStrategy(s: Int): Builder = this
        fun setOutputImageFormat(f: Int): Builder = this
        fun build(): ImageAnalysis = ImageAnalysis()
    }
    companion object { const val STRATEGY_KEEP_ONLY_LATEST = 0
                       const val OUTPUT_IMAGE_FORMAT_YUV_420_888 = 1 }
}
class ImageCapture : UseCase() {
    fun takePicture(e: java.util.concurrent.Executor, cb: OnImageCapturedCallback) {}
    abstract class OnImageCapturedCallback {
        open fun onCaptureSuccess(image: ImageProxy) {}
        open fun onError(exception: ImageCaptureException) {}
    }
    class Builder {
        fun setResolutionSelector(s: androidx.camera.core.resolutionselector.ResolutionSelector): Builder = this
        fun setCaptureMode(m: Int): Builder = this
        fun build(): ImageCapture = ImageCapture()
    }
    companion object { const val CAPTURE_MODE_MAXIMIZE_QUALITY = 0
                       const val CAPTURE_MODE_MINIMIZE_LATENCY = 1 }
}
class ImageCaptureException(msg: String) : Exception(msg)
