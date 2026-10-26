package org.fossify.camera.helpers

import android.util.Log

/**
 * Journalisation optionnelle (Logcat uniquement). Mettre [ENABLED] à true pour diagnostiquer :
 * adb logcat -s FossifyCamDiag
 */
object CameraDiagnostics {
    const val TAG = "FossifyCamDiag"
    const val ENABLED = false

    fun log(stage: String, message: String, error: Throwable? = null) {
        if (!ENABLED) return
        if (error != null) Log.e(TAG, "[$stage] $message", error) else Log.d(TAG, "[$stage] $message")
    }
}
