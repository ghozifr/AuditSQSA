package com.suruhaja.data.repository

import com.suruhaja.data.relay.Fungsi
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.suruhaja.data.relay.CallableFallback
import com.suruhaja.data.model.FoodAddon
import com.suruhaja.data.model.FoodItem
import com.suruhaja.data.model.FoodOrder
import com.suruhaja.data.model.MenuAddon
import com.suruhaja.data.model.MenuItem
import com.suruhaja.data.model.Merchant
import com.suruhaja.data.model.MerchantReview
import com.suruhaja.data.model.ReviewLineItem
import com.suruhaja.data.model.ReviewedMenuItem
import com.suruhaja.data.policy.ReviewPrivacyPolicy
import com.suruhaja.util.longOr
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class FoodOrderStreamState(
    val order: FoodOrder? = null,
    val error: String? = null
)

/**
 * Hasil stream ulasan. `error` dipisahkan dari daftar kosong supaya kegagalan
 * query (mis. index komposit belum dibuat) tidak tampil sebagai "belum ada
 * ulasan" — dua kondisi itu butuh penanganan yang berbeda.
 */
data class ReviewStreamState(
    val reviews: List<MerchantReview> = emptyList(),
    val error: String? = null
)

/** Satu hasil pencarian menu dari index `searchKeywords`. */
data class MenuKeywordHit(val merchantId: String, val name: String)

/** Batas dokumen per token & maksimum token yang di-query sekaligus. */
private const val MENU_KEYWORD_LIMIT = 200L
private const val MENU_KEYWORD_MAX_TOKENS = 2
private const val MERCHANT_ID_BATCH_SIZE = 10

/** Batas maksimum `whereIn` Firestore sekaligus untuk dokumen id. */
private const val MENU_ID_BATCH_SIZE = 30

/** Jumlah ulasan terbaru yang dimuat di layar "Lihat Review". */
private const val REVIEW_PAGE_SIZE = 50L

