package org.siloserver.silo.common.player

import android.util.Log

/**
 * Temporary tracing for the subtitle transaction flow.
 *
 * Every call site is a single line so this can be removed mechanically without
 * touching surrounding code. Remove once subtitle selection is confirmed
 * working on device.
 */
object SubDiag {
    private const val TAG = "SUBDIAG"

    fun log(message: String) {
        Log.e(TAG, message)
    }

    fun trace(message: String) {
        Log.e(TAG, message, Throwable("caller"))
    }
}
