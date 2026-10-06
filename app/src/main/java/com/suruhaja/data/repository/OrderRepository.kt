package com.suruhaja.data.repository

import com.suruhaja.data.relay.Fungsi
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.suruhaja.data.relay.CallableFallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrderRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val ordersRef = firestore.collection("orders")

    data class OrderRequest(
        val userId: String = "",
        val customerName: String = "Ahmad Razif",
        val customerPhone: String = "",
        val serviceType: String = "ride",
        val paymentMethod: String = "cash",
        val pickup: String = "",
        val pickupLat: Double = 0.0,
        val pickupLng: Double = 0.0,
        val destination: String = "",
        val destLat: Double = 0.0,
        val destLng: Double = 0.0,
        val distanceKm: Double = 0.0,
        val durationMin: Int = 0,
        val price: Long = 0L,
        // ── SuruhSend ──
        val itemName: String = "",
        val itemDesc: String = "",
        val weightKg: Int = 1,
        val receiverName: String = "",
        val receiverPhone: String = "",
        val senderName: String = "",
        val senderPhone: String = "",
        val vehicle: String = "motor"
    )

    data class OrderQuote(
        val price: Long,
        val distanceKm: Double,
        val weightKg: Int,
        /** Kode voucher yang dipakai (kosong = tanpa voucher). */
        val voucherCode: String = "",
        val voucherTitle: String = "",
        /** Potongan yang dihitung SERVER untuk order ini (Rp). */
        val voucherDiscount: Long = 0L,
        val voucherMessage: String = ""
    )

    /** Read-only server quote. It creates no order/offer; createOrder rechecks it. */
    suspend fun quoteOrder(order: OrderRequest, voucherCode: String = ""): OrderQuote {
        val payload = mutableMapOf<String, Any?>(
            "serviceType" to order.serviceType,
            "pickupLat" to order.pickupLat,
            "pickupLng" to order.pickupLng,
            "destLat" to order.destLat,
            "destLng" to order.destLng,
            "distanceKm" to order.distanceKm,
            "weightKg" to order.weightKg,
            "appVersionCode" to com.suruhaja.BuildConfig.VERSION_CODE
        )
        if (voucherCode.isNotBlank()) payload["voucherCode"] = voucherCode

        // Jalur cadangan (relay) dipakai otomatis kalau cloudfunctions.net tidak
        // terjangkau dari perangkat ini (mis. operator by.U).
        val data = CallableFallback.call("quoteOrder", payload) as? Map<*, *>
            ?: error("Quote server tidak valid")
        val voucher = data["voucher"] as? Map<*, *>
        return OrderQuote(
            price = (data["price"] as? Number)?.toLong() ?: error("Harga quote tidak valid"),
            distanceKm = (data["distanceKm"] as? Number)?.toDouble() ?: error("Jarak quote tidak valid"),
            weightKg = (data["weightKg"] as? Number)?.toInt() ?: order.weightKg,
            voucherCode = (voucher?.get("code") as? String).orEmpty(),
            voucherTitle = (voucher?.get("title") as? String).orEmpty(),
            voucherDiscount = (voucher?.get("discount") as? Number)?.toLong() ?: 0L,
            voucherMessage = (voucher?.get("message") as? String).orEmpty()
        )
    }

    /** Customer creates a new order via Cloud Function (server-authoritative pricing). */
    suspend fun createOrder(order: OrderRequest, voucherCode: String = ""): String {
        val result = Fungsi.getInstance("asia-southeast2")
            .getHttpsCallable("createOrder")
            .call(
                mapOf(
                    "serviceType" to order.serviceType,
                    "paymentMethod" to order.paymentMethod,
                    "pickup" to order.pickup,
                    "pickupLat" to order.pickupLat,
                    "pickupLng" to order.pickupLng,
                    "destination" to order.destination,
                    "destLat" to order.destLat,
                    "destLng" to order.destLng,
                    "distanceKm" to order.distanceKm,
                    "durationMin" to order.durationMin,
                    "itemName" to order.itemName,
                    "itemDesc" to order.itemDesc,
                    "weightKg" to order.weightKg,
                    "receiverName" to order.receiverName,
                    "receiverPhone" to order.receiverPhone,
                    "senderName" to order.senderName,
                    "senderPhone" to order.senderPhone,
                    "vehicle" to order.vehicle,
                    "voucherCode" to voucherCode,
                    "appVersionCode" to com.suruhaja.BuildConfig.VERSION_CODE
                )
            ).await()
        return (result.getData() as? Map<*, *>)?.get("orderId") as? String ?: ""
    }

    /** Returns the active order ID (pending/accepted/pickup), or null. */
    suspend fun getActiveOrderId(userId: String): String? {
        if (userId.isEmpty()) return null
        return try {
            val snap = ordersRef.whereEqualTo("userId", userId).get().await()
            snap.documents.firstOrNull { doc ->
                val status = doc.getString("status") ?: ""
                status == "pending" || status == "accepted" || status == "pickup"
            }?.id
        } catch (e: Exception) { null }
    }

    suspend fun hasActiveOrder(userId: String): Boolean = getActiveOrderId(userId) != null

    /** Count active orders for a specific service type (for multi-order policy). */
    suspend fun countActiveOrders(userId: String, serviceType: String): Int {
        if (userId.isEmpty()) return 0
        return try {
            val snap = ordersRef.whereEqualTo("userId", userId).get().await()
            snap.documents.count { doc ->
                val status = doc.getString("status") ?: ""
                val type = doc.getString("serviceType") ?: ""
                status in setOf("pending", "accepted", "pickup") && type == serviceType
            }
        } catch (e: Exception) { 0 }
    }

    /** Driver online di sekitar customer (radius km) — filter jarak client-side (pola 30km). */
    data class NearbyDriver(val id: String, val name: String, val lat: Double, val lng: Double)

    /**
     * Realtime stream driver online (server-only, filter fresh `lastSeen` < 2 menit).
     * Emit ulang tiap ada driver online yang update lokasi/status. Radius difilter
     * oleh pemanggil (NearbyDriverOverlay) karena butuh center customer.
     */
    fun observeNearbyDrivers(): Flow<List<NearbyDriver>> = callbackFlow {
        val reg = firestore.collection("drivers")
            .whereEqualTo("isOnline", true)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                if (snap.metadata.isFromCache) return@addSnapshotListener // server-only, hindari stale
                val now = System.currentTimeMillis()
                val drivers = snap.documents.mapNotNull { doc ->
                    val lat = doc.getDouble("currentLat") ?: return@mapNotNull null
                    val lng = doc.getDouble("currentLng") ?: return@mapNotNull null
                    if (lat == 0.0 && lng == 0.0) return@mapNotNull null
                    // Hindari posisi stale (driver crash tanpa sempat offline).
                    val lastSeen = doc.getLong("lastSeen") ?: 0L
                    if (now - lastSeen > 120_000L) return@mapNotNull null
                    NearbyDriver(doc.id, doc.getString("name") ?: "Driver", lat, lng)
                }
                trySend(drivers)
            }
        awaitClose { reg.remove() }
    }

    private fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0] / 1000.0
    }
}
