package androidx.activity.result.contract

abstract class ActivityResultContract<I, O>

object ActivityResultContracts {
    class GetContent : ActivityResultContract<String, android.net.Uri?>()
    class CreateDocument(mime: String) : ActivityResultContract<String, android.net.Uri?>()
    class RequestPermission : ActivityResultContract<String, Boolean>()
    class TakePicture : ActivityResultContract<android.net.Uri, Boolean>()
}
