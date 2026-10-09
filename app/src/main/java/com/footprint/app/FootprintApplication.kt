package com.footprint.app

import android.app.Application
import com.footprint.app.data.FootprintDatabase
import com.footprint.app.data.TrackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FootprintApplication : Application() {
    val database by lazy { FootprintDatabase.getInstance(this) }
    val tracks by lazy { TrackRepository(database, applicationContext) }
    private val mutableLocationWarning = MutableStateFlow<String?>(null)
    val locationWarning = mutableLocationWarning.asStateFlow()
    private val mutableTrackFinishNotice = MutableStateFlow<String?>(null)
    val trackFinishNotice = mutableTrackFinishNotice.asStateFlow()

    @Volatile
    var hasCheckedUnfinishedTrackAtStartup: Boolean = false

    fun setLocationWarning(message: String?) {
        mutableLocationWarning.value = message
    }

    fun setTrackFinishNotice(message: String?) {
        mutableTrackFinishNotice.value = message
    }
}
