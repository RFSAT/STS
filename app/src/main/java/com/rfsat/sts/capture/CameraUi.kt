package com.rfsat.sts.capture

import android.app.Activity
import androidx.appcompat.app.AlertDialog

/** Shared camera-type chooser used by both tabs and Settings. */
object CameraUi {
    fun chooseType(activity: Activity, current: CameraType, onChosen: (CameraType) -> Unit) {
        val types = CameraType.values()
        AlertDialog.Builder(activity)
            .setTitle("Camera")
            .setSingleChoiceItems(types.map { it.label }.toTypedArray(), types.indexOf(current)) { d, w ->
                d.dismiss(); onChosen(types[w])
            }
            .show()
    }

    fun promptHost(activity: Activity, type: CameraType, current: String, onSet: (String) -> Unit) {
        val et = android.widget.EditText(activity).apply { setText(current); hint = "camera IP / host" }
        AlertDialog.Builder(activity)
            .setTitle("${type.label} address")
            .setView(et)
            .setPositiveButton("Save") { _, _ -> onSet(et.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
