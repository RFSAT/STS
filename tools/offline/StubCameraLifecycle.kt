package androidx.camera.lifecycle

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCase

class ProcessCameraProvider {
    fun unbindAll() {}
    fun bindToLifecycle(owner: Any, selector: CameraSelector, vararg useCases: UseCase): Camera = Camera()
    companion object {
        @JvmStatic fun getInstance(c: android.content.Context): com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> =
            com.google.common.util.concurrent.ListenableFuture()
    }
}
