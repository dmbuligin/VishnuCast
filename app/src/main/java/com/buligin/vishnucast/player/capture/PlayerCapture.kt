package com.buligin.vishnucast.player.capture

import android.content.Intent

object PlayerCapture {
    @Volatile var resultCode: Int = Int.MIN_VALUE
    @Volatile var resultData: Intent? = null

    fun set(resultCode_: Int, resultData_: Intent?) {
        resultCode = resultCode_
        resultData = resultData_
    }

    fun isGranted(): Boolean = (resultCode != Int.MIN_VALUE) && (resultData != null)
}
