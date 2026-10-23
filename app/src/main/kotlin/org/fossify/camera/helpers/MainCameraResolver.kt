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
    val reason: String,
    /** true si l'ID est une caméra logique (fusion de plusieurs capteurs) : voir limites. */
    val isLogicalMultiCamera: Boolean,
    /** Tous les ID dorsaux vus lors de l'énumération. */
    val allBackIds: Set<String>,
)

/**
 * Choisit l'ID Camera2 du capteur dorsal principal (grand-angle standard, zoom 1x) à partir de
 * l'équivalent 35 mm de la focale (f × 43,27 / diagonale du capteur), sans ouvrir aucune caméra :
 * seules les CameraCharacteristics sont lues.
 *
 * Valeurs typiques : ultra grand-angle ~12-15 mm, principal ~22-28 mm, téléobjectif > 50 mm.
 * Ces bornes sont une heuristique ; le journal ENUM_ID permet de les vérifier sur l'appareil.
 */
object MainCameraResolver {
    private const val TARGET_EQUIV_MM = 24f
    private const val MIN_MAIN_EQUIV_MM = 19f
    private const val MAX_MAIN_EQUIV_MM = 32f
    private const val FULL_FRAME_DIAGONAL_MM = 43.267f

    /** Pour forcer un ID (ex. "0") après lecture du journal ENUM_ID. null = heuristique. */
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
            CameraDiagnostics.log("ENUM_FAIL", "getCameraIdList() a échoué (résolution)", e)
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
                CameraDiagnostics.log("RESOLVE_SKIP", "id=$id illisible : ${e.javaClass.simpleName}", e)
            }
        }
        val allBack = candidates.map { it.id }.toSet()
        CameraDiagnostics.log(
            "RESOLVE",
            "candidats dorsaux : " + candidates.joinToString {
                "id=${it.id}(equiv=${it.equiv?.let { v -> "%.1f".format(v) }}, logical=${it.logical}, compat=${it.backwardCompatible})"
            }
        )

        val forced = FORCED_BACK_CAMERA_ID
        if (forced != null) {
            val c = candidates.firstOrNull { it.id == forced }
            return if (c != null) {
                MainCameraChoice(c.id, "forcé par FORCED_BACK_CAMERA_ID", c.logical, allBack)
            } else {
                CameraDiagnostics.log("RESOLVE", "ID forcé $forced absent des caméras dorsales")
                null
            }
        }

        val usable = candidates.filter { it.backwardCompatible }
        val inRange = usable.filter { it.equiv != null && it.equiv in MIN_MAIN_EQUIV_MM..MAX_MAIN_EQUIV_MM }

        val pick: Candidate?
        val reason: String
        if (inRange.isNotEmpty()) {
            // plus proche de 24 mm ; à égalité on préfère une caméra non logique, puis l'ID le plus petit
            pick = inRange.sortedWith(
                compareBy<Candidate>({ Math.round(abs(it.equiv!! - TARGET_EQUIV_MM) * 2f) }, { it.logical })
                    .thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
            ).first()
            reason = "équivalent 35 mm ${"%.1f".format(pick.equiv)} mm, le plus proche de $TARGET_EQUIV_MM"
        } else {
            pick = usable.minByOrNull { it.id.toIntOrNull() ?: Int.MAX_VALUE }
            reason = "repli : aucune focale dans la plage grand-angle standard, plus petit ID dorsal"
        }
        if (pick == null) {
            CameraDiagnostics.log("RESOLVE", "aucune caméra dorsale utilisable")
            return null
        }
        if (pick.logical) {
            CameraDiagnostics.log(
                "RESOLVE_WARN",
                "id=${pick.id} est une caméra LOGIQUE : son ouverture peut alimenter d'autres capteurs " +
                    "(ultra grand-angle inclus). Le contournement côté app est alors limité."
            )
        }
        CameraDiagnostics.log("RESOLVE_OK", "capteur principal = id=${pick.id} ($reason)")
        return MainCameraChoice(pick.id, reason, pick.logical, allBack)
    }

    /** Sélecteur CameraX qui ne retient que le capteur dorsal principal. null => comportement d'origine. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun backSelector(context: Context): CameraSelector? {
        cachedSelector?.let { return it }
        val choice = resolve(context) ?: return null
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter(CameraFilter { infos ->
                val match = infos.filter { Camera2CameraInfo.from(it).cameraId == choice.cameraId }
                if (match.isEmpty()) {
                    CameraDiagnostics.log("BIND_WARN", "id=${choice.cameraId} absent de la liste CameraX, repli sur le comportement d'origine")
                    infos
                } else {
                    match
                }
            })
            .build()
        cachedSelector = selector
        return selector
    }

    /**
     * Utilisé comme « available cameras limiter » de CameraX : les caméras dorsales autres que le
     * capteur principal ne sont pas déclarées à CameraX (ni liées, ni listées).
     * Les caméras non dorsales (avant) sont conservées.
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
            CameraDiagnostics.log(
                "PROVIDER",
                "limiteur CameraX : ${infos.size} -> ${kept.size} caméras (principal conservé : $hasMain)"
            )
            return if (hasMain) kept else infos
        } catch (e: Exception) {
            CameraDiagnostics.log("PROVIDER_FAIL", "limiteur en échec, aucune limitation appliquée", e)
            return infos
        }
    }
}
