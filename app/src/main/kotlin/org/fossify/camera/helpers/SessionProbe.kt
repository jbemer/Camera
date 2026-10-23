package org.fossify.camera.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Test Camera2 pur (sans CameraX) du capteur dorsal principal, une seule fois (résultat mémorisé) :
 *   1. session sur la caméra logique, flux logique par défaut ;
 *   2. session sur la caméra logique avec un flux demandé à UN capteur physique caché (ex. 5, 6).
 * Chaque test compte les images reçues pendant ~2 s. Le résultat décide si CameraX doit demander
 * un ID physique (CameraSelector.Builder.setPhysicalCameraId).
 * Résultat mémorisé dans les préférences ; effacer les données de l'app pour refaire le test.
 */
object SessionProbe {
    private const val PREFS = "camera_diag_prefs"
    private const val KEY_RESULT = "probe_result_v2"
    private const val MIN_FRAMES = 3

    private enum class State { IDLE, RUNNING, DONE }

    private var state = State.IDLE
    private val pending = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private class Result(val physicalId: String?, val ok: Boolean, val frames: Int, val focal: Float?)

    /** À appeler depuis le thread principal. */
    fun ensureRun(context: Context, onDone: () -> Unit) {
        when (state) {
            State.DONE -> { onDone(); return }
            State.RUNNING -> { pending.add(onDone); return }
            State.IDLE -> {}
        }
        state = State.RUNNING
        pending.add(onDone)
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun complete() {
            state = State.DONE
            val callbacks = ArrayList(pending)
            pending.clear()
            callbacks.forEach { it() }
        }

        val saved = prefs.getString(KEY_RESULT, null)
        if (saved != null) {
            CameraDiagnostics.log("PROBE", "résultat mémorisé : $saved")
            applyResult(saved)
            complete()
            return
        }

        val choice = MainCameraResolver.resolve(app)
        if (choice == null || !choice.isLogicalMultiCamera) {
            CameraDiagnostics.log("PROBE", "capteur principal non logique ou introuvable : test inutile")
            complete()
            return
        }
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = try {
            cm.getCameraCharacteristics(choice.cameraId)
        } catch (e: Exception) {
            CameraDiagnostics.log("PROBE_FAIL", "caractéristiques illisibles", e)
            complete()
            return
        }
        val logicalFocal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        // Capteurs physiques cachés = ceux qui ne sont pas aussi exposés comme caméra dorsale autonome
        val hidden = characteristics.physicalCameraIds.filter { it !in choice.allBackIds }.sorted()
        val tests: List<String?> = listOf<String?>(null) + hidden
        CameraDiagnostics.log("PROBE", "tests prévus sur id=${choice.cameraId} : ${tests.map { it ?: "logique" }}")

        runTests(app, choice.cameraId, tests, 0, ArrayList()) { results ->
            val decision = decide(results, logicalFocal)
            CameraDiagnostics.log("PROBE_DECISION", decision)
            prefs.edit().putString(KEY_RESULT, decision).apply()
            applyResult(decision)
            waitForCamera(app, choice.cameraId, 1500) { complete() }
        }
    }

    private fun applyResult(result: String) {
        if (result.startsWith("PHYSICAL:")) {
            MainCameraResolver.setPhysicalOverride(result.removePrefix("PHYSICAL:"))
        }
    }

    private fun decide(results: List<Result>, logicalFocal: Float?): String {
        val logical = results.firstOrNull { it.physicalId == null }
        if (logical?.ok == true) return "LOGICAL_OK"
        val okPhysical = results.filter { it.physicalId != null && it.ok }
        if (okPhysical.isEmpty()) return "NONE"
        var best: Result = okPhysical.first()
        if (logicalFocal != null) {
            // le capteur principal est celui dont la focale réelle est la plus proche de celle de la caméra logique
            best = okPhysical.minByOrNull { abs((it.focal ?: 999f) - logicalFocal) } ?: best
        }
        return "PHYSICAL:${best.physicalId}"
    }

    private fun runTests(
        context: Context,
        cameraId: String,
        tests: List<String?>,
        index: Int,
        acc: MutableList<Result>,
        finished: (List<Result>) -> Unit,
    ) {
        if (index >= tests.size) {
            finished(acc)
            return
        }
        waitForCamera(context, cameraId, if (index == 0) 0 else 1500) {
            runOne(context, cameraId, tests[index]) { r ->
                acc.add(r)
                runTests(context, cameraId, tests, index + 1, acc, finished)
            }
        }
    }

