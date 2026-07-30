package androidx.camera.view

class PreviewView(c: android.content.Context? = null) : android.view.ViewGroup(c) {
    val surfaceProvider: Any? = null
    val meteringPointFactory: androidx.camera.core.MeteringPointFactory =
        androidx.camera.core.MeteringPointFactory()
}
