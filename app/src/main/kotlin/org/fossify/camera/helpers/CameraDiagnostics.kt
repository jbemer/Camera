package org.fossify.camera.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Diagnostic des caméras. Sortie : Logcat (tag [TAG]) + fichier
 * <externalFilesDir>/camera_diag.log.
 *
 * Étapes journalisées (le préfixe indique où ça casse) :
 *  ENUM_*      : CameraManager.getCameraIdList() / getCameraCharacteristics()
 *  RESOLVE_*   : choix du capteur principal
 *  PROVIDER_*  : ProcessCameraProvider.getInstance()
 *  QUALITIES_* : lecture des qualités vidéo/photo
 *  BIND_*      : CameraX bindToLifecycle()
 *  STATE_*     : machine d'état CameraX (OPENING / OPEN / erreur)
 *  RAW_OPEN_*  : ouverture Camera2 directe (sans CameraX) du capteur principal
 *  AVAIL_*     : callbacks de disponibilité du CameraService
 */
object CameraDiagnostics {
    const val TAG = "FossifyCamDiag"

    /** Ouvre une fois par processus le capteur principal en Camera2 pur, avant CameraX. */
    const val RAW_OPEN_PROBE_ENABLED = true

    @Volatile
    var probeAlreadyRun = false

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val availabilityRegistered = AtomicBoolean(false)

