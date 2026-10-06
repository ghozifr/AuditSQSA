package com.suruhaja.realtime

data class RealtimeUiState<T>(
    val data: T? = null,
    val loading: Boolean = data == null,
    val reconnecting: Boolean = false,
    val error: String? = null,
    val canRetry: Boolean = error != null
)

/**
 * Keputusan tampilan pembayaran pada satu snapshot order.
 *
 * `buttonVisible` sengaja TIDAK dipengaruhi [alreadyPrompted]: kalau customer
 * menutup dialog (tombol back/swipe) sementara driver sudah minta bayar, dialog
 * tidak boleh muncul otomatis lagi — tapi tombol "Bayar sekarang" harus tetap
 * ada supaya customer bisa membuka ulang dan tidak stuck.
 */
data class PaymentPromptDecision(
    val autoShow: Boolean,
    val markPrompted: Boolean,
    val buttonVisible: Boolean
)

object RealtimeStatePolicy {
    fun <T> accept(previous: T?, value: T, fromCache: Boolean): RealtimeUiState<T> =
        if (fromCache) RealtimeUiState(previous, loading = previous == null, reconnecting = true, error = null, canRetry = true)
        else RealtimeUiState(value, loading = false, reconnecting = false, error = null, canRetry = false)

    fun <T> failure(previous: T?, message: String): RealtimeUiState<T> =
        RealtimeUiState(previous, loading = false, reconnecting = true, error = message, canRetry = true)

    /**
     * Pembayaran masih tertunda: driver sudah minta bayar ([awaiting]),
     * order memakai saldo (metode yang bisa dibayar dari app), belum lunas,
     * dan order belum selesai/dibatalkan.
     */
    fun paymentPending(method: String, awaiting: Boolean, paymentStatus: String, terminal: Boolean): Boolean =
        !terminal && method == "saldo" && awaiting && paymentStatus != "paid"

    /** Nama lama; aturan sesungguhnya ada di [paymentPending]. */
    fun showPaymentPrompt(method: String, awaiting: Boolean, paymentStatus: String, terminal: Boolean): Boolean =
        paymentPending(method, awaiting, paymentStatus, terminal)

    fun paymentPromptDecision(pending: Boolean, alreadyPrompted: Boolean): PaymentPromptDecision =
        PaymentPromptDecision(
            autoShow = pending && !alreadyPrompted,
            // Selama permintaan masih tertunda, dialog dianggap sudah ditampilkan
            // (jangan popup berulang tiap snapshot). Reset hanya saat pending
            // berhenti — lihat test "new request episode".
            markPrompted = pending,
            buttonVisible = pending
        )
}
