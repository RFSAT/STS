package android

object Manifest {
    object permission {
        const val CAMERA = "android.permission.CAMERA"
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
        const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
        const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
        const val INTERNET = "android.permission.INTERNET"
    }
    object R {
        object attr { const val textColorPrimary = 0; const val textColorSecondary = 0
                      const val listPreferredItemHeightSmall = 0 }
        object color { const val white = 0; const val black = 0 }
        object layout { const val simple_list_item_1 = 0; const val simple_spinner_item = 0
                        const val simple_spinner_dropdown_item = 0 }
    }
}
