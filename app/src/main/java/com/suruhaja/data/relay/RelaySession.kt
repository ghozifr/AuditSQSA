package com.suruhaja.data.relay

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * Menyambungkan sesi relay ke Firebase Auth SUNGGUHAN.
 *
 * Kenapa perlu: relay memverifikasi password lewat Identity Toolkit lalu menerbitkan
 * token sendiri — sesi itu tidak dikenal SDK Firebase di perangkat. Padahal aturan
 * Firestore hampir semuanya `signedIn()`, jadi tanpa langkah ini pengguna yang login
 * lewat relay akan "masuk" TAPI seluruh data Firestore (pesanan, riwayat, chat) kosong.
 *
 * Relay hanya mengirim `customToken` SETELAH password diverifikasi Google, dan
 * `signInWithCustomToken` menghasilkan sesi Firebase yang sah — sama seperti login biasa.
 * Kalau langkah ini gagal, login tidak digagalkan: sesi relay tetap dipakai (mode terbatas).
 */
object RelaySession {

    private const val TAG = "RelaySession"

    /** Return true kalau sesi Firebase sungguhan berhasil dibuat. */
    suspend fun sambungkanKeFirebase(customToken: String?): Boolean {
        if (customToken.isNullOrBlank()) return false
        return try {
            FirebaseAuth.getInstance().signInWithCustomToken(customToken).await()
            Log.i(TAG, "sesi Firebase dibuat dari custom token relay")
            true
        } catch (e: Exception) {
            Log.w(TAG, "gagal membuat sesi Firebase dari relay: ${e.message}")
            false
        }
    }
}
