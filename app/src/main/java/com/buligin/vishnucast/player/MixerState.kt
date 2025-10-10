package com.buligin.vishnucast.audio

import androidx.lifecycle.MutableLiveData

object MixerState {
    /** 0..1: 0=Mic, 1=Player */
    val alpha01 = MutableLiveData(0f)

}