    /** Attend que le CameraService annonce [cameraId] présent et disponible (max 12 s). */
    private fun waitForCamera(context: Context, cameraId: String, minDelayMs: Long, onReady: () -> Unit) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val start = SystemClock.elapsedRealtime()
        val check = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - start
                val present = try {
                    cameraId in cm.cameraIdList
                } catch (e: Exception) {
                    false
                }
                val ready = present && cameraId !in CameraDiagnostics.unavailableIds
                if ((ready && elapsed >= minDelayMs) || elapsed > 12_000) {
                    if (!ready) CameraDiagnostics.log("PROBE", "id=$cameraId toujours indisponible après ${elapsed} ms")
                    onReady()
                } else {
                    main.postDelayed(this, 250)
                }
            }
        }
        main.postDelayed(check, minDelayMs.coerceAtMost(250))
    }

    private fun pickSize(cm: CameraManager, id: String): Size {
        val sizes = try {
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
        } catch (e: Exception) {
            null
        }
        return sizes?.firstOrNull { it.width == 640 && it.height == 480 }
            ?: sizes?.filter { it.width * 3 == it.height * 4 }?.minByOrNull { it.width * it.height }
            ?: Size(640, 480)
    }

    @SuppressLint("MissingPermission")
    private fun runOne(context: Context, cameraId: String, physicalId: String?, done: (Result) -> Unit) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val label = "id=$cameraId flux=${physicalId?.let { "physique $it" } ?: "logique"}"
        val thread = HandlerThread("diag-session").also { it.start() }
        val handler = Handler(thread.looper)
        val executor = Executor { handler.post(it) }
        val finished = AtomicBoolean(false)
        val frames = AtomicInteger(0)
        val focal = AtomicReference<Float?>(null)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        val startNs = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startNs) / 1_000_000

        fun finish(reason: String) {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            val ok = frames.get() >= MIN_FRAMES
            CameraDiagnostics.log(
                "SESSION_RESULT",
                "$label : ${if (ok) "OK" else "ÉCHEC"} ($reason) images=${frames.get()} focale=${focal.get()} durée=${elapsedMs()} ms"
            )
            try { session?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            thread.quitSafely()
            val result = Result(physicalId, ok, frames.get(), focal.get())
            main.postDelayed({ done(result) }, 400)
        }

        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val n = frames.incrementAndGet()
                if (n == 1) {
                    val logicalFocal = result.get(CaptureResult.LENS_FOCAL_LENGTH)
                    val physical = try { result.physicalCameraResults } catch (e: Exception) { null }
                    val physicalDesc = physical?.entries?.joinToString {
                        "${it.key}:focale=${it.value.get(CaptureResult.LENS_FOCAL_LENGTH)}"
                    }
                    focal.set(
                        (physicalId?.let { physical?.get(it)?.get(CaptureResult.LENS_FOCAL_LENGTH) }) ?: logicalFocal
                    )
                    CameraDiagnostics.log(
                        "SESSION_FRAME",
                        "$label : 1re image après ${elapsedMs()} ms, focale logique=$logicalFocal, résultats physiques=[$physicalDesc]"
                    )
                }
            }

            override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                CameraDiagnostics.log("SESSION_CAPTURE_FAILED", "$label : reason=${failure.reason}")
            }
        }

        fun startSession(d: CameraDevice) {
            val size = pickSize(cm, cameraId)
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            reader = r
            r.setOnImageAvailableListener({ rd ->
                try { rd.acquireLatestImage()?.close() } catch (_: Exception) {}
            }, handler)
            val output = OutputConfiguration(r.surface)
            if (physicalId != null) output.setPhysicalCameraId(physicalId)
            CameraDiagnostics.log("SESSION", "$label : createCaptureSession ${size.width}x${size.height} YUV")
            try {
                d.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(output),
                        executor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (finished.get()) { s.close(); return }
                                session = s
                                CameraDiagnostics.log("SESSION", "$label : onConfigured après ${elapsedMs()} ms")
                                try {
                                    val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    builder.addTarget(r.surface)
                                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
                                    }
                                    s.setRepeatingRequest(builder.build(), captureCallback, handler)
                                    handler.postDelayed({ finish("fin du test") }, 2000)
                                } catch (e: Exception) {
                                    CameraDiagnostics.log("SESSION_EXCEPTION", "$label : setRepeatingRequest", e)
                                    finish("exception setRepeatingRequest")
                                }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                finish("onConfigureFailed")
                            }
                        }
                    )
                )
            } catch (e: Exception) {
                CameraDiagnostics.log("SESSION_EXCEPTION", "$label : createCaptureSession", e)
                finish("exception createCaptureSession")
            }
        }

        handler.postDelayed({ finish("délai dépassé (8 s)") }, 8000)
        CameraDiagnostics.log("SESSION", "$label : openCamera…")
        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (finished.get()) { d.close(); return }
                    device = d
                    CameraDiagnostics.log("SESSION", "$label : ouvert en ${elapsedMs()} ms")
                    startSession(d)
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    finish("onDisconnected")
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    finish("onError code=$error")
                }
            }, handler)
        } catch (e: Exception) {
            CameraDiagnostics.log("SESSION_EXCEPTION", "$label : openCamera", e)
            finish("exception openCamera")
        }
    }
}
