package com.velocimetro.nativeapp.tracking

import com.velocimetro.nativeapp.core.TrackingSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Canal en memoria entre el servicio y la interfaz; la fuente durable es SQLite. */
object TrackingStore {
    private val mutableSnapshot = MutableStateFlow(TrackingSnapshot())
    val snapshot: StateFlow<TrackingSnapshot> = mutableSnapshot

    fun publish(value: TrackingSnapshot) {
        mutableSnapshot.value = value
    }
}
