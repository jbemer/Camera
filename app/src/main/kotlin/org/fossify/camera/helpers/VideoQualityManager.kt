package org.fossify.camera.helpers

import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import org.fossify.camera.extensions.config
import org.fossify.camera.extensions.toVideoQuality
import org.fossify.camera.models.CameraSelectorVideoQualities
import org.fossify.camera.models.VideoQuality
import org.fossify.commons.extensions.showErrorToast

class VideoQualityManager(
    private val activity: AppCompatActivity,
) {
    companion object {
        private val QUALITIES = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
        private val CAMERA_SELECTORS =
            arrayOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
    }

    private val videoQualities = mutableListOf<CameraSelectorVideoQualities>()
    private val mediaSizeStore = MediaSizeStore(activity.config)

    fun initSupportedQualities(cameraProvider: ProcessCameraProvider) {
        if (videoQualities.isEmpty()) {
            for (camSelector in CAMERA_SELECTORS) {
                try {
                    cameraProvider.unbindAll()
                    // La clé reste DEFAULT_*_CAMERA ; pour l'arrière on ne lie que le capteur principal.
                    val bindSelector = if (camSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                        MainCameraResolver.backSelector(activity) ?: camSelector
                    } else {
                        camSelector
                    }
                    if (cameraProvider.hasCamera(bindSelector)) {
                        val camera = cameraProvider.bindToLifecycle(activity, bindSelector)
                        QualitySelector.getSupportedQualities(camera.cameraInfo)
                            .filter(QUALITIES::contains)
                            .also { allQualities ->
                                val qualities = allQualities.map { it.toVideoQuality() }
                                videoQualities.add(
                                    CameraSelectorVideoQualities(
                                        camSelector,
                                        qualities
                                    )
                                )
                            }
                    }
                } catch (e: Exception) {
                    CameraDiagnostics.log("QUALITIES_FAIL", "qualités vidéo ($camSelector) : ${e.javaClass.simpleName}", e)
                    activity.showErrorToast(e)
                }
            }
        }
    }

    fun getUserSelectedQuality(cameraSelector: CameraSelector): VideoQuality {
        val isFrontCamera = cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
        val selectionIndex = mediaSizeStore.getCurrentSizeIndex(
            isPhotoCapture = false,
            isFrontCamera = isFrontCamera
        ).coerceAtLeast(0)
        return getSupportedQualities(cameraSelector).getOrElse(selectionIndex) { VideoQuality.HD }
    }

    fun getSupportedQualities(cameraSelector: CameraSelector): List<VideoQuality> {
        return videoQualities.filter { it.camSelector == cameraSelector }
            .flatMap { it.qualities }
            .sortedByDescending { it.pixels }
    }
}
