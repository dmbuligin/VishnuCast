package com.buligin.vishnucast.player.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager

/**
 * Небольшой helper для показа системного запроса MediaProjection
 * и хранения результата (resultCode/resultData).
 *
 * Используется из PlayerUiBinder (для показа диалога) и PlayerSystemCapture (для старта захвата).
 */
object PlayerCapture {
    @Volatile private var granted: Boolean = false

    @Volatile
    var resultCode: Int = Activity.RESULT_CANCELED
        private set

    @Volatile
    var resultData: Intent? = null
        private set

    fun isGranted(): Boolean = granted

    /** Создаёт Intent для системного диалога MediaProjection. */
    fun createRequestIntent(activity: Activity): Intent {
        val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mpm.createScreenCaptureIntent()
    }

    /** Вызывается из ActivityResultLauncher по итогам диалога. */
    fun onProjectionResult(code: Int, data: Intent?) {
        resultCode = code
        resultData = data
        granted = (code == Activity.RESULT_OK && data != null)
    }
}
