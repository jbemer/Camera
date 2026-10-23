package org.fossify.camera.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
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
 * Tests Camera2 purs (sans CameraX), exécutés une seule fois (résultat mémorisé).
 *
 * Phase 1 : flux YUV 640x480 sur la caméra logique, puis sur chaque capteur physique caché.
 * Phase 2 (si un capteur physique fonctionne) : combinaisons proches de CameraX
 *           (YUV + JPEG pleine taille, puis aperçu PRIVATE + JPEG pleine taille).
 *
 * Le capteur physique retenu en phase 1 est imposé à CameraX (voir CameraXPreview).
 * Effacer les données de l'app pour refaire les tests.
 */
object SessionProbe {
    private const val PREFS = "camera_diag_prefs"
    private const val KEY_RESULT = "probe_result_v3"
    private const val MIN_FRAMES = 3

    private enum class State { IDLE, RUNNING, DONE }

    private var state = State.IDLE
    private val pending = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private class Spec(
        val title: String,
        val physicalId: String?,
        val previewPrivate: Boolean = false,
        val jpeg: Boolean = false,
    )

    private class Result(val spec: Spec, val ok: Boolean, val focal: Float?)

    /** À appeler depuis le thread principal. */
    fun ensureRun(context: Context, onDone: () -> Unit) {
        when (state) {
            State.DONE -> {
                onDone()
                return
            }
            State.RUNNING -> {
                pending.add(onDone)
                return
            }
            State.IDLE -> Unit
        }
        state = State.RUNNING
        pending.add(onDone)
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
        val logicalFocal =
            characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        // Capteurs physiques cachés = ceux qui ne sont pas aussi exposés comme caméra dorsale autonome
        val hidden = characteristics.physicalCameraIds.filter { it !in choice.allBackIds }.sorted()
        val phase1 = listOf(Spec("logique YUV", null)) + hidden.map { Spec("physique $it YUV", it) }
        CameraDiagnostics.log("PROBE", "phase 1 sur id=${choice.cameraId} : ${phase1.map { it.title }}")

        runTests(app, choice.cameraId, phase1, 0, ArrayList()) { results1 ->
            val decision = decide(results1, logicalFocal)
            if (!decision.startsWith("PHYSICAL:")) {
                conclude(app, prefs, choice.cameraId, decision)
                return@runTests
            }
            val chosen = decision.removePrefix("PHYSICAL:")
            val phase2 = listOf(
                Spec("physique $chosen YUV + JPEG max", chosen, jpeg = true),
                Spec("physique $chosen PRIVATE + JPEG max", chosen, previewPrivate = true, jpeg = true),
            )
            CameraDiagnostics.log("PROBE", "phase 2 : ${phase2.map { it.title }}")
            runTests(app, choice.cameraId, phase2, 0, ArrayList()) {
                conclude(app, prefs, choice.cameraId, decision)
            }
        }
    }

    private fun complete() {
        state = State.DONE
        val callbacks = ArrayList(pending)
        pending.clear()
        callbacks.forEach { it() }
    }

    private fun conclude(app: Context, prefs: SharedPreferences, cameraId: String, decision: String) {
        CameraDiagnostics.log("PROBE_DECISION", decision)
        prefs.edit().putString(KEY_RESULT, decision).apply()
        applyResult(decision)
        waitForCamera(app, cameraId, 1500) { complete() }
    }

    private fun applyResult(result: String) {
        if (result.startsWith("PHYSICAL:")) {
            MainCameraResolver.setPhysicalOverride(result.removePrefix("PHYSICAL:"))
        }
    }

    private fun decide(results: List<Result>, logicalFocal: Float?): String {
        val logical = results.firstOrNull { it.spec.physicalId == null }
        if (logical?.ok == true) return "LOGICAL_OK"
        val okPhysical = results.filter { it.spec.physicalId != null && it.ok }
        if (okPhysical.isEmpty()) return "NONE"
        var best: Result = okPhysical.first()
        if (logicalFocal != null) {
            // le capteur principal a la focale réelle la plus proche de celle de la caméra logique
            best = okPhysical.minByOrNull { abs((it.focal ?: 999f) - logicalFocal) } ?: best
        }
        return "PHYSICAL:${best.spec.physicalId}"
    }

