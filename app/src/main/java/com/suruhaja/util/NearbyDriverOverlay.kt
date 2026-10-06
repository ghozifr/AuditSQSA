package com.suruhaja.util

import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.suruhaja.R
import com.suruhaja.data.repository.OrderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Overlay marker driver terdekat (realtime) di peta customer.
 *
 * - Realtime: Flow<NearbyDriver> dari snapshot listener Firestore (update otomatis
 *   tiap driver online berubah lokasi / status).
 * - Radius difilter client-side dari `center` (lokasi customer) — konsisten dgn pola 30 km.
 * - Mengelola add/remove marker agar tidak dobel antar emit.
 */
class NearbyDriverOverlay(
    private val scope: CoroutineScope,
    private val map: GoogleMap,
    driversFlow: Flow<List<OrderRepository.NearbyDriver>>,
    private val radiusKm: Double = 30.0
) {
    private val markers = mutableMapOf<String, Marker>()
    private val center = MutableStateFlow<LatLng?>(null)

    init {
        scope.launch {
            combine(driversFlow, center) { drivers, c -> drivers to c }
                .collect { (drivers, c) -> if (c != null) render(drivers, c) }
        }
    }

    /** Update titik referensi (lokasi customer). Panggil setiap lokasi customer berubah. */
    fun setCenter(latLng: LatLng?) {
        center.value = latLng
    }

    fun clear() {
        markers.values.forEach { it.remove() }
        markers.clear()
    }

    private fun render(drivers: List<OrderRepository.NearbyDriver>, c: LatLng) {
        val seen = mutableSetOf<String>()
        for (d in drivers) {
            if (distanceKm(c.latitude, c.longitude, d.lat, d.lng) > radiusKm) continue
            seen += d.id
            val existing = markers[d.id]
            if (existing != null) {
                // Geser marker yang sudah ada (tanpa remove+add → tidak kedip).
                existing.position = LatLng(d.lat, d.lng)
                existing.title = "Driver: ${d.name}"
            } else {
                markers[d.id] = map.addMarker(
                    MarkerOptions().position(LatLng(d.lat, d.lng))
                        .title("Driver: ${d.name}")
                        .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_driver_marker))
                ) ?: continue
            }
        }
        // Hapus marker driver yang sudah keluar radius / offline.
        (markers.keys - seen).forEach { markers.remove(it)?.remove() }
    }

    private fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, r)
        return r[0] / 1000.0
    }
}
