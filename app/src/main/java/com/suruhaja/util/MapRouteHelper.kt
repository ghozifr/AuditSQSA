package com.suruhaja.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.util.Log
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import com.suruhaja.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * Reusable Directions API helper — fetch + decode + draw route polyline.
 * Used by OrderTrackingFragment (customer) and shared across map screens.
 */
object MapRouteHelper {

    private const val TAG = "MapRouteHelper"

    fun getApiKey(context: Context): String {
        return try {
            val ai = context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA
            )
            ai.metaData.getString("com.google.android.geo.API_KEY") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "API key not found", e)
            ""
        }
    }

    /** Fetch decoded route points (null on failure). */
    suspend fun fetchRoutePoints(
        context: Context, origin: LatLng, destination: LatLng
    ): List<LatLng>? {
        val key = getApiKey(context)
        if (key.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://maps.googleapis.com/maps/api/directions/json?" +
                    "origin=${origin.latitude},${origin.longitude}" +
                    "&destination=${destination.latitude},${destination.longitude}" +
                    "&mode=driving&key=$key"
                val jsonStr = URL(url).readText()
                val json = JSONObject(jsonStr)
                if (json.getString("status") != "OK") {
                    Log.w(TAG, "Directions status: ${json.getString("status")}")
                    return@withContext null
                }
                val route = json.getJSONArray("routes").getJSONObject(0)
                val points = route.getJSONObject("overview_polyline").getString("points")
                decodePolyline(points)
            } catch (e: Exception) {
                Log.e(TAG, "fetchRoutePoints", e)
                null
            }
        }
    }

    /** Draw route polyline on the map. Returns null on empty points. */
    fun drawRoute(map: GoogleMap, points: List<LatLng>, colorHex: String? = null, context: android.content.Context? = null): com.google.android.gms.maps.model.Polyline? {
        if (points.isEmpty()) return null
        val color = if (colorHex != null) Color.parseColor(colorHex) 
                    else if (context != null) androidx.core.content.ContextCompat.getColor(context, R.color.brand)
                    else Color.parseColor("#14B8A6")
        
        return map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(8f)
                .color(color)
                .geodesic(false)
        )
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            poly.add(LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5))
        }
        return poly
    }
}