    private fun runTests(
        context: Context,
        cameraId: String,
        specs: List<Spec>,
        index: Int,
        acc: MutableList<Result>,
        finished: (List<Result>) -> Unit,
    ) {
        if (index >= specs.size) {
            finished(acc)
            return
        }
        waitForCamera(context, cameraId, if (index == 0) 0 else 1500) {
            runOne(context, cameraId, specs[index]) { r ->
                acc.add(r)
                runTests(context, cameraId, specs, index + 1, acc, finished)
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
                    if (!ready) CameraDiagnostics.log("PROBE", "id=$cameraId toujours indisponible après $elapsed ms")
                    onReady()
                } else {
                    main.postDelayed(this, 250)
                }
            }
        }
        main.postDelayed(check, minDelayMs.coerceAtMost(250L))
    }

    /**
     * Attend qu'aucune caméra ne soit signalée utilisée et que le CameraService réponde,
     * au moins [minDelayMs] (max 8 s), puis appelle [onReady] sur le thread principal.
     */
    fun waitUntilIdle(context: Context, minDelayMs: Long, onReady: () -> Unit) {
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
                val idle = alive && CameraDiagnostics.unavailableIds.isEmpty()
                if ((idle && elapsed >= minDelayMs) || elapsed > 8_000) {
                    CameraDiagnostics.log(
                        "PROVIDER",
                        "caméras ${if (idle) "libres" else "encore occupées"} après $elapsed ms, réouverture"
                    )
                    onReady()
                } else {
                    main.postDelayed(this, 150)
                }
            }
        }
        main.postDelayed(check, 150)
    }

    private fun pickYuvSize(cm: CameraManager, id: String): Size {
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

    private fun pickPrivateSize(cm: CameraManager, id: String): Size {
        val sizes = try {
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)
        } catch (e: Exception) {
            null
        }
        return sizes?.firstOrNull { it.width == 1440 && it.height == 1080 }
            ?: sizes?.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: sizes?.firstOrNull { it.width <= 1920 }
            ?: Size(1280, 720)
    }

    private fun pickJpegSize(cm: CameraManager, id: String): Size {
        val sizes = try {
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
        } catch (e: Exception) {
            null
        }
        return sizes?.maxByOrNull { it.width.toLong() * it.height } ?: Size(4000, 3000)
    }

    @SuppressLint("MissingPermission")
    private fun runOne(context: Context, cameraId: String, spec: Spec, done: (Result) -> Unit) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val label = "id=$cameraId [${spec.title}]"
        val physicalId = spec.physicalId
        val thread = HandlerThread("diag-session").also { it.start() }
        val handler = Handler(thread.looper)
        val executor = Executor { handler.post(it) }
        val finished = AtomicBoolean(false)
        val frames = AtomicInteger(0)
        val jpegBytes = AtomicInteger(0)
        val focal = AtomicReference<Float?>(null)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var previewReader: ImageReader? = null
        var jpegReader: ImageReader? = null
        val startNs = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startNs) / 1_000_000

        fun finish(reason: String) {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            val ok = frames.get() >= MIN_FRAMES && (!spec.jpeg || jpegBytes.get() > 0)
            CameraDiagnostics.log(
                "SESSION_RESULT",
                "$label : ${if (ok) "OK" else "ÉCHEC"} ($reason) images=${frames.get()} " +
                    "jpegOctets=${jpegBytes.get()} focale=${focal.get()} durée=${elapsedMs()} ms"
            )
            try { session?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            try { previewReader?.close() } catch (_: Exception) {}
            try { jpegReader?.close() } catch (_: Exception) {}
            thread.quitSafely()
            val result = Result(spec, ok, focal.get())
            main.postDelayed({ done(result) }, 400)
        }

        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                // les captures « still » ne comptent pas comme images d'aperçu
                if (request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) ==
                    CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
                ) {
                    CameraDiagnostics.log("SESSION_STILL", "$label : capture JPEG terminée après ${elapsedMs()} ms")
                    return
                }
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
            val previewSize = if (spec.previewPrivate) pickPrivateSize(cm, cameraId) else pickYuvSize(cm, cameraId)
            val pr = if (spec.previewPrivate) {
                ImageReader.newInstance(
                    previewSize.width, previewSize.height, ImageFormat.PRIVATE, 3,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE.toLong()
                )
            } else {
                ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 3)
            }
            previewReader = pr
            pr.setOnImageAvailableListener({ rd ->
                try { rd.acquireLatestImage()?.close() } catch (_: Exception) {}
            }, handler)

            val outputs = ArrayList<OutputConfiguration>()
            val previewOutput = OutputConfiguration(pr.surface)
            if (physicalId != null) previewOutput.setPhysicalCameraId(physicalId)
            outputs.add(previewOutput)

            var jr: ImageReader? = null
            var jpegSize: Size? = null
            if (spec.jpeg) {
                val js = pickJpegSize(cm, cameraId)
                jpegSize = js
                val reader = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 2)
                jr = reader
                jpegReader = reader
                reader.setOnImageAvailableListener({ rd ->
                    try {
                        val image = rd.acquireLatestImage()
                        if (image != null) {
                            jpegBytes.set(image.planes[0].buffer.remaining())
                            image.close()
                        }
                    } catch (_: Exception) {}
                }, handler)
                val jpegOutput = OutputConfiguration(reader.surface)
                if (physicalId != null) jpegOutput.setPhysicalCameraId(physicalId)
                outputs.add(jpegOutput)
            }
            val stillReader: ImageReader? = jr
            CameraDiagnostics.log(
                "SESSION",
                "$label : createCaptureSession aperçu ${previewSize.width}x${previewSize.height}" +
                    (if (jpegSize != null) " + JPEG ${jpegSize.width}x${jpegSize.height}" else "")
            )
            try {
                d.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputs,
                        executor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (finished.get()) {
                                    s.close()
                                    return
                                }
                                session = s
                                CameraDiagnostics.log("SESSION", "$label : onConfigured après ${elapsedMs()} ms")
                                try {
                                    val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    builder.addTarget(pr.surface)
                                    builder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                    )
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
                                    }
                                    s.setRepeatingRequest(builder.build(), captureCallback, handler)
                                    if (stillReader != null) {
                                        handler.postDelayed({
                                            try {
                                                val still = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                                still.addTarget(stillReader.surface)
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                    still.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
                                                }
                                                s.capture(still.build(), captureCallback, handler)
                                            } catch (e: Exception) {
                                                CameraDiagnostics.log("SESSION_EXCEPTION", "$label : capture JPEG", e)
                                            }
                                        }, 1000)
                                    }
                                    handler.postDelayed({ finish("fin du test") }, if (stillReader != null) 3500 else 2000)
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

        handler.postDelayed({ finish("délai dépassé (10 s)") }, 10_000)
        CameraDiagnostics.log("SESSION", "$label : openCamera…")
        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (finished.get()) {
                        d.close()
                        return
                    }
                    device = d
                    CameraDiagnostics.log("SESSION", "$label : ouvert en ${elapsedMs()} ms")
                    try {
                        startSession(d)
                    } catch (e: Exception) {
                        CameraDiagnostics.log("SESSION_EXCEPTION", "$label : startSession", e)
                        finish("exception startSession")
                    }
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
