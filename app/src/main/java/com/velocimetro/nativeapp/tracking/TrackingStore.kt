package com.velocimetro.nativeapp.tracking

import com.velocimetro.nativeapp.domain.model.TrackingSnapshot
import com.velocimetro.nativeapp.domain.repository.TrackingStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Canal en memoria entre el servicio y la interfaz; la fuente durable es SQLite. */
object TrackingStore : TrackingStateRepository {
    private val mutableSnapshot = MutableStateFlow(TrackingSnapshot())
    override val snapshot: StateFlow<TrackingSnapshot> = mutableSnapshot

    override fun publish(snapshot: TrackingSnapshot) {
        mutableSnapshot.value = snapshot
    }
}
