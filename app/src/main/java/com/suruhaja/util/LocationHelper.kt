package com.suruhaja.util

import android.annotation.SuppressLint
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deteksi lokasi sekali-jalan yang andal:
 * 1) cache segar (< 3 menit),
 * 2) getCurrentLocation dengan PRIORITY_BALANCED_POWER_ACCURACY (network/Wi-Fi, cepat & andal),
 * 3) fallback requestLocationUpdates sampai dapat fix pertama.
 *
 * Semua kegagalan di-log dengan tag "LocationHelper" supaya mudah dilacak via logcat.
 */
object LocationHelper {
    /** Cache dianggap masih segar (langsung dipakai). */
    const val MAX_AGE_MS = 3 * 60 * 1000L

    /**
     * Batas mutlak untuk cache lama yang dipakai sebagai fallback terakhir.
     * Lebih tua dari ini → diabaikan (return null).
     *
     * Alasan: lokasi basi bisa beda KOTA — mis. terakhir terdeteksi di Aceh
     * padahal customer sudah di Madura. Kalau dipakai, toko kota lain lolos
     * filter radius 30 km dan muncul di kota yang salah.
     */
    const val MAX_FALLBACK_AGE_MS = 10 * 60 * 1000L

    const val TIMEOUT_MS = 12_000L
    private const val TAG = "LocationHelper"

    @SuppressLint("MissingPermission")
    suspend fun fetch(client: FusedLocationProviderClient?, forceFresh: Boolean = false): Location? {
        val c = client ?: run {
            Log.e(TAG, "fusedLocationClient null")
            return null
        }
        return try {
            // 1) cache
            val last = try {
                c.lastLocation.await()
            } catch (e: Exception) {
                Log.w(TAG, "lastLocation gagal: ${e.message}", e)
                null
            }
            Log.d(TAG, "lastLocation=${last?.let { "%.6f,%.6f age=${System.currentTimeMillis() - it.time}ms".format(it.latitude, it.longitude) } ?: "null"}")

            val lastFresh = last != null && (System.currentTimeMillis() - last.time) <= MAX_AGE_MS
            if (!forceFresh && lastFresh) {
                Log.d(TAG, "pakai cache segar")
                return last
            }

            // 2) one-shot
            val oneShot = try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    c.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
                }
            } catch (e: ApiException) {
                Log.w(TAG, "getCurrentLocation ApiException statusCode=${e.statusCode} msg=${e.message}", e)
                null
            } catch (e: Exception) {
                Log.w(TAG, "getCurrentLocation gagal: ${e.message}", e)
                null
            }
            Log.d(TAG, "oneShot=${oneShot?.let { "%.6f,%.6f".format(it.latitude, it.longitude) } ?: "null"}")
            if (oneShot != null) return oneShot

            // 3) fallback requestLocationUpdates
            Log.w(TAG, "one-shot gagal/timeout → fallback requestLocationUpdates")
            val ageMs = last?.let { System.currentTimeMillis() - it.time }
            val usableFallback = last?.takeIf { (ageMs ?: Long.MAX_VALUE) <= MAX_FALLBACK_AGE_MS }
            if (last != null && usableFallback == null) {
                Log.w(TAG, "cache terlalu basi (${ageMs}ms) → diabaikan supaya tidak salah kota")
            }
            requestUpdates(c, usableFallback)
        } catch (e: Exception) {
            Log.e(TAG, "fetch gagal total: ${e.message}", e)
            null
        }
    }

    /**
     * Lokasi terakhir yang diketahui, TANPA menunggu fix GPS baru (instan).
     *
     * Dipakai supaya layar (mis. konfirmasi antar SuruhFood) bisa LANGSUNG
     * menampilkan posisi customer begitu dibuka, sementara [fetch] tetap
     * berjalan di belakang untuk memperbarui.
     *
     * Tetap dibatasi [MAX_FALLBACK_AGE_MS] supaya tidak menampilkan kota yang
     * salah (lihat alasan di MAX_FALLBACK_AGE_MS di atas).
     */
    @SuppressLint("MissingPermission")
    suspend fun peekLast(client: FusedLocationProviderClient?): Location? {
        val c = client ?: return null
        val last = try {
            c.lastLocation.await()
        } catch (e: Exception) {
            Log.w(TAG, "peekLast gagal: ${e.message}")
            null
        } ?: return null
        val age = System.currentTimeMillis() - last.time
        if (age > MAX_FALLBACK_AGE_MS) {
            Log.d(TAG, "peekLast: cache terlalu basi (${age}ms) → null")
            return null
        }
        Log.d(TAG, "peekLast=%.6f,%.6f age=%dms".format(last.latitude, last.longitude, age))
        return last
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestUpdates(
        client: FusedLocationProviderClient,
        fallback: Location?
    ): Location? {
        var callback: LocationCallback? = null
        return try {
            val deferred = CompletableDeferred<Location?>()
            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5000L)
                .setMaxUpdates(1)
                .build()
            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation
                    if (loc != null && !deferred.isCompleted) deferred.complete(loc)
                }
            }
            callback = cb
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
            val result = withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }
            Log.d(TAG, "requestUpdates=${result?.let { "%.6f,%.6f".format(it.latitude, it.longitude) } ?: "null"}")
            result ?: fallback
        } catch (e: Exception) {
            Log.w(TAG, "requestLocationUpdates gagal: ${e.message}", e)
            fallback
        } finally {
            callback?.let { client.removeLocationUpdates(it) }
        }
    }
}
