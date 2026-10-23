package org.fossify.camera.helpers

import androidx.camera.core.ZoomState

class ZoomCalculator {

    fun calculateZoomRatio(zoomState: ZoomState, pinchToZoomScale: Float): Float {
        val clampedRatio = zoomState.zoomRatio * speedUpZoomBy2X(pinchToZoomScale)
        // Clamp the ratio with the zoom range.
        // Jamais sous 1x : sur une caméra logique, un ratio < 1 bascule vers l'ultra grand-angle.
        return clampedRatio.coerceAtLeast(maxOf(zoomState.minZoomRatio, 1f))
            .coerceAtMost(zoomState.maxZoomRatio)
    }

    private fun speedUpZoomBy2X(scaleFactor: Float): Float {
        return if (scaleFactor > 1f) {
            1.0f + (scaleFactor - 1.0f) * 2
        } else {
            1.0f - (1.0f - scaleFactor) * 2
        }
    }
}
