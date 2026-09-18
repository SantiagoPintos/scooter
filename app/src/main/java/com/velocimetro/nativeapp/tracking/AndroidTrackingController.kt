package com.velocimetro.nativeapp.tracking

import android.content.Context
import androidx.core.content.ContextCompat
import com.velocimetro.nativeapp.domain.repository.TrackingController

/** Android service adapter for the domain tracking lifecycle contract. */
class AndroidTrackingController(context: Context) : TrackingController {
    private val appContext = context.applicationContext

    override fun start() {
        ContextCompat.startForegroundService(appContext, LocationTrackingService.startIntent(appContext))
    }

    override fun stop() {
        appContext.startService(LocationTrackingService.stopIntent(appContext))
    }
}
