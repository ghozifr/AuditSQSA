package com.suruhaja.ui.food

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.suruhaja.data.model.FoodOrder
import com.suruhaja.data.model.ReviewedMenuItem
import com.suruhaja.data.repository.FoodOrderStreamState
import com.suruhaja.data.repository.FoodRepository
import com.suruhaja.realtime.RealtimeStatePolicy
import com.suruhaja.realtime.RealtimeUiState
import com.suruhaja.util.ChatNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class FoodTrackingViewModel @Inject constructor(
    private val repository: FoodRepository,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    data class DriverInfo(
        val photoUrl: String = "",
        val vehicle: String = "",
        val plate: String = ""
    )

    private val orderId: String = savedStateHandle["orderId"] ?: ""

    private val orderStream: LiveData<FoodOrderStreamState> = repository.observeFoodOrderWithState(orderId).asLiveData()
    private val _order = MediatorLiveData<FoodOrder?>().apply {
        addSource(orderStream) { state -> if (state.error == null) value = state.order }
    }
    val order: LiveData<FoodOrder?> = _order
    private val _realtimeState = MediatorLiveData<RealtimeUiState<FoodOrder>>().apply {
        addSource(orderStream) { state ->
            value = if (state.error != null) RealtimeStatePolicy.failure(state.order, state.error)
            else RealtimeUiState(data = state.order, loading = false)
        }
    }
    val realtimeState: LiveData<RealtimeUiState<FoodOrder>> = _realtimeState

    private val _paymentMessage = MutableLiveData("")
    val paymentMessage: LiveData<String> = _paymentMessage

    private val _cancelMessage = MutableLiveData("")
    val cancelMessage: LiveData<String> = _cancelMessage

    /** Siapa yang meminta pembatalan ("" | "customer" | "driver"). */
    val cancelRequestedBy: LiveData<String> = order.map { it?.cancelRequestedBy ?: "" }

    /** Jumlah pesan driver yang belum dibaca (untuk badge tombol chat). */
    val unreadCount: LiveData<Long> = order.map { it?.unreadByCustomer ?: 0L }

    private val _balance = MutableLiveData(0L)
    val balance: LiveData<Long> = _balance

    /** Foto dan kendaraan driver dari profil drivers/{driverId}. */
    val driverInfo: LiveData<DriverInfo> = order.switchMap { o ->
        val did = o?.driverId ?: ""
        val result = MutableLiveData(DriverInfo())
        if (did.isNotEmpty()) {
            firestore.collection("drivers").document(did).get()
                .addOnSuccessListener { snap ->
                    if (snap.exists()) {
                        result.value = DriverInfo(
                            photoUrl = snap.getString("photoUrl") ?: "",
                            vehicle = snap.getString("vehicle") ?: "",
                            plate = snap.getString("plate") ?: ""
                        )
                    }
                }
        }
        result
    }

    /** Prompt pembayaran: saldo order, driver minta bayar, belum paid. */
    val paymentPrompt: LiveData<Boolean> = order.map {
        it != null && RealtimeStatePolicy.showPaymentPrompt(
            it.paymentMethod,
            it.awaitingPayment,
            it.paymentStatus,
            it.status == FoodOrder.STATUS_DELIVERED || it.status == FoodOrder.STATUS_CANCELLED
        )
    }

    private var balanceListener: ListenerRegistration? = null
    private var messageListener: ListenerRegistration? = null
    private var lastMsgAt = 0L

    init {
        observeBalance()
        observeMessages()
    }

    private fun observeBalance() {
        val uid = auth.currentUser?.uid ?: return
        balanceListener?.remove()
        balanceListener = firestore.collection("users").document(uid)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
                _balance.value = snap.getLong("balance") ?: 0L
            }
    }

    private fun observeMessages() {
        messageListener?.remove()
        val uid = auth.currentUser?.uid ?: return
        messageListener = firestore.collection("merchant_orders").document(orderId).collection("messages")
            .orderBy("sentAt", Query.Direction.ASCENDING)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                if (snap.metadata.isFromCache) return@addSnapshotListener
                val last = snap.documents.lastOrNull() ?: return@addSnapshotListener
                val senderId = last.getString("senderId") ?: ""
                val sentAt = last.getTimestamp("sentAt")?.toDate()?.time ?: 0L
                val prev = lastMsgAt
                lastMsgAt = maxOf(prev, sentAt)
                if (prev == 0L) return@addSnapshotListener   // snapshot baseline: jangan notif
                if (senderId == uid) return@addSnapshotListener
                if (sentAt <= prev) return@addSnapshotListener
                // Notifikasi chat ditangani OrderNotificationService (single source).
            }
    }

    fun payFromBalance() {
        val uid = auth.currentUser?.uid ?: ""
        viewModelScope.launch {
            val result = runCatching {
                withTimeout(15_000) { repository.payFoodFromBalance(orderId, uid).getOrThrow() }
            }
            _paymentMessage.value = result.fold(
                onSuccess = { "Pembayaran berhasil ✅" },
                onFailure = { "Gagal bayar: ${it.message}" }
            )
        }
    }

    /** Pilih bayar via QRIS → driver yang tampilkan QR. */
    fun requestQrisPayment() {
        viewModelScope.launch {
            val result = runCatching {
                withTimeout(15_000) {
                    firestore.collection("merchant_orders").document(orderId)
                        .update("paymentRequest", "qris").await()
                }
            }
            _paymentMessage.value = result.fold({ "Permintaan QRIS dikirim. Scan QR di HP driver untuk bayar." }, { "Gagal meminta QRIS: ${it.message}" })
        }
    }

    fun refreshFromServer() {
        viewModelScope.launch {
            runCatching { withTimeout(10_000) { repository.refreshFoodOrder(orderId).getOrThrow() } }
                .onSuccess { refreshed ->
                    _order.value = refreshed
                    _realtimeState.value = RealtimeUiState(data = refreshed, loading = false)
                }
                .onFailure {
                    val message = "Gagal menyegarkan status: ${it.message ?: "koneksi gagal"}"
                    _realtimeState.value = RealtimeStatePolicy.failure(_order.value, message)
                    _paymentMessage.value = message
                }
        }
    }

    /** Batalkan langsung (pending/seeking_driver — belum ada driver). */
    fun cancelDirectly() {
        viewModelScope.launch {
            val r = repository.cancelFoodOrder(orderId)
            _cancelMessage.value = r.fold(
                onSuccess = { "Pesanan dibatalkan" },
                onFailure = { "Gagal membatalkan: ${it.message}" }
            )
        }
    }

    /** Minta pembatalan ke driver (mutual — driver harus setujui). */
    fun requestCancel() {
        viewModelScope.launch {
            val r = repository.requestCancelFoodOrder(orderId)
            _cancelMessage.value = r.fold(
                onSuccess = { "Permintaan pembatalan dikirim ke driver" },
                onFailure = { "Gagal: ${it.message}" }
            )
        }
    }

    private val _ratingSubmitted = MutableLiveData<Boolean?>(null)
    val ratingSubmitted: LiveData<Boolean?> = _ratingSubmitted

    private val _ratingError = MutableLiveData<String?>(null)
    val ratingError: LiveData<String?> = _ratingError

    /** Simpan rating toko + driver + menu (1-5, default 5, komentar opsional). */
    fun submitReview(
        merchantRating: Int,
        driverRating: Int,
        merchantComment: String,
        driverComment: String,
        reviewedItems: List<ReviewedMenuItem>
    ) {
        val o = order.value ?: return
        val uid = auth.currentUser?.uid ?: ""
        viewModelScope.launch {
            val result = repository.submitFoodReview(
                orderId = orderId,
                customerId = uid,
                // Nama penyamar diambil dari snapshot order (users/{uid} hanya
                // bisa dibaca pemiliknya, sementara ulasan dibaca semua orang).
                customerName = o.customerName,
                merchantId = o.merchantId,
                driverId = o.driverId,
                merchantName = o.merchantName,
                driverName = o.driverName,
                merchantRating = merchantRating,
                driverRating = driverRating,
                merchantComment = merchantComment,
                driverComment = driverComment,
                reviewedItems = reviewedItems
            )
            result.fold(
                onSuccess = { _ratingSubmitted.value = true },
                onFailure = {
                    _ratingSubmitted.value = false
                    _ratingError.value = it.message ?: "error tidak diketahui"
                }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        balanceListener?.remove()
        messageListener?.remove()
    }
}
