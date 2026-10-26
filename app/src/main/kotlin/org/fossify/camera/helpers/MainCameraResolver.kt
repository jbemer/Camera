package org.fossify.camera.helpers

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import kotlin.math.abs
import kotlin.math.hypot

data class MainCameraChoice(
    val cameraId: String,
    /** true si l'ID est une caméra logique (fusion de plusieurs capteurs). */
    val isLogicalMultiCamera: Boolean,
    /** Capteur physique principal derrière la caméra logique, à demander seul au pilote. */
    val physicalId: String?,
    /** Tous les ID dorsaux vus lors de l'énumération. */
    val allBackIds: Set<String>,
)

/**
 * Choisit la caméra dorsale principale (grand-angle standard, zoom 1x) par l'équivalent 35 mm de sa
 * focale. Si c'est une caméra logique, repère aussi le capteur physique principal (même focale et
 * même taille de capteur que la caméra logique) pour ne jamais démarrer l'ultra grand-angle.
 */
object MainCameraResolver {
    private const val TARGET_EQUIV_MM = 24f
    private const val MIN_MAIN_EQUIV_MM = 19f
    private const val MAX_MAIN_EQUIV_MM = 32f
    private const val FULL_FRAME_DIAGONAL_MM = 43.267f

    /** Pour forcer un ID de caméra dorsale (ex. "0"). null = détection automatique. */
    val FORCED_BACK_CAMERA_ID: String? = null

    @Volatile
    private var resolved = false

    @Volatile
    private var cached: MainCameraChoice? = null

    @Volatile
    private var cachedSelector: CameraSelector? = null

    fun equivalentFocalLengthMm(c: CameraCharacteristics): Float? {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            ?: return null
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diagonal = hypot(size.width, size.height)
        if (diagonal <= 0f) return null
        return focal * FULL_FRAME_DIAGONAL_MM / diagonal
    }

    fun resolve(context: Context): MainCameraChoice? {
        if (resolved) return cached
        synchronized(this) {
            if (!resolved) {
                cached = try {
                    compute(context.applicationContext)
                } catch (e: Exception) {
                    CameraDiagnostics.log("RESOLVE_FAIL", "résolution impossible", e)
                    null
                }
                resolved = true
            }
            return cached
        }
    }

    /** ID du capteur physique à imposer à tous les flux, ou null (comportement CameraX d'origine). */
    fun physicalCameraId(context: Context): String? = resolve(context)?.physicalId

    private class Candidate(
        val id: String,
        val equiv: Float?,
        val logical: Boolean,
        val backwardCompatible: Boolean,
    )

    private fun compute(context: Context): MainCameraChoice? {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try {
            cm.cameraIdList
        } catch (e: Exception) {
            CameraDiagnostics.log("ENUM_FAIL", "getCameraIdList() a échoué", e)
            return null
        }

        val candidates = ArrayList<Candidate>()
        for (id in ids) {
            try {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                    continue
                }
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                candidates.add(
                    Candidate(
                        id = id,
                        equiv = equivalentFocalLengthMm(c),
                        logical = caps?.contains(
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                        ) == true,
                        backwardCompatible = caps?.contains(
                            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
                        ) ?: true,
                    )
                )
            } catch (e: Exception) {
                CameraDiagnostics.log("RESOLVE_SKIP", "id=$id illisible", e)
            }
        }
        val allBack = candidates.map { it.id }.toSet()

        val forced = FORCED_BACK_CAMERA_ID
        val pick: Candidate? = if (forced != null) {
            candidates.firstOrNull { it.id == forced }
        } else {
            val usable = candidates.filter { it.backwardCompatible }
            val inRange = usable.filter { it.equiv != null && it.equiv in MIN_MAIN_EQUIV_MM..MAX_MAIN_EQUIV_MM }
            if (inRange.isNotEmpty()) {
                inRange.sortedWith(
                    compareBy<Candidate>({ Math.round(abs(it.equiv!! - TARGET_EQUIV_MM) * 2f) }, { it.logical })
                        .thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
                ).first()
            } else {
                usable.minByOrNull { it.id.toIntOrNull() ?: Int.MAX_VALUE }
            }
        }
        if (pick == null) {
            CameraDiagnostics.log("RESOLVE", "aucune caméra dorsale utilisable")
            return null
        }

        val physical = if (pick.logical) findMainPhysicalId(cm, pick.id) else null
        CameraDiagnostics.log(
            "RESOLVE_OK",
            "principal=id ${pick.id} (logique=${pick.logical}), capteur physique=$physical, dorsales=$allBack"
        )
        return MainCameraChoice(pick.id, pick.logical, physical, allBack)
    }

    /**
     * Capteur physique de la caméra logique dont la focale et la taille de capteur sont celles de la
     * caméra logique elle-même : c'est le capteur principal.
     */
    private fun findMainPhysicalId(cm: CameraManager, logicalId: String): String? {
        return try {
            val logical = cm.getCameraCharacteristics(logicalId)
            val focal = logical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                ?: return null
            val sensor = logical.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            logical.physicalCameraIds.sorted().firstOrNull { physicalId ->
                try {
                    val pc = cm.getCameraCharacteristics(physicalId)
                    val f = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    val s = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    f != null && abs(f - focal) < 0.05f &&
                        (sensor == null || s == null ||
                            (abs(s.width - sensor.width) < 0.05f && abs(s.height - sensor.height) < 0.05f))
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            CameraDiagnostics.log("RESOLVE_FAIL", "capteur physique introuvable", e)
            null
        }
    }

    /** Sélecteur CameraX qui ne retient que la caméra dorsale principale. null => comportement d'origine. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun backSelector(context: Context): CameraSelector? {
        cachedSelector?.let { return it }
        val choice = resolve(context) ?: return null
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter(CameraFilter { infos ->
                val match = infos.filter { Camera2CameraInfo.from(it).cameraId == choice.cameraId }
                if (match.isEmpty()) infos else match
            })
            .build()
        cachedSelector = selector
        return selector
    }

    /**
     * « Available cameras limiter » de CameraX : les autres caméras dorsales (ultra grand-angle,
     * téléobjectif) ne lui sont pas déclarées. Les caméras avant sont conservées.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun limitAvailableCameras(context: Context, infos: List<CameraInfo>): List<CameraInfo> {
        try {
            val choice = resolve(context) ?: return infos
            val kept = infos.filter {
                it.lensFacing != CameraSelector.LENS_FACING_BACK ||
                    Camera2CameraInfo.from(it).cameraId == choice.cameraId
            }
            val hasMain = kept.any {
                it.lensFacing == CameraSelector.LENS_FACING_BACK &&
                    Camera2CameraInfo.from(it).cameraId == choice.cameraId
            }
            return if (hasMain) kept else infos
        } catch (e: Exception) {
            CameraDiagnostics.log("PROVIDER_FAIL", "limiteur en échec, aucune limitation appliquée", e)
            return infos
        }
    }
}
