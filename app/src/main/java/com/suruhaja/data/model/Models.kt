package com.suruhaja.data.model

data class User(
    val uid: String = "",
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    /** Ditulis server saat kode OTP email berhasil diverifikasi. */
    val emailVerified: Boolean = false,
    val photoUrl: String = "",
    val balance: Long = 0L,
    val points: Long = 0L,
    val createdAt: Long = 0L
)

data class Order(
    val id: String = "",
    val userId: String = "",
    val customerName: String = "",
    val customerPhone: String = "",
    val driverId: String = "",
    @JvmField val driverLat: Double = 0.0,
    @JvmField val driverLng: Double = 0.0,
    val serviceType: String = "ride",
    val paymentMethod: String = "cash",
    val pickup: String = "",
    @JvmField val pickupLat: Double = 0.0,
    @JvmField val pickupLng: Double = 0.0,
    val destination: String = "",
    @JvmField val destLat: Double = 0.0,
    @JvmField val destLng: Double = 0.0,
    @JvmField val distanceKm: Double = 0.0,
    @JvmField val durationMin: Int = 0,
    @JvmField val price: Long = 0L,
    @JvmField val status: String = "pending",
    @JvmField val driverRating: Int = 0,
    // ── SuruhSend (serviceType = "send") ──
    val itemName: String = "",
    val itemDesc: String = "",
    @JvmField val weightKg: Int = 1,
    val receiverName: String = "",
    val receiverPhone: String = "",
    val senderName: String = "",
    val senderPhone: String = "",
    val vehicle: String = "motor",
    // ── Voucher (di-snapshot server saat order dibuat) ──
    val voucherCode: String = "",
    @JvmField val voucherDiscount: Long = 0L,
    @JvmField val createdAt: Long = System.currentTimeMillis(),
    @JvmField val updatedAt: Long = System.currentTimeMillis()
)

data class Topup(
    val id: String = "",
    val userId: String = "",
    val role: String = "customer",
    val baseAmount: Long = 0L,
    val amount: Long = 0L,
    val status: String = "pending",
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
    val eventId: String = "",
    val source: String = ""
)
