package com.suruhaja.ui.home

import android.util.Log
import android.os.Bundle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.suruhaja.data.repository.PromotionRepository
import com.suruhaja.data.policy.PromoVisibilityPolicy
import com.suruhaja.data.model.Merchant
import com.suruhaja.data.model.Order
import com.suruhaja.data.model.FoodOrder
import com.suruhaja.data.model.Promotion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val promotionRepository: PromotionRepository
) : ViewModel() {

    private val _userName = MutableLiveData("")
    val userName: LiveData<String> = _userName

    private val _balance = MutableLiveData(0L)
    val balance: LiveData<Long> = _balance

    // Active Ride/Send order.
    private val _activeOrder = MutableLiveData<Order?>(null)
    val activeOrder: LiveData<Order?> = _activeOrder

    // Active Food order, kept separate because it lives in merchant_orders.
    private val _activeFoodOrder = MutableLiveData<FoodOrder?>(null)
    val activeFoodOrder: LiveData<FoodOrder?> = _activeFoodOrder

    private val _activeDriverName = MutableLiveData("")
    val activeDriverName: LiveData<String> = _activeDriverName

    // ── Penawaran Terbaik (diisi admin dari web/admin) ──
    private val _promotions = MutableLiveData<List<Promotion>>(emptyList())
    val promotions: LiveData<List<Promotion>> = _promotions

    // ── Notifikasi admin: true kalau ada yang belum dibaca (bell merah) ──
    private val _hasUnreadNotifications = MutableLiveData(false)
    val hasUnreadNotifications: LiveData<Boolean> = _hasUnreadNotifications

    /** Bundle tujuan toko setelah promo ditekan; dikonsumsi sekali oleh Fragment. */
    private val _promoTarget = MutableLiveData<Bundle?>(null)
    val promoTarget: LiveData<Bundle?> = _promoTarget

    private val _promoMessage = MutableLiveData<String?>(null)
    val promoMessage: LiveData<String?> = _promoMessage

    private var orderListener: ListenerRegistration? = null
    private var foodOrderListener: ListenerRegistration? = null
    private var balanceListener: ListenerRegistration? = null
    private var promoListener: ListenerRegistration? = null
    private var announcementsListener: ListenerRegistration? = null

    private var notifReadAt = 0L
    private var latestAnnouncementAt = 0L

    /** Semua promo aktif dari server; yang ditampilkan disaring per lokasi. */
    private var allPromos: List<Promotion> = emptyList()
    private var customerLat = 0.0
    private var customerLng = 0.0

    companion object {
        /** Announcement terbaru yang di-scan untuk status unread bell. */
        private const val ANNOUNCEMENT_SCAN_LIMIT = 10L
    }

    init {
        loadUserData()
        observeBalance()
        observeActiveOrder()
        observePromotions()
        observeAnnouncements()
    }

    private fun loadUserData() {
        viewModelScope.launch {
            try {
                val uid = auth.currentUser?.uid ?: return@launch
                val doc = firestore.collection("users").document(uid).get().await()
                _userName.value = (doc.getString("name") ?: "").ifEmpty { "Pengguna" }
            } catch (e: Exception) {
                _userName.value = "Pengguna"
            }
        }
    }

    /** Saldo harus live supaya bertambah setelah top-up (PayHook kredit balance). */
    private fun observeBalance() {
        val uid = auth.currentUser?.uid ?: return
        balanceListener?.remove()
        balanceListener = firestore.collection("users").document(uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                if (snap.metadata.isFromCache) return@addSnapshotListener
                if (!snap.exists()) return@addSnapshotListener
                _userName.value = (snap.getString("name") ?: "").ifEmpty { "Pengguna" }
                _balance.value = snap.getLong("balance") ?: 0L
                notifReadAt = snap.getLong("notifReadAt") ?: 0L
                recomputeUnread()
            }
    }

    /**
     * Real-time listener for the user's active order. Shows the ongoing trip card
     * on the home screen. Filter status client-side (no composite index needed).
     */
    private fun observeActiveOrder() {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrEmpty()) return
        orderListener?.remove()
        orderListener = firestore.collection("orders")
            .whereEqualTo("userId", uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                if (snap.metadata.isFromCache) return@addSnapshotListener
                val active = snap.documents
                    .mapNotNull { doc -> try { doc.toObject(Order::class.java)?.copy(id = doc.id) } catch (_: Exception) { null } }
                    .filter { it.status in listOf("pending", "accepted", "pickup", "delivering") }
                    .maxByOrNull { it.createdAt }
                _activeOrder.value = active

                val driverId = active?.driverId.orEmpty()
                if (driverId.isNotEmpty()) {
                    viewModelScope.launch {
                        try {
                            val d = firestore.collection("drivers").document(driverId).get().await()
                            _activeDriverName.value = d.getString("name") ?: ""
                        } catch (e: Exception) {
                            Log.w("HomeVM", "fetch driver", e)
                            _activeDriverName.value = ""
                        }
                    }
                } else {
                    _activeDriverName.value = ""
                }
            }

        foodOrderListener?.remove()
        foodOrderListener = firestore.collection("merchant_orders")
            .whereEqualTo("customerId", uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null || snap == null || snap.metadata.isFromCache) return@addSnapshotListener
                _activeFoodOrder.value = snap.documents
                    .mapNotNull { doc -> try { doc.toObject(FoodOrder::class.java)?.copy(id = doc.id) } catch (_: Exception) { null } }
                    .filter { it.status in listOf(FoodOrder.STATUS_PENDING, "seeking_driver", "accepted", "preparing", "ready", "delivering") }
                    .maxByOrNull { it.createdAt }
            }
    }

    fun refresh() { loadUserData() }

    /** Penawaran aktif, urut `order` lalu terbaru. Tanpa orderBy agar dokumen
     *  lama yang belum punya field `order` tidak ikut terbuang. */
    private fun observePromotions() {
        viewModelScope.launch {
            promotionRepository.observeActivePromotions().collect { list ->
                allPromos = list
                applyPromoVisibility()
            }
        }
    }

    /**
     * Simpan lokasi customer (dipakai menyaring promo) lalu saring ulang.
     * Home jadi ikut aturan radius: customer di Madura tidak melihat promo
     * toko yang ada di Aceh.
     */
    fun setCustomerLocation(lat: Double, lng: Double) {
        if (lat == 0.0 && lng == 0.0) return
        if (customerLat == lat && customerLng == lng) return
        customerLat = lat; customerLng = lng
        applyPromoVisibility()
    }

    private fun applyPromoVisibility() {
        _promotions.value = PromoVisibilityPolicy.visible(allPromos, customerLat, customerLng)
    }

    /**
     * Pantau announcement customer untuk menentukan bell merah/hitam.
     *
     * Hanya butuh timestamp TERBARU, jadi dibaca [ANNOUNCEMENT_SCAN_LIMIT]
     * dokumen terbaru (bukan 50) — hemat baca di Home. Announcement beraudiens
     * driver/toko yang menyelip di daftar terbaru tetap difilter di klien.
     */
    private fun observeAnnouncements() {
        announcementsListener?.remove()
        announcementsListener = firestore.collection("announcements")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(ANNOUNCEMENT_SCAN_LIMIT)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                latestAnnouncementAt = snap.documents
                    .filter { doc ->
                        val active = doc.getBoolean("active") ?: true
                        val audience = doc.getString("audience") ?: "customer"
                        active && (audience == "customer" || audience == "all")
                    }
                    .maxOfOrNull { it.getLong("createdAt") ?: 0L } ?: 0L
                recomputeUnread()
            }
    }

    private fun recomputeUnread() {
        _hasUnreadNotifications.value = latestAnnouncementAt > notifReadAt
    }

    fun consumePromoMessage() { _promoMessage.value = null }

    /** Kode voucher yang harus disalin app (ditangani Fragment — butuh Clipboard). */
    private val _promoCopyCode = MutableLiveData<String?>(null)
    val promoCopyCode: LiveData<String?> = _promoCopyCode

    fun consumePromoCopyCode() { _promoCopyCode.value = null }

    fun clearPromoTarget() { _promoTarget.value = null }

    /**
     * Promo ditekan → aksi mengikuti targetType yang dipilih admin:
     *   voucher  → salin kode voucher
     *   toko/menu/ride/send + merchantId → buka menu toko tujuan
     *   sisanya  → tampilkan info saja
     */
    fun openPromotion(promo: Promotion) {
        when {
            promo.targetType == Promotion.TARGET_VOUCHER && promo.voucherCode.isNotBlank() -> {
                _promoCopyCode.value = promo.voucherCode
            }
            promo.merchantId.isNotBlank() -> when {
                customerLat == 0.0 || customerLng == 0.0 ->
                    _promoMessage.value = "Menunggu lokasi Anda…"
                !PromoVisibilityPolicy.isWithinRange(promo, customerLat, customerLng) ->
                    _promoMessage.value =
                        "Toko pada penawaran ini di luar jangkauan (maks ${PromoVisibilityPolicy.MAX_STORE_RADIUS_KM.toInt()} km)"
                else -> openPromotionMerchant(promo)
            }
            else -> {
                _promoMessage.value = promo.subtitle.ifBlank { "Penawaran ini hanya informasi." }
            }
        }
    }

    /** Ambil dokumen toko tujuan (nama + koordinat) supaya bisa buka menu toko itu. */
    private fun openPromotionMerchant(promo: Promotion) {
        viewModelScope.launch {
            try {
                val doc = firestore.collection("merchants").document(promo.merchantId).get().await()
                val merchant = doc.toObject(Merchant::class.java)
                if (merchant == null) {
                    _promoMessage.value = "Toko pada penawaran ini tidak ditemukan"
                    return@launch
                }
                _promoTarget.value = Bundle().apply {
                    putString("merchantId", promo.merchantId)
                    putString("merchantName", merchant.storeName.ifEmpty { "Toko" })
                    putFloat("merchantLat", merchant.latitude.toFloat())
                    putFloat("merchantLng", merchant.longitude.toFloat())
                }
            } catch (e: Exception) {
                Log.w("HomeVM", "openPromotion", e)
                _promoMessage.value = "Gagal membuka toko: ${e.message ?: "coba lagi"}"
            }
        }
    }

    /** Tulis lokasi customer ke users/{uid} (private: hanya self-read — customer lain tak bisa lihat). */
    fun updateLocation(lat: Double, lng: Double) {
        val uid = auth.currentUser?.uid ?: return
        if (lat == 0.0 && lng == 0.0) return
        firestore.collection("users").document(uid)
            .set(
                mapOf(
                    "currentLat" to lat,
                    "currentLng" to lng,
                    "lastSeen" to System.currentTimeMillis()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            .addOnFailureListener { Log.w("HomeVM", "updateLocation", it) }
    }

    override fun onCleared() {
        super.onCleared()
        orderListener?.remove()
        foodOrderListener?.remove()
        balanceListener?.remove()
        promoListener?.remove()
        announcementsListener?.remove()
    }
}
