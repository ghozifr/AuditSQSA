package com.suruhaja.data.policy

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.suruhaja.data.relay.RelayClient

/**
 * Radius layanan customer (customer ↔ toko, customer ↔ driver) — SATU sumber nilai.
 *
 * Nilainya hidup di Firestore `config/discovery { serviceRadiusKm }`, jadi mengubah
 * radius (mis. 30 km -> 15 km) TIDAK perlu update aplikasi: cukup ubah satu field.
 *
 * Kalau dokumen/field belum ada atau nilainya tidak masuk akal, dipakai
 * [DEFAULT_RADIUS_KM] = 30 km — jadi perilaku lama tidak berubah sampai diisi.
 */
object ServiceRadiusPolicy {

    const val DEFAULT_RADIUS_KM = 30.0
    private const val MIN_RADIUS_KM = 1.0
    private const val MAX_RADIUS_KM = 100.0

    @Volatile
    private var cachedKm: Double = DEFAULT_RADIUS_KM

    /** Nilai radius yang dipakai semua layar saat ini. */
    fun currentKm(): Double = cachedKm

    /** "30" atau "12,5" untuk teks ke pengguna. */
    fun labelKm(): String = if (cachedKm % 1.0 == 0.0) cachedKm.toInt().toString()
    else cachedKm.toString().replace('.', ',')

    /** Nilai tidak wajar (0, negatif, teks, di luar 1..100) -> default 30 km. */
    fun normalize(raw: Any?): Double {
        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        } ?: return DEFAULT_RADIUS_KM
        if (!value.isFinite() || value < MIN_RADIUS_KM || value > MAX_RADIUS_KM) return DEFAULT_RADIUS_KM
        return value
    }

    fun update(raw: Any?) {
        cachedKm = normalize(raw)
    }

    /**
     * Mulai mendengarkan config. Aman dipanggil berkali-kali (dipanggil dari
     * pemeriksaan versi saat app dibuka). Kegagalan baca tidak mengubah apa pun
     * — nilai tetap default/terakhir.
     */
    fun observe(firestore: FirebaseFirestore = FirebaseFirestore.getInstance()): ListenerRegistration =
        firestore.collection("config").document("discovery")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    // Google tak terjangkau (mis. operator by.U) → ambil radius dari relay.
                    // Pembacaan relay murah & di-cache 60 dtk, jadi layak dicoba langsung.
                    if (RelayClient.isNetworkError(err)) {
                        RelayClient.catatGagalJaringan()
                        ambilDariRelay()
                    }
                    return@addSnapshotListener
                }
                RelayClient.catatSuksesLangsung()
                update(snap?.get("serviceRadiusKm"))
            }

    /** Jalur cadangan: nilai radius diambil dari relay (laptop rumah), bukan dari Google. */
    private fun ambilDariRelay() {
        Thread {
            try {
                val km = RelayClient.config()
                    ?.optJSONObject("discovery")
                    ?.optJSONObject("data")
                    ?.opt("serviceRadiusKm")
                if (km != null) update(km)
            } catch (_: Exception) {
                // diamkan: nilai terakhir/default tetap dipakai
            }
        }.start()
    }
}
