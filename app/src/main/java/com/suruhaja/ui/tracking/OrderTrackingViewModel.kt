package com.suruhaja.ui.tracking

import com.suruhaja.data.relay.Fungsi
import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.suruhaja.data.model.Order
import com.suruhaja.data.policy.OrderMoneyPolicy
import com.suruhaja.data.policy.PriceBreakdown
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
class OrderTrackingViewModel @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _status = MutableLiveData("pending")
    val status: LiveData<String> = _status

    private val _price = MutableLiveData("Rp 0")
    val price: LiveData<String> = _price

    private val _distance = MutableLiveData("—")
    val distance: LiveData<String> = _distance

    private val _detail = MutableLiveData("Mohon tunggu...")
    val detail: LiveData<String> = _detail

    private val _driverName = MutableLiveData("")
    val driverName: LiveData<String> = _driverName

    private val _driverVehicle = MutableLiveData("")
    val driverVehicle: LiveData<String> = _driverVehicle

    private val _driverPhone = MutableLiveData("")
    val driverPhone: LiveData<String> = _driverPhone

    private val _driverPhoto = MutableLiveData("")
    val driverPhoto: LiveData<String> = _driverPhoto

    private val _driverVisible = MutableLiveData(false)
    val driverVisible: LiveData<Boolean> = _driverVisible

    private val _canCancel = MutableLiveData(true)
    val canCancel: LiveData<Boolean> = _canCancel

    private val _isCompleted = MutableLiveData(false)
    val isCompleted: LiveData<Boolean> = _isCompleted

    private val _needsPayment = MutableLiveData(false)
    val needsPayment: LiveData<Boolean> = _needsPayment

    private val _amountDue = MutableLiveData(0L)
    val amountDue: LiveData<Long> = _amountDue

    private val _paymentMessage = MutableLiveData<String?>(null)
    val paymentMessage: LiveData<String?> = _paymentMessage

    private val _balance = MutableLiveData(0L)
    val balance: LiveData<Long> = _balance

    private val _paymentPrompt = MutableLiveData(false)
    val paymentPrompt: LiveData<Boolean> = _paymentPrompt

    private val _realtimeState = MutableLiveData(RealtimeStatePolicy.accept<Order>(null, Order(), true))
    val realtimeState: LiveData<RealtimeUiState<Order>> = _realtimeState

    private val _fareAmount = MutableLiveData(0L)
    val fareAmount: LiveData<Long> = _fareAmount

    /** Rincian harga order berjalan (asli, potongan voucher, dibayar). */
    private var _tripMoney = PriceBreakdown(original = 0L, discount = 0L, payable = 0L)
    val tripMoney: PriceBreakdown get() = _tripMoney

    private val _cancelledBy = MutableLiveData("")
    val cancelledBy: LiveData<String> = _cancelledBy

    /** "ride" atau "send" — dipakai untuk memilih aksen warna (Send = hijau). */
    private val _serviceType = MutableLiveData("ride")
    val serviceType: LiveData<String> = _serviceType

    private val _cancelRequestedBy = MutableLiveData("")
    val cancelRequestedBy: LiveData<String> = _cancelRequestedBy

    // True when the OTHER party (driver) has requested cancellation → show approve/reject
    private val _pendingCancellation = MutableLiveData(false)
    val pendingCancellation: LiveData<Boolean> = _pendingCancellation

    private val _pickupLatLng = MutableLiveData<Pair<Double, Double>?>()
    val pickupLatLng: LiveData<Pair<Double, Double>?> = _pickupLatLng

    private val _destLatLng = MutableLiveData<Pair<Double, Double>?>()
    val destLatLng: LiveData<Pair<Double, Double>?> = _destLatLng

    private val _driverLatLng = MutableLiveData(Pair(0.0, 0.0))
    val driverLatLng: LiveData<Pair<Double, Double>> = _driverLatLng

    private val _unreadCount = MutableLiveData(0L)
    val unreadCount: LiveData<Long> = _unreadCount

    private var orderId: String = ""
    private var listener: ListenerRegistration? = null
    private var balanceListener: ListenerRegistration? = null
    private var settling = false
    private var messageListener: ListenerRegistration? = null
    private var lastMsgAt = 0L
    private var lastServerOrder: Order? = null
    private val myUid get() = auth.currentUser?.uid ?: ""

    fun loadOrder(orderId: String) {
        if (orderId.isEmpty()) return
        this.orderId = orderId
        observeBalance()
        observeMessages()

        // Remove previous listener
        listener?.remove()

        listener = firestore.collection("orders").document(orderId)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null) { _realtimeState.postValue(RealtimeStatePolicy.failure(lastServerOrder, err.message ?: "Koneksi gagal")); return@addSnapshotListener }
                if (snap == null || !snap.exists()) return@addSnapshotListener
                try {
                    val order = snap.toObject(Order::class.java)?.copy(id = snap.id)
                        ?: return@addSnapshotListener
                    if (snap.metadata.isFromCache) {
                        _realtimeState.postValue(RealtimeStatePolicy.accept(lastServerOrder, order, true))
                        return@addSnapshotListener
                    }
                    lastServerOrder = order
                    _realtimeState.postValue(RealtimeStatePolicy.accept(lastServerOrder, order, false))
                    _status.value = order.status
                    _unreadCount.value = snap.getLong("unreadByCustomer") ?: 0L
                    // Harga yang ditampilkan = yang ditagih server (setelah
                    // potongan voucher), bukan harga kotor.
                    _tripMoney = OrderMoneyPolicy.trip(order.price, order.voucherDiscount)
                    _price.value = OrderMoneyPolicy.rupiah(_tripMoney.payable)
                    _distance.value = if (order.durationMin > 0)
                        "%.1f km · ±%d mnt".format(order.distanceKm, order.durationMin)
                    else "%.1f km".format(order.distanceKm)
                    _detail.value = "${order.pickup} → ${order.destination}"
                    _pickupLatLng.value = Pair(order.pickupLat, order.pickupLng)
                    _destLatLng.value = Pair(order.destLat, order.destLng)

                    _canCancel.value = order.status == "pending" || order.status == "accepted" || order.status == "pickup" || order.status == "delivering"
                    _isCompleted.value = order.status == "completed" || order.status == "cancelled"
                    val amountDue = snap.getLong("amountDue") ?: 0L
                    val paymentStatus = snap.getString("paymentStatus") ?: "unpaid"
                    _amountDue.value = amountDue
                    _needsPayment.value = order.status == "completed" && paymentStatus == "unpaid" && amountDue > 0
                    // Prompt pembayaran (saldo order): tampil kalau driver minta bayar & belum paid.
                    val paymentMethod = snap.getString("paymentMethod") ?: "cash"
                    val awaitingPayment = snap.getBoolean("awaitingPayment") ?: false
                    _fareAmount.value = _tripMoney.payable
                    _paymentPrompt.value = RealtimeStatePolicy.showPaymentPrompt(
                        paymentMethod, awaitingPayment, paymentStatus,
                        order.status == "completed" || order.status == "cancelled"
                    )
                    _cancelledBy.value = snap.getString("cancelledBy") ?: ""
                    _serviceType.value = order.serviceType.ifEmpty { "ride" }
                    val reqBy = snap.getString("cancelRequestedBy") ?: ""
                    _cancelRequestedBy.value = reqBy
                    // Show approve/reject only if the DRIVER requested cancellation
                    _pendingCancellation.value = reqBy == "driver"

                    if (order.status == "accepted" || order.status == "pickup" || order.status == "delivering") {
                        _driverVisible.value = true
                        if (order.driverId.isNotEmpty()) {
                            viewModelScope.launch {
                                try {
                                    val driverDoc = firestore.collection("drivers")
                                        .document(order.driverId).get().await()
                                    _driverName.value = driverDoc.getString("name") ?: "Driver"
                                    _driverVehicle.value = "${driverDoc.getString("vehicle") ?: ""} · ${driverDoc.getString("plate") ?: ""}"
                                    _driverPhone.value = driverDoc.getString("phone") ?: ""
                                    _driverPhoto.value = driverDoc.getString("photoUrl") ?: ""
                                } catch (_: Exception) {}
                            }
                        }
                        _driverLatLng.value = Pair(
                            snap.getDouble("driverLat") ?: 0.0,
                            snap.getDouble("driverLng") ?: 0.0
                        )
                    }

                    if (order.status == "completed") {
                        _driverVisible.value = false
                    }
                } catch (e: Exception) {
                    Log.e("TrackingVM", "parse", e)
                }
            }
    }

    fun cancelOrder() {
        if (orderId.isEmpty()) return
        viewModelScope.launch {
            try {
                firestore.collection("orders").document(orderId)
                    .update("status", "cancelled", "cancelledBy", "customer", "updatedAt", System.currentTimeMillis())
                    .await()
            } catch (_: Exception) {}
        }
    }

    fun refreshFromServer() {
        if (orderId.isEmpty()) return
        viewModelScope.launch {
            try {
                val snap = withTimeout(10_000) {
                    firestore.collection("orders").document(orderId).get(Source.SERVER).await()
                }
                val order = snap.toObject(Order::class.java)?.copy(id = snap.id)
                if (order != null) {
                    lastServerOrder = order
                    _status.value = order.status
                    _serviceType.value = order.serviceType.ifEmpty { "ride" }
                    // Rekonsiliasi manual juga harus memakai harga setelah
                    // potongan voucher, sama seperti listener order.
                    _tripMoney = OrderMoneyPolicy.trip(order.price, order.voucherDiscount)
                    _price.value = OrderMoneyPolicy.rupiah(_tripMoney.payable)
                    _fareAmount.value = _tripMoney.payable
                    _paymentPrompt.value = RealtimeStatePolicy.showPaymentPrompt(
                        order.paymentMethod,
                        snap.getBoolean("awaitingPayment") ?: false,
                        snap.getString("paymentStatus") ?: "unpaid",
                        order.status == "completed" || order.status == "cancelled"
                    )
                    _realtimeState.value = RealtimeStatePolicy.accept(lastServerOrder, order, false)
                }
            } catch (e: Exception) {
                _realtimeState.value = RealtimeStatePolicy.failure(lastServerOrder, e.message ?: "Koneksi gagal")
            }
        }
    }

    /** Request cancellation (mutual) — driver must approve */
    fun requestCancel() {
        if (orderId.isEmpty()) return
        viewModelScope.launch {
            try {
                firestore.collection("orders").document(orderId)
                    .update("cancelRequestedBy", "customer").await()
            } catch (_: Exception) {}
        }
    }

    /** Approve the other party's cancellation request → actually cancel order */
    fun approveCancel() {
        if (orderId.isEmpty()) return
        val requester = _cancelRequestedBy.value ?: "driver"
        viewModelScope.launch {
            try {
                firestore.collection("orders").document(orderId)
                    .update("status", "cancelled", "cancelledBy", requester,
                        "cancelRequestedBy", "", "updatedAt", System.currentTimeMillis())
                    .await()
            } catch (_: Exception) {}
        }
    }

    /** Reject the other party's cancellation request */
    fun rejectCancel() {
        if (orderId.isEmpty()) return
        viewModelScope.launch {
            try {
                firestore.collection("orders").document(orderId)
                    .update("cancelRequestedBy", "").await()
            } catch (_: Exception) {}
        }
    }

    /** Submit driver rating after order completion (1-5 only). Also updates the driver's aggregate rating. */
    fun submitRating(rating: Int) {
        if (orderId.isEmpty()) return
        val r = rating.coerceIn(1, 5)
        viewModelScope.launch {
            try {
                val orderRef = firestore.collection("orders").document(orderId)
                firestore.runTransaction { txn ->
                    val orderSnap = txn.get(orderRef)
                    // Guard: only rate once per order
                    if ((orderSnap.getLong("driverRating") ?: 0L) > 0) return@runTransaction
                    val driverId = orderSnap.getString("driverId") ?: ""
                    if (driverId.isEmpty()) return@runTransaction

                    val driverRef = firestore.collection("drivers").document(driverId)
                    val driverSnap = txn.get(driverRef)
                    val current = driverSnap.getDouble("rating") ?: 0.0
                    val count = driverSnap.getLong("ratingCount") ?: 0L
                    val newCount = count + 1
                    val newRating = (current * count + r) / newCount

                    txn.update(orderRef, "driverRating", r)
                    txn.set(driverRef,
                        mapOf("rating" to newRating, "ratingCount" to newCount),
                        SetOptions.merge())
                }.await()
            } catch (e: Exception) {
                Log.e("TrackingVM", "submitRating", e)
            }
        }
    }

    /** Bayar pakai saldo customer via Cloud Function (split server-side). */
    fun payFromBalance() {
        if (orderId.isEmpty()) return
        viewModelScope.launch {
            try {
                withTimeout(15_000) {
                    Fungsi.getInstance("asia-southeast2").getHttpsCallable("payOrder")
                        .call(mapOf("orderId" to orderId, "appVersionCode" to com.suruhaja.BuildConfig.VERSION_CODE)).await()
                }
                _paymentMessage.value = "Pembayaran lunas dari saldo!"
            } catch (e: Exception) {
                _paymentMessage.value = "Pembayaran gagal: ${e.message ?: "saldo tidak cukup"}"
            } finally {
                settling = false
            }
        }
    }

    /** Pilih bayar via QRIS → driver yang tampilkan QR. */
    fun requestQrisPayment() {
        if (orderId.isEmpty()) return
        viewModelScope.launch {
            val result = runCatching {
                withTimeout(15_000) {
                    firestore.collection("orders").document(orderId)
                        .update("paymentRequest", "qris").await()
                }
            }
            _paymentMessage.value = result.fold({ "Permintaan QRIS dikirim. Scan QR di HP driver untuk bayar." }, { "Gagal meminta QRIS: ${it.message}" })
        }
    }

    /** Pantau saldo customer (untuk cek cukup/tidak di prompt pembayaran). */
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
        if (myUid.isEmpty()) return
        messageListener = firestore.collection("orders").document(orderId).collection("messages")
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
                if (senderId == myUid) return@addSnapshotListener
                if (sentAt <= prev) return@addSnapshotListener
                // Notifikasi chat ditangani OrderNotificationService (single source).
            }
    }

    override fun onCleared() {
        super.onCleared()
        listener?.remove()
        balanceListener?.remove()
        messageListener?.remove()
    }
}
