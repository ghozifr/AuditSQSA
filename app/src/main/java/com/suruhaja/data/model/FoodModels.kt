package com.suruhaja.data.model

/**
 * Model SuruhFood (pesan makanan). Order disimpan di koleksi `merchant_orders`
 * (koleksi yang sama dengan app toko), status awal `seeking_driver`.
 */
data class Merchant(
    val id: String = "",
    val storeName: String = "",
    val address: String = "",
    @JvmField val latitude: Double = 0.0,
    @JvmField val longitude: Double = 0.0,
    val isOnline: Boolean = false,
    @JvmField val rating: Double = 0.0,      // agregat rating toko
    @JvmField val ratingCount: Long = 0L,
    @JvmField val orderCount: Long = 0L,      // jumlah order (untuk "menu terbaik"/terlaris)
    val imageUrl: String = "",                 // cover foto toko
    val photos: List<String> = emptyList(),    // gallery foto tempat/toko
    /** Snapshot maksimal 5 menu unggulan; ditulis server agar list toko bebas N+1 query. */
    val topMenuPreview: List<MenuItem> = emptyList()
)

data class MenuAddon(
    val id: String = "",
    val name: String = "",
    @JvmField val price: Long = 0L,
    val available: Boolean = true
)

data class FoodAddon(
    val id: String = "",
    val name: String = "",
    @JvmField val price: Long = 0L,
    @JvmField val qty: Int = 0
)

data class MenuItem(
    val id: String = "",
    val merchantId: String = "",
    val name: String = "",
    val category: String = "",
    @JvmField val price: Long = 0L,
    val description: String = "",
    val available: Boolean = true,
    val imageUrl: String = "",
    val addons: List<MenuAddon> = emptyList(),
    /** Jumlah porsi pada order DELIVERED; server-owned, bukan jumlah order dibuat. */
    @JvmField val soldCount: Long = 0L,
    /** Agregat rating item dari review order food. */
    @JvmField val rating: Double = 0.0,
    @JvmField val ratingCount: Long = 0L,
    /** Pilihan toko; tetap kalah dari availability dan diurutkan oleh featuredRank. */
    val featured: Boolean = false,
    @JvmField val featuredRank: Long = 0L
)

data class FoodItem(
    val menuItemId: String = "",
    val name: String = "",
    @JvmField val qty: Int = 0,
    @JvmField val price: Long = 0L,
    val addons: List<FoodAddon> = emptyList()
)

data class FoodOrder(
    val id: String = "",
    val merchantId: String = "",
    val merchantName: String = "",
    @JvmField val merchantLat: Double = 0.0,
    @JvmField val merchantLng: Double = 0.0,
    val customerId: String = "",
    val customerName: String = "",
    val customerPhone: String = "",
    val customerAddress: String = "",
    @JvmField val customerLat: Double = 0.0,
    @JvmField val customerLng: Double = 0.0,
    val driverId: String = "",
    val driverName: String = "",
    val driverPhone: String = "",
    @JvmField val driverLat: Double = 0.0,   // posisi live driver (saat delivering)
    @JvmField val driverLng: Double = 0.0,
    val items: List<FoodItem> = emptyList(),
    @JvmField val total: Long = 0L,          // total harga makanan/minuman (ke toko, tanpa fee)
    @JvmField val distanceKm: Double = 0.0,   // jarak customer → toko
    @JvmField val deliveryFee: Long = 0L,     // ongkir = max(7000, distanceKm * 1200)
    @JvmField val adminFee: Long = 0L,        // snapshot biaya layanan server (Rp2.000 untuk order baru)
    val paymentMethod: String = "cash",       // "cash" | "saldo"
    val paymentStatus: String = "unpaid",     // "unpaid" | "paid"
    val paidBy: String = "",                  // "" | "saldo"
    val awaitingPayment: Boolean = false,
    val paymentRequest: String = "",          // "" | "qris" (customer pilih bayar via QRIS)
    val rated: Boolean = false,               // customer sudah kasih rating?
    @JvmField val status: String = STATUS_SEEKING_DRIVER,
    val notes: String = "",
    val cancelledBy: String = "",             // "" | "merchant" | "customer" | "driver"
    val cancelReason: String = "",            // alasan pembatalan (mis. "Toko tutup")
    val cancelRequestedBy: String = "",       // "" | "customer" | "driver" (mutual cancel)
    @JvmField val unreadByCustomer: Long = 0L, // pesan driver yang belum dibaca customer
    // ── Voucher (di-snapshot server saat order dibuat) ──
    val voucherCode: String = "",
    @JvmField val voucherDiscount: Long = 0L,
    @JvmField val createdAt: Long = 0L,
    @JvmField val updatedAt: Long = 0L
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_SEEKING_DRIVER = "seeking_driver"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_PREPARING = "preparing"
        const val STATUS_READY = "ready"
        const val STATUS_DELIVERING = "delivering"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_CANCELLED = "cancelled"

        fun statusText(status: String): String = when (status) {
            STATUS_PENDING -> "Mencari Driver..." // kompatibilitas order lama
            STATUS_SEEKING_DRIVER -> "Mencari Driver..."
            STATUS_ACCEPTED -> "Driver ditemukan, menunggu konfirmasi toko"
            STATUS_PREPARING -> "Pesanan dimasak"
            STATUS_READY -> "Pesanan siap diambil driver"
            STATUS_DELIVERING -> "Driver mengantar pesanan"
            STATUS_DELIVERED -> "Selesai"
            STATUS_CANCELLED -> "Dibatalkan"
            else -> status
        }
    }
}
