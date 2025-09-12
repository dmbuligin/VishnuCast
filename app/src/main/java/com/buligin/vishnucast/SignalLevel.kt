package com.buligin.vishnucast

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object SignalLevel {
    private val _live = MutableLiveData(0)
    val live: LiveData<Int> = _live

    fun post(level0to100: Int) {
        _live.postValue(level0to100.coerceIn(0, 100))
    }
}
