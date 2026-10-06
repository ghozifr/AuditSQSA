package com.suruhaja.data.policy

/**
 * Kebijakan order per tipe layanan.
 *
 * Template untuk mendukung multi-order di masa depan:
 * - "ride"  : maksimal 1 order aktif (ride-hailing, satu penumpang satu perjalanan)
 * - "food"  : maksimal 3 order aktif (pesan makanan, bisa paralel)
 * - "send"  : maksimal 5 order aktif (kirim barang/kurir, bisa banyak paralel)
 *
 * Saat ini baru "ride" yang dipakai. Tipe lain tinggal tambah di sini
 * tanpa perlu ubah logika di fragment/repository.
 */
object OrderPolicy {

    const val TYPE_RIDE = "ride"
    const val TYPE_FOOD = "food"
    const val TYPE_SEND = "send"

    /** Status yang dihitung sebagai order "aktif" (belum selesai). */
    val ACTIVE_STATUSES = setOf("pending", "accepted", "pickup")

    /**
     * Maksimal order AKTIF yang boleh dimiliki satu akun untuk suatu tipe layanan.
     * Return Int.MAX_VALUE jika tipe tidak dibatasi (bebas).
     */
    fun maxConcurrentOrders(serviceType: String): Int = when (serviceType.lowercase()) {
        TYPE_RIDE -> 1
        TYPE_FOOD -> 3
        TYPE_SEND -> 5
        else -> 1   // default: aman, satu-satu
    }

    /** Cek apakah user boleh membuat order baru untuk tipe ini. */
    fun canCreateOrder(serviceType: String, activeCount: Int): Boolean =
        activeCount < maxConcurrentOrders(serviceType)

    /** Shared server-mirrored distance fare: Rp8.000 through 5 km, then Rp1.600/km. */
    fun ridePrice(distanceKm: Double): Long = when {
        distanceKm <= 5.0 -> 8_000L
        else -> 8_000L + ((distanceKm - 5.0) * 1_600).toLong()
    }

    /** Food changes only its delivery fee; menu/addon totals and admin fee remain server-owned. */
    fun foodDeliveryFee(distanceKm: Double): Long = ridePrice(distanceKm)

    /** Send uses the same distance fare plus the unchanged +Rp1.000/kg over 5 kg. */
    fun sendPrice(distanceKm: Double, weightKg: Int): Long {
        var price = ridePrice(distanceKm)
        if (weightKg > 5) price += (weightKg - 5) * 1_000L
        return price
    }
}
