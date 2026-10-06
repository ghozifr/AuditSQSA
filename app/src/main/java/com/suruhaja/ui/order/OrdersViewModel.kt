package com.suruhaja.ui.order

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.suruhaja.data.model.Order
import com.suruhaja.data.repository.FoodRepository
import com.suruhaja.util.longOr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val foodRepository: FoodRepository
) : ViewModel() {

    private val _orders = MutableLiveData<List<Order>>(emptyList())
    val orders: LiveData<List<Order>> = _orders

    private val _activeOrders = MutableLiveData<List<Order>>(emptyList())
    val activeOrders: LiveData<List<Order>> = _activeOrders

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _loadingMore = MutableLiveData(false)
    val loadingMore: LiveData<Boolean> = _loadingMore

    private val _hasMoreHistory = MutableLiveData(false)
    val hasMoreHistory: LiveData<Boolean> = _hasMoreHistory

    private val _historyMessage = MutableLiveData<String?>(null)
    val historyMessage: LiveData<String?> = _historyMessage

    private val _stats = MutableLiveData(Triple(0, 0, 0L)) // loaded trips, loaded food orders, points
    val stats: LiveData<Triple<Int, Int, Long>> = _stats

    private var lastRideHistory: DocumentSnapshot? = null
    private var lastFoodHistory: DocumentSnapshot? = null
    private var initialLoadStarted = false

    init {
        loadInitial()
    }

    /**
     * Cache-first initial screen: cached history is shown immediately, then one server
     * reconciliation replaces it. Returning to this Fragment does not re-query server.
     */
    fun loadInitial() {
        if (initialLoadStarted) return
        initialLoadStarted = true
        _loading.value = true
        viewModelScope.launch {
            val uid = auth.currentUser?.uid ?: run {
                _loading.value = false
                return@launch
            }
            loadHistoryPage(uid, Source.CACHE, replace = true, updateCursor = false)
            loadActiveAndPoints(uid, Source.CACHE)
            refreshFromServer(uid)
            _loading.value = false
        }
    }

    /** Explicit user refresh; never triggered merely by onResume. */
    fun refresh() {
        viewModelScope.launch {
            val uid = auth.currentUser?.uid ?: return@launch
            _loading.value = true
            refreshFromServer(uid)
            _loading.value = false
        }
    }

    fun loadMore() {
        if (_loadingMore.value == true || _hasMoreHistory.value != true) return
        viewModelScope.launch {
            val uid = auth.currentUser?.uid ?: return@launch
            _loadingMore.value = true
            loadHistoryPage(uid, Source.SERVER, replace = false, updateCursor = true)
            _loadingMore.value = false
        }
    }

    private suspend fun refreshFromServer(uid: String) {
        lastRideHistory = null
        lastFoodHistory = null
        loadHistoryPage(uid, Source.SERVER, replace = true, updateCursor = true)
        loadActiveAndPoints(uid, Source.SERVER)
        loadAllHistoryStats(uid)
    }

    private suspend fun loadHistoryPage(
        uid: String,
        source: Source,
        replace: Boolean,
        updateCursor: Boolean
    ) {
        try {
            var rides = firestore.collection("orders")
                .whereEqualTo("userId", uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(HISTORY_PAGE_SIZE.toLong())
            var foods = firestore.collection("merchant_orders")
                .whereEqualTo("customerId", uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(HISTORY_PAGE_SIZE.toLong())

            if (!replace && source == Source.SERVER) {
                lastRideHistory?.let { rides = rides.startAfter(it) }
                lastFoodHistory?.let { foods = foods.startAfter(it) }
            }

            val rideSnapshot = rides.get(source).await()
            val foodSnapshot = foods.get(source).await()

            if (updateCursor) {
                lastRideHistory = rideSnapshot.documents.lastOrNull() ?: lastRideHistory
                lastFoodHistory = foodSnapshot.documents.lastOrNull() ?: lastFoodHistory
                _hasMoreHistory.value =
                    rideSnapshot.size() == HISTORY_PAGE_SIZE || foodSnapshot.size() == HISTORY_PAGE_SIZE
            }

            val incoming = (rideSnapshot.documents.mapNotNull(::historyRide)
                + foodSnapshot.documents.mapNotNull(::historyFood))
                .sortedByDescending { it.createdAt }

            val combined = if (replace) incoming else {
                (_orders.value.orEmpty() + incoming)
                    .distinctBy { "${it.serviceType}:${it.id}" }
                    .sortedByDescending { it.createdAt }
            }
            _orders.value = combined
            _historyMessage.value = when {
                source == Source.CACHE && combined.isNotEmpty() -> "Menampilkan riwayat tersimpan"
                source == Source.SERVER -> null
                else -> null
            }
        } catch (_: Exception) {
            // A cold/empty disk cache is expected on first use. Keep any already-rendered data.
            if (source == Source.SERVER && _orders.value.isNullOrEmpty()) {
                _historyMessage.value = "Riwayat belum dapat diperbarui. Periksa koneksi lalu coba lagi."
            }
        }
    }

    private suspend fun loadActiveAndPoints(uid: String, source: Source) {
        try {
            val rideActive = firestore.collection("orders")
                .whereEqualTo("userId", uid)
                .whereIn("status", RIDE_ACTIVE_STATUSES.toList())
                .get(source)
                .await()
                .documents
                .mapNotNull(::activeRide)

            val foodActive = firestore.collection("merchant_orders")
                .whereEqualTo("customerId", uid)
                .whereIn("status", FOOD_ACTIVE_STATUSES.toList())
                .get(source)
                .await()
                .documents
                .mapNotNull(::activeFood)

            _activeOrders.value = (rideActive + foodActive).sortedByDescending { it.createdAt }
            val points = firestore.collection("users").document(uid).get(source).await().getLong("points") ?: 0L
            val previous = _stats.value ?: Triple(0, 0, 0L)
            _stats.value = Triple(previous.first, previous.second, points)
        } catch (_: Exception) {
            // Preserve cache-backed UI if server is temporarily unavailable.
        }
    }

    /** Counts every terminal history document; unlike the paged list this is never page-derived. */
    private suspend fun loadAllHistoryStats(uid: String) {
        try {
            val tripCount = firestore.collection("orders")
                .whereEqualTo("userId", uid)
                .whereIn("status", TERMINAL_RIDE_STATUSES.toList())
                .count()
                .get(AggregateSource.SERVER)
                .await()
                .count
                .toInt()
            val foodCount = firestore.collection("merchant_orders")
                .whereEqualTo("customerId", uid)
                .whereIn("status", TERMINAL_FOOD_STATUSES.toList())
                .count()
                .get(AggregateSource.SERVER)
                .await()
                .count
                .toInt()
            val previous = _stats.value ?: Triple(0, 0, 0L)
            _stats.value = Triple(tripCount, foodCount, previous.third)
        } catch (_: Exception) {
            // Do not substitute the page length for an unavailable all-history count.
        }
    }

    private fun historyRide(doc: DocumentSnapshot): Order? {
        val status = doc.getString("status") ?: return null
        if (status != "completed" && status != "cancelled") return null
        return Order(
            id = doc.id,
            userId = doc.getString("userId") ?: "",
            serviceType = doc.getString("serviceType") ?: "ride",
            destination = doc.getString("destination") ?: "",
            status = status,
            price = doc.longOr("price"),
            driverRating = doc.getLong("driverRating")?.toInt() ?: 0,
            createdAt = doc.getLong("createdAt") ?: 0L
        )
    }

    private fun historyFood(doc: DocumentSnapshot): Order? {
        val status = doc.getString("status") ?: return null
        if (status != "delivered" && status != "cancelled") return null
        return Order(
            id = doc.id,
            userId = doc.getString("customerId") ?: "",
            serviceType = "food",
            destination = doc.getString("merchantName") ?: "SuruhFood",
            status = if (status == "delivered") "completed" else "cancelled",
            price = doc.longOr("total") + doc.longOr("deliveryFee") + doc.longOr("adminFee"),
            createdAt = doc.getLong("createdAt") ?: 0L
        )
    }

    private fun activeRide(doc: DocumentSnapshot): Order? {
        val status = doc.getString("status") ?: return null
        return Order(
            id = doc.id,
            userId = doc.getString("userId") ?: "",
            serviceType = doc.getString("serviceType") ?: "ride",
            destination = doc.getString("destination") ?: "",
            pickup = doc.getString("pickup") ?: "",
            status = status,
            price = doc.longOr("price"),
            createdAt = doc.getLong("createdAt") ?: 0L
        )
    }

    private fun activeFood(doc: DocumentSnapshot): Order? {
        val status = doc.getString("status") ?: return null
        return Order(
            id = doc.id,
            userId = doc.getString("customerId") ?: "",
            serviceType = "food",
            destination = doc.getString("merchantName") ?: "SuruhFood",
            status = status,
            price = doc.longOr("total") + doc.longOr("deliveryFee") + doc.longOr("adminFee"),
            createdAt = doc.getLong("createdAt") ?: 0L
        )
    }

    private val _cancelMessage = MutableLiveData<String?>(null)
    val cancelMessage: LiveData<String?> = _cancelMessage

    fun cancelActiveFoodOrder(orderId: String) {
        viewModelScope.launch {
            val result = foodRepository.cancelFoodOrder(orderId)
            _cancelMessage.value = result.fold(
                onSuccess = { "Pesanan dibatalkan" },
                onFailure = { "Gagal membatalkan: ${it.message}" }
            )
            refresh()
        }
    }

    fun requestCancelActiveFoodOrder(orderId: String) {
        viewModelScope.launch {
            val result = foodRepository.requestCancelFoodOrder(orderId)
            _cancelMessage.value = result.fold(
                onSuccess = { "Permintaan pembatalan dikirim ke driver" },
                onFailure = { "Gagal: ${it.message}" }
            )
            refresh()
        }
    }

    companion object {
        private const val HISTORY_PAGE_SIZE = 15
        private val TERMINAL_RIDE_STATUSES = setOf("completed", "cancelled")
        private val TERMINAL_FOOD_STATUSES = setOf("delivered", "cancelled")
        private val RIDE_ACTIVE_STATUSES = setOf("pending", "accepted", "pickup", "delivering")
        private val FOOD_ACTIVE_STATUSES = setOf("pending", "seeking_driver", "accepted", "preparing", "ready", "delivering")
    }
}
