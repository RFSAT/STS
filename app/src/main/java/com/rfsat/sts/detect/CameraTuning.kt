package com.rfsat.sts.detect

import android.hardware.camera2.CaptureRequest
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import com.rfsat.sts.log.Logger

/** Analysis resolutions offered on the live screen. */
enum class CaptureResolution(val label: String, val size: Size) {
    HD("1280 x 720 — fastest", Size(1280, 720)),
    FHD("1920 x 1080 — default", Size(1920, 1080)),
    QHD("2560 x 1440", Size(2560, 1440)),
    UHD("3840 x 2160 — 4K, if supported", Size(3840, 2160));

    companion object {
        val DEFAULT = FHD
    }
}

/**
 * ============================================================================
 *  HOLDING THE CAMERA STILL — EXPOSURE, WHITE BALANCE AND FOCUS
 * ============================================================================
 *
 * A phone camera continuously re-meters and re-focuses. For taking pictures
 * that is what you want. For scoring a target it is close to the worst
 * possible behaviour, and for the differential path it is fatal:
 *
 *   - DIFFERENCING assumes two frames of the same card differ only where a
 *     shot has arrived. If the camera re-meters between them the whole frame
 *     changes brightness, every pixel differs, and the detector is looking for
 *     a 40-level hole in a field that has just moved by more than that. A
 *     passing cloud, or the shooter's arm entering frame and being metered
 *     for, is enough.
 *   - RE-FOCUSING moves the lens, and moving the lens changes the field of
 *     view slightly on almost every phone. The registration is then stale:
 *     the rings are no longer where the app was told they are.
 *   - AUTO WHITE BALANCE shifts the colour channel that hole detection is
 *     measured in, which is distance from the MEASURED paper colour.
 *
 * So all three are locked once the card is registered, and the lock is
 * reported rather than silent, because a locked camera in changing light will
 * eventually need releasing and the shooter has to know it is on.
 */
object CameraTuning {

    var locked: Boolean = false
        private set

    /**
     * Locks exposure, white balance and focus.
     *
     * Focus is locked by metering once at [point] with auto-cancel disabled —
     * CameraX's own idiom — rather than by switching the AF mode off, which
     * on many devices then requires driving the lens by hand and focuses at
     * infinity if you do not.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun lock(camera: Camera?, point: MeteringPoint?): Boolean {
        val cam = camera ?: return false
        return runCatching {
            if (point != null) {
                cam.cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or
                            FocusMeteringAction.FLAG_AWB
                    ).disableAutoCancel().build()
                )
            }
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                    .build()
            )
            locked = true
            Logger.i("CameraTuning", "exposure, white balance and focus locked")
            true
        }.getOrElse {
            Logger.w("CameraTuning", "could not lock the camera: ${it.message}")
            false
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun unlock(camera: Camera?) {
        val cam = camera ?: return
        runCatching {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                    .build()
            )
            cam.cameraControl.cancelFocusAndMetering()
            locked = false
            Logger.i("CameraTuning", "camera released back to automatic")
        }.onFailure { Logger.w("CameraTuning", "could not release the camera: ${it.message}") }
    }

    fun forget() { locked = false }
}
