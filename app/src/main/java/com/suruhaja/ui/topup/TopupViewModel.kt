package com.suruhaja.ui.topup

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.suruhaja.data.repository.TopupRepository
import com.suruhaja.util.QrisUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TopupViewModel @Inject constructor(
    private val repo: TopupRepository,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val _amountInput = MutableLiveData("")
    val amountInput: LiveData<String> = _amountInput

    private val _balance = MutableLiveData<Long?>(null)
    val balance: LiveData<Long?> = _balance

    private val _qrContent = MutableLiveData<String?>(null)
    val qrContent: LiveData<String?> = _qrContent

    private val _payAmount = MutableLiveData(0L)
    val payAmount: LiveData<Long> = _payAmount

    private val _status = MutableLiveData(STATUS_IDLE)
    val status: LiveData<String> = _status

    private val _message = MutableLiveData("")
    val message: LiveData<String> = _message

    private val _countdown = MutableLiveData("")
    val countdown: LiveData<String> = _countdown

    private var statusJob: Job? = null
    private var countdownJob: Job? = null

    init {
        val uid = auth.currentUser?.uid
        if (!uid.isNullOrEmpty()) {
            viewModelScope.launch {
                repo.observeBalance(uid).collect { _balance.value = it }
            }
        }
    }

    fun onAmountChanged(text: String) {
        _amountInput.value = text
    }

    fun generateQr() {
        val base = _amountInput.value?.trim()?.toLongOrNull() ?: 0L
        if (base <= 0) {
            _message.value = "Masukkan nominal yang valid"
            return
        }
        val uid = auth.currentUser?.uid ?: return
        _status.value = STATUS_PROCESSING
        viewModelScope.launch {
            val topup = repo.createTopup(uid, base)
            if (topup == null) {
                _status.value = STATUS_ERROR
                _message.value = "Gagal membuat top-up. Coba lagi."
                return@launch
            }
            _payAmount.value = topup.amount
            // Merchant QRIS statis TIDAK support dynamic (method 12) — kalau diubah ke
            // method 12, GoPay proses sebagai transaksi dinamis dan notifikasi "uang masuk"
            // gak muncul (PayHook jadi gak bisa detect). Jadi pakai QR STATIS,
            // customer ketik nominal unik secara manual di GoPay.
            _qrContent.value = QrisUtil.STATIC_QRIS
            _status.value = STATUS_PENDING
            _message.value = "Scan QR & masukkan nominal Rp ${format(topup.amount)} secara manual di GoPay"
            startCountdown(topup.expiresAt)
            observeStatus(topup.id)
        }
    }

    private fun observeStatus(topupId: String) {
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            repo.observeTopup(topupId).collect { topup ->
                when (topup?.status) {
                    TopupRepository.STATUS_CONFIRMED -> {
                        _status.value = STATUS_CONFIRMED
                        _message.value = "Top-up berhasil! Saldo bertambah Rp ${format(topup.baseAmount)}"
                    }
                    TopupRepository.STATUS_EXPIRED -> {
                        _status.value = STATUS_EXPIRED
                        _message.value = "Waktu habis. Silakan buat ulang QR."
                    }
                }
            }
        }
    }

    /** Countdown local sampai `expiresAt`; saat habis → expired (tanpa menunggu backend). */
    private fun startCountdown(expiresAt: Long) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (_status.value == STATUS_PENDING) {
                val remaining = expiresAt - System.currentTimeMillis()
                if (remaining <= 0L) {
                    _countdown.value = "QR kedaluwarsa"
                    _status.value = STATUS_EXPIRED
                    _message.value = "Waktu habis. Silakan buat ulang QR."
                    break
                }
                _countdown.value = "Berlaku ${formatCountdown(remaining)}"
                delay(1000)
            }
        }
    }

    private fun formatCountdown(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun format(n: Long): String = "%,d".format(n)

    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_PROCESSING = "processing"
        const val STATUS_PENDING = "pending"
        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_EXPIRED = "expired"
        const val STATUS_ERROR = "error"
    }
}