    /** ID actuellement signalés indisponibles par le CameraService (mis à jour par callback). */
    val unavailableIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, "camera_diag.log")
            if (file.exists() && file.length() > 512 * 1024) file.delete()
            logFile = file
        } catch (e: Exception) {
            Log.w(TAG, "Cannot init log file", e)
        }
        log("INIT", "log file = ${logFile?.absolutePath}")
    }

    @Synchronized
    fun log(stage: String, message: String, error: Throwable? = null) {
        val line = "[$stage] $message"
        if (error != null) Log.e(TAG, line, error) else Log.i(TAG, line)
        try {
            logFile?.appendText(
                buildString {
                    append(dateFormat.format(Date())).append(' ').append(line).append('\n')
                    if (error != null) append(Log.getStackTraceString(error)).append('\n')
                }
            )
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- énumération

    fun dumpEnumeration(context: Context) {
        log(
            "ENUM",
            "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "fingerprint=${Build.FINGERPRINT}"
        )
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try {
            cm.cameraIdList
        } catch (e: Exception) {
            log("ENUM_FAIL", "getCameraIdList() a échoué", e)
            return
        }
        log("ENUM", "cameraIdList = ${ids.joinToString()}")
        ids.forEach { describeCamera(cm, it) }
        registerAvailabilityCallback(cm)
    }

    private fun describeCamera(cm: CameraManager, id: String) {
        try {
            val c = cm.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "?"
            }
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList()
            val equiv = MainCameraResolver.equivalentFocalLengthMm(c)
            val zoomRange =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                } else {
                    null
                }
            log(
                "ENUM_ID",
                "id=$id facing=$facing " +
                    "equiv35mm=${equiv?.let { "%.1f".format(Locale.US, it) }} " +
                    "focals=${c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()} " +
                    "sensorMm=${c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)} " +
                    "pixelArray=${c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)} " +
                    "hwLevel=${c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)} " +
                    "capabilities=$caps " +
                    "LOGICAL_MULTI=${caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)} " +
                    "physicalIds=${c.physicalCameraIds} " +
                    "flash=${c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)} " +
                    "afModes=${c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList()} " +
                    "minFocusDist=${c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)} " +
                    "maxDigitalZoom=${c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)} " +
                    "zoomRatioRange=$zoomRange"
            )
            // Caméras physiques cachées derrière une caméra logique
            for (physId in c.physicalCameraIds) {
                try {
                    val pc = cm.getCameraCharacteristics(physId)
                    log(
                        "ENUM_PHYS",
                        "logique=$id physique=$physId focals=${pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()} " +
                            "sensorMm=${pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)}"
                    )
                } catch (e: Exception) {
                    log("ENUM_PHYS", "logique=$id physique=$physId : caractéristiques inaccessibles (${e.javaClass.simpleName})")
                }
            }
        } catch (e: Exception) {
            log("ENUM_FAIL", "getCameraCharacteristics($id) a échoué : ${e.javaClass.simpleName}", e)
        }
    }

    private fun registerAvailabilityCallback(cm: CameraManager) {
        if (!availabilityRegistered.compareAndSet(false, true)) return
        val thread = HandlerThread("diag-avail").also { it.start() }
        cm.registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                unavailableIds.remove(cameraId)
                log("AVAIL", "id=$cameraId disponible")
            }

            override fun onCameraUnavailable(cameraId: String) {
                unavailableIds.add(cameraId)
                log("AVAIL", "id=$cameraId indisponible (ouvert par un client, ou HAL en défaut)")
            }
        }, Handler(thread.looper))
    }

    // ---------------------------------------------------------------- ouverture Camera2 directe

    private fun errorName(code: Int) = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE(1)"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE(2)"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED(3)"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE(4)"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE(5)"
        else -> "UNKNOWN($code)"
    }

    /**
     * Ouvre [cameraId] avec Camera2 pur, attend onOpened/onError, referme, puis appelle [onDone]
     * sur le thread principal. Sépare « CameraX est en cause » de « Android/HAL refuse l'ouverture ».
     * Nécessite la permission CAMERA déjà accordée.
     */
    @SuppressLint("MissingPermission")
    fun probeOpen(context: Context, cameraId: String, onDone: () -> Unit) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val thread = HandlerThread("diag-probe").also { it.start() }
        val handler = Handler(thread.looper)
        val main = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val startNs = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startNs) / 1_000_000

        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
            // laisse le CameraService libérer le périphérique avant que CameraX ne l'ouvre
            main.postDelayed({ onDone() }, 400)
        }

        handler.postDelayed({
            log("RAW_OPEN_TIMEOUT", "id=$cameraId : aucun callback après ${elapsedMs()} ms")
            finish()
        }, 6000)

        log("RAW_OPEN", "openCamera(id=$cameraId) via Camera2 direct…")
        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    log("RAW_OPEN_OK", "id=$cameraId ouvert en ${elapsedMs()} ms")
                    device.close()
                    finish()
                }

                override fun onDisconnected(device: CameraDevice) {
                    log("RAW_OPEN_DISCONNECTED", "id=$cameraId après ${elapsedMs()} ms")
                    device.close()
                    finish()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    log("RAW_OPEN_ERROR", "id=$cameraId ${errorName(error)} après ${elapsedMs()} ms")
                    device.close()
                    finish()
                }
            }, handler)
        } catch (e: CameraAccessException) {
            log("RAW_OPEN_EXCEPTION", "id=$cameraId reason=${e.reason}", e)
            finish()
        } catch (e: Exception) {
            log("RAW_OPEN_EXCEPTION", "id=$cameraId ${e.javaClass.simpleName}", e)
            finish()
        }
    }

    // ---------------------------------------------------------------- CameraX

    @OptIn(ExperimentalCamera2Interop::class)
    fun describeBoundCamera(camera: Camera?) {
        if (camera == null) {
            log("BIND_FAIL", "camera == null après bindToLifecycle")
            return
        }
        try {
            val info = camera.cameraInfo
            val zoom = info.zoomState.value
            log(
                "BIND_OK",
                "CameraX utilise id=${Camera2CameraInfo.from(info).cameraId} " +
                    "flash=${info.hasFlashUnit()} " +
                    "zoom(min=${zoom?.minZoomRatio}, max=${zoom?.maxZoomRatio})"
            )
        } catch (e: Exception) {
            log("BIND_OK", "caméra liée, id illisible", e)
        }
    }
}
