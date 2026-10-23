package org.fossify.camera

import androidx.camera.camera2.Camera2Config
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import org.fossify.camera.helpers.CameraDiagnostics
import org.fossify.camera.helpers.MainCameraResolver
import org.fossify.commons.FossifyApp

class CameraApp : FossifyApp(), CameraXConfig.Provider {

    override fun onCreate() {
        super.onCreate()
        CameraDiagnostics.init(this)
        CameraDiagnostics.dumpEnumeration(this)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    override fun getCameraXConfig(): CameraXConfig {
        val limiter = CameraSelector.Builder()
            .addCameraFilter(CameraFilter { infos ->
                MainCameraResolver.limitAvailableCameras(this, infos)
            })
            .build()
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(limiter)
            .build()
    }
}