@Singleton
class FoodRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val merchantsRef = firestore.collection("merchants")
    private val menuRef = firestore.collection("menu")
    private val ordersRef = firestore.collection("merchant_orders")
    private val reviewsRef = firestore.collection("reviews")

    /** Batas toko yang dipantau real-time (40 toko saat ini). */
    private val merchantLimit = 500L

    /** Daftar merchant real-time (server-only) — termasuk offline, untuk tampilan "tutup". */
    fun observeMerchants(): Flow<List<Merchant>> = callbackFlow {
        val listener = merchantsRef
            .limit(merchantLimit)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null) { trySend(emptyList()); return@addSnapshotListener }
                if (snap == null) { trySend(emptyList()); return@addSnapshotListener }
                if (snap.metadata.isFromCache) return@addSnapshotListener
                trySend(snap.documents.mapNotNull { doc ->
                    Merchant(
                        id = doc.id,
                        storeName = doc.getString("storeName") ?: "",
                        address = doc.getString("address") ?: "",
                        latitude = doc.getDouble("latitude") ?: 0.0,
                        longitude = doc.getDouble("longitude") ?: 0.0,
                        isOnline = doc.getBoolean("isOnline") ?: false,
                        rating = doc.getDouble("rating") ?: 0.0,
                        ratingCount = doc.getLong("ratingCount") ?: 0L,
                        orderCount = doc.getLong("orderCount") ?: 0L,
                        imageUrl = doc.getString("imageUrl") ?: "",
                        photos = (doc.get("photos") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        topMenuPreview = mapTopMenuPreview(doc.id, doc.get("topMenuPreview"))
                    )
                })
            }
        awaitClose { listener.remove() }
    }

    /**
     * Cari menu lewat field `searchKeywords` (query array-contains).
     *
     * Hanya membaca dokumen yang COCOK (belasan), bukan memindai seluruh koleksi
     * `menu` — jauh lebih hemat. Field `searchKeywords` diisi otomatis oleh
     * Cloud Function `indexMenuKeywords` (prefix nama + kategori, lowercase).
     */
    suspend fun searchMenuKeywords(tokens: List<String>): List<MenuKeywordHit> {
        if (tokens.isEmpty()) return emptyList()
        return try {
            val hits = mutableListOf<MenuKeywordHit>()
            val seen = mutableSetOf<String>()
            for (token in tokens.distinct().take(MENU_KEYWORD_MAX_TOKENS)) {
                val snap = menuRef
                    .whereArrayContains("searchKeywords", token)
                    .limit(MENU_KEYWORD_LIMIT)
                    .get().await()
                for (doc in snap.documents) {
                    if (!seen.add(doc.id)) continue
                    if (doc.getBoolean("available") == false) continue
                    val merchantId = doc.getString("merchantId") ?: continue
                    val name = doc.getString("name") ?: continue
                    if (merchantId.isBlank() || name.isBlank()) continue
                    hits.add(MenuKeywordHit(merchantId, name))
                }
            }
            hits
        } catch (e: Exception) {
            Log.w("FoodRepo", "searchMenuKeywords gagal", e)
            emptyList()
        }
    }

    /**
     * Fallback rollout untuk merchant yang belum memiliki `topMenuPreview`.
     * Query dibatch dengan `whereIn`, bukan satu query/listener per kartu toko.
     */
    suspend fun getMenusForMerchants(merchantIds: List<String>): Map<String, List<MenuItem>> {
        val ids = merchantIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return try {
            val result = mutableMapOf<String, MutableList<MenuItem>>()
            ids.chunked(MERCHANT_ID_BATCH_SIZE).forEach { batch ->
                val snap = menuRef.whereIn("merchantId", batch).get().await()
                snap.documents.forEach { doc ->
                    val merchantId = doc.getString("merchantId").orEmpty()
                    if (merchantId.isNotBlank()) {
                        result.getOrPut(merchantId) { mutableListOf() }.add(mapMenuItem(doc, merchantId))
                    }
                }
            }
            result
        } catch (e: Exception) {
            Log.w("FoodRepo", "getMenusForMerchants gagal", e)
            emptyMap()
        }
    }

    /**
     * Nama menu untuk ulasan lama / ulasan dari web yang hanya menyimpan
     * `menuItemId`. Menu yang sudah dihapus tidak dikembalikan.
     */
    suspend fun getMenuNames(menuIds: List<String>): Map<String, String> {
        val ids = menuIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return try {
            val names = mutableMapOf<String, String>()
            ids.chunked(MENU_ID_BATCH_SIZE).forEach { batch ->
                menuRef.whereIn(com.google.firebase.firestore.FieldPath.documentId(), batch)
                    .get().await().documents.forEach { doc ->
                        val name = doc.getString("name").orEmpty()
                        if (name.isNotBlank()) names[doc.id] = name
                    }
            }
            names
        } catch (e: Exception) {
            Log.w("FoodRepo", "getMenuNames gagal", e)
            emptyMap()
        }
    }

    /**
     * Ulasan satu toko, terbaru dulu (layar "Lihat Review").
     *
     * Butuh index komposit `reviews(merchantId ASC, createdAt DESC)` — lihat
     * firestore.indexes.json. Server-only: hasil cache di-skip supaya ulasan
     * yang baru dikirim langsung terlihat, sama seperti listener order lain.
     */
    fun observeMerchantReviews(
        merchantId: String,
        limit: Long = REVIEW_PAGE_SIZE
    ): Flow<ReviewStreamState> = callbackFlow {
        if (merchantId.isEmpty()) {
            trySend(ReviewStreamState())
            awaitClose {}
            return@callbackFlow
        }
        val listener = reviewsRef
            .whereEqualTo("merchantId", merchantId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null) {
                    trySend(ReviewStreamState(error = err.message ?: "gagal memuat ulasan"))
                    return@addSnapshotListener
                }
                if (snap == null) {
                    trySend(ReviewStreamState(error = "gagal memuat ulasan"))
                    return@addSnapshotListener
                }
                if (snap.metadata.isFromCache) return@addSnapshotListener
                trySend(ReviewStreamState(reviews = snap.documents.map { mapMerchantReview(it) }))
            }
        awaitClose { listener.remove() }
    }

    private fun mapMerchantReview(snap: com.google.firebase.firestore.DocumentSnapshot): MerchantReview {
        val menuItems = (snap.get("menuItems") as? List<*>)?.mapNotNull { it as? Map<*, *> }?.map { m ->
            ReviewLineItem(
                name = m["name"] as? String ?: "",
                rating = (m["rating"] as? Number)?.toInt() ?: 0
            )
        }?.filter { it.name.isNotBlank() } ?: emptyList()

        val menuRatings = (snap.get("menuRatings") as? List<*>)?.mapNotNull { it as? Map<*, *> }?.mapNotNull { m ->
            val id = m["menuItemId"] as? String
            if (id.isNullOrBlank()) null else id to ((m["rating"] as? Number)?.toInt() ?: 0)
        } ?: emptyList()

        return MerchantReview(
            id = snap.id,
            orderId = snap.getString("orderId") ?: "",
            customerName = snap.getString("customerName").orEmpty(),
            merchantRating = (snap.getLong("merchantRating") ?: 0L).toInt(),
            merchantComment = snap.getString("merchantComment") ?: "",
            menuItems = menuItems,
            menuRatings = menuRatings,
            createdAt = snap.getLong("createdAt") ?: 0L
        )
    }

    /** Menu dari satu merchant (hanya yang tersedia). */
    suspend fun getMenu(merchantId: String): List<MenuItem> = try {
        menuRef
            .whereEqualTo("merchantId", merchantId)
            .orderBy("createdAt")
            .get().await().documents.map { doc -> mapMenuItem(doc, merchantId) }
            .filter { it.available }
    } catch (e: Exception) { emptyList() }

    /**
     * Menu real-time (server-only) — TERMASUK item habis, supaya UI bisa
     * mendeteksi perubahan `available` (toggle "habis" toko) dan langsung
     * menghapus item dari keranjang customer.
     */
    fun observeMenu(merchantId: String): Flow<List<MenuItem>> = callbackFlow {
        if (merchantId.isEmpty()) { trySend(emptyList()); awaitClose {}; return@callbackFlow }
        val listener = menuRef
            .whereEqualTo("merchantId", merchantId)
            .orderBy("createdAt")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null) { trySend(emptyList()); return@addSnapshotListener }
                if (snap == null) { trySend(emptyList()); return@addSnapshotListener }
                if (snap.metadata.isFromCache) return@addSnapshotListener
                trySend(snap.documents.map { doc -> mapMenuItem(doc, merchantId) })
            }
        awaitClose { listener.remove() }
    }

    data class FoodQuote(
        val distanceKm: Double,
        val deliveryFee: Long,
        val adminFee: Long,
        /** Kode voucher yang dipakai (kosong = tidak pakai voucher). */
        val voucherCode: String = "",
        /** Judul voucher dari server, untuk ditampilkan. */
        val voucherTitle: String = "",
        /** Potongan yang dihitung SERVER untuk order ini (Rp). */
        val voucherDiscount: Long = 0L,
        /** Alasan voucher tidak bisa dipakai (kosong = sah). */
        val voucherMessage: String = ""
    )

    /**
     * Server-only delivery/service-fee quote; it creates no order or driver offer.
     * `items` dikirim supaya server bisa menghitung basis voucher ber-scope menu;
     * `voucherCode` kosong berarti tanpa voucher (respons sama seperti sebelumnya).
     */
    suspend fun quoteFoodOrder(
        merchantId: String,
        customerLat: Double,
        customerLng: Double,
        distanceKm: Double,
        items: List<FoodItem>,
        voucherCode: String
    ): FoodQuote {
        val payload = mutableMapOf<String, Any?>(
            "merchantId" to merchantId,
            "customerLat" to customerLat,
            "customerLng" to customerLng,
            "distanceKm" to distanceKm,
            "appVersionCode" to com.suruhaja.BuildConfig.VERSION_CODE,
            "items" to items.map {
                mapOf(
                    "menuItemId" to it.menuItemId,
                    "name" to it.name,
                    "qty" to it.qty,
                    "price" to it.price,
                    "addons" to it.addons.map { a -> mapOf("id" to a.id, "qty" to a.qty, "price" to a.price) }
                )
            }
        )
        if (voucherCode.isNotBlank()) payload["voucherCode"] = voucherCode

        // Jalur cadangan (relay) dipakai otomatis kalau cloudfunctions.net tidak
        // terjangkau dari perangkat ini (mis. operator by.U).
        val data = CallableFallback.call("quoteFoodOrder", payload) as? Map<*, *>
            ?: error("Quote server tidak valid")
        val voucher = data["voucher"] as? Map<*, *>
        return FoodQuote(
            distanceKm = (data["distanceKm"] as? Number)?.toDouble() ?: error("Jarak quote tidak valid"),
            deliveryFee = (data["deliveryFee"] as? Number)?.toLong() ?: error("Ongkir quote tidak valid"),
            adminFee = (data["adminFee"] as? Number)?.toLong() ?: error("Biaya layanan quote tidak valid"),
            voucherCode = (voucher?.get("code") as? String).orEmpty(),
            voucherTitle = (voucher?.get("title") as? String).orEmpty(),
            voucherDiscount = (voucher?.get("discount") as? Number)?.toLong() ?: 0L,
            voucherMessage = (voucher?.get("message") as? String).orEmpty()
        )
    }

    /**
     * Buat order SuruhFood. Backend langsung membuat status `seeking_driver` dan offer Driver.
     * Ongkir dihitung dari jarak customer → toko (distanceKm), min Rp 7.000.
     */
    suspend fun createFoodOrder(
        merchantId: String,
        merchantName: String,
        merchantLat: Double,
        merchantLng: Double,
        customerId: String,
        customerName: String,
        customerPhone: String,
        customerAddress: String,
        customerLat: Double,
        customerLng: Double,
        items: List<FoodItem>,
        total: Long,
        distanceKm: Double,
        deliveryFee: Long,
        paymentMethod: String,
        notes: String,
        voucherCode: String = ""
    ): String {
        val result = Fungsi.getInstance("asia-southeast2")
            .getHttpsCallable("createFoodOrder")
            .call(
                mapOf(
                    "merchantId" to merchantId,
                    "voucherCode" to voucherCode,
                    "merchantName" to merchantName,
                    "merchantLat" to merchantLat,
                    "merchantLng" to merchantLng,
                    "customerAddress" to customerAddress,
                    "customerLat" to customerLat,
                    "customerLng" to customerLng,
                    "items" to items.map {
                        mapOf(
                            "menuItemId" to it.menuItemId,
                            "name" to it.name,
                            "qty" to it.qty,
                            "price" to it.price,
                            "addons" to it.addons.map { a -> mapOf("id" to a.id, "qty" to a.qty) }
                        )
                    },
                    "distanceKm" to distanceKm,
                    "paymentMethod" to paymentMethod,
                    "notes" to notes,
                    "appVersionCode" to com.suruhaja.BuildConfig.VERSION_CODE
                )
            ).await()
        return (result.getData() as? Map<*, *>)?.get("orderId") as? String ?: ""
    }

    /** Lacak status order food real-time (server-only, hindari cache staleness). */
    fun observeFoodOrder(orderId: String): Flow<FoodOrder?> =
        observeFoodOrderWithState(orderId).map { it.order }

    fun observeFoodOrderWithState(orderId: String): Flow<FoodOrderStreamState> = callbackFlow {
        if (orderId.isEmpty()) { trySend(FoodOrderStreamState(error = "Order tidak valid")); awaitClose {}; return@callbackFlow }
        var lastOrder: FoodOrder? = null
        val listener = ordersRef.document(orderId)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null) { trySend(FoodOrderStreamState(lastOrder, err.message ?: "Koneksi gagal")); return@addSnapshotListener }
                if (snap == null) { trySend(FoodOrderStreamState(lastOrder, "Data order tidak tersedia")); return@addSnapshotListener }
                if (snap.metadata.isFromCache) return@addSnapshotListener
                if (!snap.exists()) { lastOrder = null; trySend(FoodOrderStreamState()); return@addSnapshotListener }
                mapFoodOrder(snap).also { lastOrder = it; trySend(FoodOrderStreamState(it)) }
            }
        awaitClose { listener.remove() }
    }

    suspend fun refreshFoodOrder(orderId: String): Result<FoodOrder?> = try {
        val snap = ordersRef.document(orderId).get(Source.SERVER).await()
        Result.success(if (snap.exists()) mapFoodOrder(snap) else null)
    } catch (e: Exception) { Result.failure(e) }

    /**
     * Bayar order food pakai saldo customer (atomic):
     * - Customer potong (total + ongkir).
     * - Driver kredit 80% ongkir (jatah 20% owner implicit).
     * - Order tandai paid + paidBy=saldo.
     * Kredit merchant (total makanan) dilakukan driver saat completeFoodOrder.
     */
    suspend fun payFoodFromBalance(orderId: String, customerUid: String): Result<Unit> = try {
        Fungsi.getInstance("asia-southeast2").getHttpsCallable("payFoodOrder")
            .call(mapOf("orderId" to orderId, "appVersionCode" to com.suruhaja.BuildConfig.VERSION_CODE)).await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Batalkan order food oleh customer — hanya valid saat status masih
     * `pending`/`seeking_driver` (belum ada driver yang menerima).
     */
    suspend fun cancelFoodOrder(orderId: String): Result<Unit> = try {
        ordersRef.document(orderId).update(
            "status", FoodOrder.STATUS_CANCELLED,
            "cancelledBy", "customer",
            "cancelReason", "Dibatalkan pelanggan",
            "updatedAt", System.currentTimeMillis()
        ).await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Minta pembatalan (mutual) — driver harus setujui. */
    suspend fun requestCancelFoodOrder(orderId: String): Result<Unit> = try {
        ordersRef.document(orderId).update(
            "cancelRequestedBy", "customer",
            "updatedAt", System.currentTimeMillis()
        ).await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Setujui permintaan pembatalan pihak lain → order jadi cancelled. */
    suspend fun approveFoodCancel(orderId: String, requester: String): Result<Unit> = try {
        ordersRef.document(orderId).update(
            "status", FoodOrder.STATUS_CANCELLED,
            "cancelledBy", requester,
            "cancelRequestedBy", "",
            "updatedAt", System.currentTimeMillis()
        ).await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Tolak permintaan pembatalan pihak lain. */
    suspend fun rejectFoodCancel(orderId: String): Result<Unit> = try {
        ordersRef.document(orderId).update(
            "cancelRequestedBy", "",
            "updatedAt", System.currentTimeMillis()
        ).await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Simpan review food. DUA tahap:
     * 1) Primary (atomik): tulis dokumen review + tandai order rated=true.
     * 2) Agregat rating driver/toko/menu — best-effort (non-fatal), supaya
     *    kegagalan agregat tidak menggagalkan review + navigasi.
     */
    suspend fun submitFoodReview(
        orderId: String,
        customerId: String,
        customerName: String,
        merchantId: String,
        driverId: String,
        merchantName: String,
        driverName: String,
        merchantRating: Int,
        driverRating: Int,
        merchantComment: String,
        driverComment: String,
        reviewedItems: List<ReviewedMenuItem>
    ): Result<Unit> = try {
        val now = System.currentTimeMillis()
        firestore.runTransaction { tx ->
            tx.set(firestore.collection("reviews").document(), mapOf(
                "orderId" to orderId,
                "orderType" to "food",
                "customerId" to customerId,
                // Nama disamarkan karena ulasan bisa dibaca semua pengguna login,
                // sedangkan users/{uid} hanya bisa dibaca pemiliknya.
                "customerName" to ReviewPrivacyPolicy.maskName(customerName),
                "merchantId" to merchantId,
                "driverId" to driverId,
                "merchantName" to merchantName,
                "driverName" to driverName,
                "merchantRating" to merchantRating,
                "driverRating" to driverRating,
                "merchantComment" to merchantComment,
                "driverComment" to driverComment,
                "menuRatings" to reviewedItems.map { mapOf("menuItemId" to it.menuItemId, "rating" to it.rating) },
                "menuItems" to reviewedItems.map { mapOf("name" to it.name, "rating" to it.rating) },
                "createdAt" to now
            ))
            tx.update(ordersRef.document(orderId), "rated", true)
        }.await()

        // Agregat rating (best-effort)
        try {
            aggregateDriverRating(driverId, driverRating)
            aggregateMerchantRating(merchantId, merchantRating)
            aggregateMenuRatings(reviewedItems.map { it.menuItemId to it.rating })
        } catch (e: Exception) {
            Log.e("FoodRepo", "aggregate ratings failed", e)
        }

        Result.success(Unit)
    } catch (e: Exception) {
        Log.e("FoodRepo", "submitFoodReview failed", e)
        Result.failure(e)
    }

    private suspend fun aggregateDriverRating(driverId: String, rating: Int) {
        if (driverId.isEmpty()) return
        val dr = firestore.collection("drivers").document(driverId)
        firestore.runTransaction { tx ->
            val d = tx.get(dr)
            val or = d.getDouble("rating") ?: 0.0
            val oc = d.getLong("ratingCount") ?: 0L
            tx.update(dr, "rating", (or * oc + rating) / (oc + 1), "ratingCount", oc + 1)
        }.await()
    }

    private suspend fun aggregateMerchantRating(merchantId: String, rating: Int) {
        if (merchantId.isEmpty()) return
        val mr = merchantsRef.document(merchantId)
        firestore.runTransaction { tx ->
            val m = tx.get(mr)
            val or = m.getDouble("rating") ?: 0.0
            val oc = m.getLong("ratingCount") ?: 0L
            tx.update(mr, "rating", (or * oc + rating) / (oc + 1), "ratingCount", oc + 1)
        }.await()
    }

    private suspend fun aggregateMenuRatings(menuRatings: List<Pair<String, Int>>) {
        for ((menuId, rating) in menuRatings) {
            if (menuId.isEmpty()) continue
            val ref = menuRef.document(menuId)
            firestore.runTransaction { tx ->
                val m = tx.get(ref)
                if (m.exists()) {
                    val or = m.getDouble("rating") ?: 0.0
                    val oc = m.getLong("ratingCount") ?: 0L
                    tx.update(ref, "rating", (or * oc + rating) / (oc + 1), "ratingCount", oc + 1)
                }
            }.await()
        }
    }

    private fun mapMenuItem(
        doc: com.google.firebase.firestore.DocumentSnapshot,
        merchantId: String
    ): MenuItem = MenuItem(
        id = doc.id,
        merchantId = merchantId,
        name = doc.getString("name") ?: "",
        category = doc.getString("category") ?: "",
        price = doc.longOr("price"),
        description = doc.getString("description") ?: "",
        available = doc.getBoolean("available") ?: true,
        imageUrl = doc.getString("imageUrl") ?: "",
        addons = mapMenuAddons(doc),
        soldCount = doc.getLong("soldCount") ?: 0L,
        rating = doc.getDouble("rating") ?: 0.0,
        ratingCount = doc.getLong("ratingCount") ?: 0L,
        featured = doc.getBoolean("featured") ?: false,
        featuredRank = doc.getLong("featuredRank") ?: 0L
    )

    /** Nested preview dari merchant doc; sengaja tanpa addon supaya payload daftar kecil. */
    private fun mapTopMenuPreview(merchantId: String, raw: Any?): List<MenuItem> =
        (raw as? List<*>)?.mapNotNull { it as? Map<*, *> }?.map { item ->
            MenuItem(
                id = item["id"] as? String ?: "",
                merchantId = merchantId,
                name = item["name"] as? String ?: "",
                category = item["category"] as? String ?: "",
                price = (item["price"] as? Number)?.toLong() ?: 0L,
                available = item["available"] as? Boolean ?: true,
                imageUrl = item["imageUrl"] as? String ?: "",
                soldCount = (item["soldCount"] as? Number)?.toLong() ?: 0L,
                rating = (item["rating"] as? Number)?.toDouble() ?: 0.0,
                ratingCount = (item["ratingCount"] as? Number)?.toLong() ?: 0L,
                featured = item["featured"] as? Boolean ?: false,
                featuredRank = (item["featuredRank"] as? Number)?.toLong() ?: 0L
            )
        }?.filter { it.id.isNotBlank() && it.name.isNotBlank() } ?: emptyList()

    private fun mapMenuAddons(doc: com.google.firebase.firestore.DocumentSnapshot): List<MenuAddon> =
        (doc.get("addons") as? List<*>)?.mapNotNull { it as? Map<*, *> }?.map { m ->
            MenuAddon(
                id = m["id"] as? String ?: "",
                name = m["name"] as? String ?: "",
                price = (m["price"] as? Number)?.toLong() ?: 0L,
                available = m["available"] as? Boolean ?: true
            )
        } ?: emptyList()

    private fun mapFoodOrder(snap: com.google.firebase.firestore.DocumentSnapshot): FoodOrder {
        val items = (snap.get("items") as? List<*>)?.mapNotNull { it as? Map<*, *> }?.map { m ->
            FoodItem(
                menuItemId = m["menuItemId"] as? String ?: "",
                name = m["name"] as? String ?: "",
                qty = (m["qty"] as? Number)?.toInt() ?: 0,
                price = (m["price"] as? Number)?.toLong() ?: 0L,
                addons = (m["addons"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.map { a ->
                    FoodAddon(
                        id = a["id"] as? String ?: "",
                        name = a["name"] as? String ?: "",
                        price = (a["price"] as? Number)?.toLong() ?: 0L,
                        qty = (a["qty"] as? Number)?.toInt() ?: 0
                    )
                } ?: emptyList()
            )
        } ?: emptyList()
        return FoodOrder(
            id = snap.id,
            merchantId = snap.getString("merchantId") ?: "",
            merchantName = snap.getString("merchantName") ?: "",
            merchantLat = snap.getDouble("merchantLat") ?: 0.0,
            merchantLng = snap.getDouble("merchantLng") ?: 0.0,
            customerId = snap.getString("customerId") ?: "",
            customerName = snap.getString("customerName") ?: "",
            customerPhone = snap.getString("customerPhone") ?: "",
            customerAddress = snap.getString("customerAddress") ?: "",
            customerLat = snap.getDouble("customerLat") ?: 0.0,
            customerLng = snap.getDouble("customerLng") ?: 0.0,
            driverId = snap.getString("driverId") ?: "",
            driverName = snap.getString("driverName") ?: "",
            driverPhone = snap.getString("driverPhone") ?: "",
            driverLat = snap.getDouble("driverLat") ?: 0.0,
            driverLng = snap.getDouble("driverLng") ?: 0.0,
            items = items,
            total = snap.longOr("total"),
            distanceKm = snap.getDouble("distanceKm") ?: 0.0,
            deliveryFee = snap.longOr("deliveryFee"),
            adminFee = snap.longOr("adminFee"),
            paymentMethod = snap.getString("paymentMethod") ?: "cash",
            paymentStatus = snap.getString("paymentStatus") ?: "unpaid",
            paidBy = snap.getString("paidBy") ?: "",
            awaitingPayment = snap.getBoolean("awaitingPayment") ?: false,
            paymentRequest = snap.getString("paymentRequest") ?: "",
            rated = snap.getBoolean("rated") ?: false,
            status = snap.getString("status") ?: FoodOrder.STATUS_PENDING,
            notes = snap.getString("notes") ?: "",
            cancelledBy = snap.getString("cancelledBy") ?: "",
            cancelReason = snap.getString("cancelReason") ?: "",
            cancelRequestedBy = snap.getString("cancelRequestedBy") ?: "",
            voucherCode = snap.getString("voucherCode") ?: "",
            voucherDiscount = snap.getLong("voucherDiscount") ?: 0L,
            unreadByCustomer = snap.getLong("unreadByCustomer") ?: 0L,
            createdAt = snap.getLong("createdAt") ?: 0L,
            updatedAt = snap.getLong("updatedAt") ?: 0L
        )
    }
}
