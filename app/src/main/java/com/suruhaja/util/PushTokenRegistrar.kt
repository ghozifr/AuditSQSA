package com.suruhaja.util

import com.suruhaja.data.relay.Fungsi
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Daftarkan FCM token customer ke backend.
 *
 * Server menyimpan token di koleksi `fcm_tokens` (server-only, ditulis via callable
 * `registerDeviceToken`). Tanpa ini, push dari admin tidak punya alamat tujuan.
 */
object PushTokenRegistrar {

    private const val TAG = "PushToken"
    private const val ROLE = "customer"

    /** Ambil token perangkat lalu daftarkan. Aman dipanggil berulang kali. */
    suspend fun registerCurrentToken(): Boolean {
        if (FirebaseAuth.getInstance().currentUser == null) return false
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            register(token)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal mengambil FCM token", e)
            false
        }
    }

    /** Dipakai juga oleh onNewToken (token dirotasi Firebase). */
    suspend fun register(token: String): Boolean {
        if (token.isBlank()) return false
        if (FirebaseAuth.getInstance().currentUser == null) return false
        return try {
            Fungsi.getInstance("asia-southeast2")
                .getHttpsCallable("registerDeviceToken")
                .call(mapOf("token" to token, "role" to ROLE))
                .await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "registerDeviceToken gagal", e)
            false
        }
    }
}
