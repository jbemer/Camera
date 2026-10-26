package org.fossify.camera.helpers

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Suit les caméras que le CameraService signale indisponibles (ouvertes, ou pilote en cours de
 * redémarrage) et permet d'attendre qu'elles soient de nouveau toutes libres.
 */
object CameraIdleWaiter {
    private val unavailable: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    private val registered = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val thread = HandlerThread("camera-availability").also { it.start() }
        cm.registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                unavailable.remove(cameraId)
            }

            override fun onCameraUnavailable(cameraId: String) {
                unavailable.add(cameraId)
            }
        }, Handler(thread.looper))
    }

    /**
     * Appelle [onReady] (thread principal) quand le service caméra répond, qu'aucune caméra
     * (hors [ignoredIds]) n'est signalée indisponible, et qu'au moins [minDelayMs] se sont écoulées.
     * Abandonne l'attente après 8 s.
     */
    fun waitUntilIdle(context: Context, minDelayMs: Long, ignoredIds: Set<String>, onReady: () -> Unit) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val start = SystemClock.elapsedRealtime()
        val check = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - start
                val alive = try {
                    cm.cameraIdList.isNotEmpty()
                } catch (e: Exception) {
                    false
                }
                val idle = alive && unavailable.none { it !in ignoredIds }
                if ((idle && elapsed >= minDelayMs) || elapsed > 8_000) {
                    CameraDiagnostics.log("WAIT", "reprise après $elapsed ms (libre=$idle)")
                    onReady()
                } else {
                    main.postDelayed(this, 100)
                }
            }
        }
        main.postDelayed(check, 100)
    }
}
