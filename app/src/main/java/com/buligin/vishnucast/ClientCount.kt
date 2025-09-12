package com.buligin.vishnucast

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object ClientCount {
    private val _live = MutableLiveData(0)
    val live: LiveData<Int> = _live

    fun post(count: Int) {
        _live.postValue(if (count < 0) 0 else count)
    }

    fun reset() = post(0)
}
