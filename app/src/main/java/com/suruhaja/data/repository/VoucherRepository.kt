package com.suruhaja.data.repository

import com.suruhaja.data.relay.Fungsi
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.suruhaja.BuildConfig
import com.suruhaja.data.model.Voucher
import com.suruhaja.data.model.VoucherCartItem
import com.suruhaja.data.model.VoucherClaim
import com.suruhaja.data.policy.VoucherPolicy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Akses voucher untuk customer.
 *
 * PENTING: koleksi `vouchers` (definisi + kode) TIDAK bisa dibaca client — kode
 * bersifat rahasia, dilindungi firestore.rules. App hanya membaca KLAIM miliknya
 * sendiri di `users/{uid}/voucher_claims`, yang sudah memuat snapshot aturan
 * voucher. Semua angka yang mengikat tetap dihitung server di fungsi quote dan
 * create.
 */
@Singleton
class VoucherRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val functions = Fungsi.getInstance("asia-southeast2")

    private fun claimCollection() = firestore.collection("users")
        .document(FirebaseAuth.getInstance().currentUser?.uid.orEmpty())
        .collection("voucher_claims")

    /** Daftar voucher milik user (realtime). */
    fun observeClaims(): Flow<List<VoucherClaim>> = callbackFlow {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrEmpty()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val listener = firestore.collection("users").document(uid).collection("voucher_claims")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(snapshot.documents.map { doc -> doc.toVoucherClaim() })
            }
        awaitClose { listener.remove() }
    }

    /** Baca sekali — dipakai pemilih voucher saat checkout. */
    suspend fun loadClaims(): List<VoucherClaim> {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrEmpty()) return emptyList()
        val snapshot = firestore.collection("users").document(uid)
            .collection("voucher_claims")
            .get()
            .await()
        return snapshot.documents.map { doc -> doc.toVoucherClaim() }
    }

    /**
     * Klaim kode voucher (banner / notifikasi / menu Tukar Kode).
     * Format, kuota, dan limit divalidasi SERVER (callable redeemVoucherCode).
     */
    suspend fun redeemCode(code: String, source: String = Voucher.SOURCE_CODE): VoucherClaim {
        val result = functions.getHttpsCallable("redeemVoucherCode").call(
            mapOf(
                "code" to code.trim().uppercase(),
                "source" to source,
                "appVersionCode" to BuildConfig.VERSION_CODE
            )
        ).await()
        val data = result.getData() as? Map<*, *> ?: error("Respons voucher tidak valid")
        return VoucherClaim(
            voucherId = data["voucherId"] as? String ?: "",
            code = data["code"] as? String ?: code.trim().uppercase(),
            title = data["title"] as? String ?: "",
            subtitle = data["subtitle"] as? String ?: "",
            source = source
        )
    }

    /**
     * Voucher milik user yang SAH untuk layanan/keranjang saat ini.
     * Penyaringan di sini hanya untuk UX — server tetap memvalidasi ulang saat
     * order dibuat, jadi voucher yang lolos di sini pun bisa ditolak server.
     */
    suspend fun usableClaims(
        service: String,
        spend: Long,
        merchantId: String = "",
        menuItemIds: List<String> = emptyList(),
        items: List<VoucherCartItem> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ): List<VoucherClaim> = loadClaims().filter { claim ->
        val voucher = claim.toVoucher()
        val basis = VoucherPolicy.discountBasis(voucher, items, spend)
        VoucherPolicy.check(
            voucher,
            VoucherPolicy.UsageContext(
                service = service,
                nowMs = nowMs,
                spend = basis,
                merchantId = merchantId,
                menuItemIds = menuItemIds,
                usedByUser = claim.useCount
            )
        ) == VoucherPolicy.Reject.OK
    }
}

/** Mapping manual (proyek ini tidak memakai toObject untuk dokumen domain). */
private fun DocumentSnapshot.toVoucherClaim(): VoucherClaim {
    @Suppress("UNCHECKED_CAST")
    val rules = get("rules") as? Map<String, Any?> ?: emptyMap()
    return VoucherClaim(
        voucherId = getString("voucherId") ?: id,
        code = getString("code") ?: "",
        title = getString("title") ?: "",
        subtitle = getString("subtitle") ?: "",
        status = getString("status") ?: Voucher.STATUS_CLAIMED,
        claimedAt = getLong("claimedAt") ?: 0L,
        usedAt = getLong("usedAt") ?: 0L,
        orderId = getString("orderId") ?: "",
        source = getString("source") ?: "",
        useCount = (getLong("useCount") ?: 0L).toInt(),
        rules = rules
    )
}
