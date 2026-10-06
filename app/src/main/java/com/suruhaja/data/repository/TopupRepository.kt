package com.suruhaja.data.repository

import com.suruhaja.data.relay.Fungsi
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.functions.FirebaseFunctions
import com.suruhaja.data.model.Topup
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository top-up customer. Menulis dokumen pending ke koleksi `topups`,
 * lalu backend PayHook (webhook Vercel) yang mencocokkan nominal unik dan
 * mengkredit `users/{uid}.balance` saat pembayaran terdeteksi.
 */
@Singleton
class TopupRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val topupsRef = firestore.collection("topups")
    private val usersRef = firestore.collection("users")

    companion object {
        const val EXPIRY_MS = 15 * 60 * 1000L
        const val STATUS_PENDING = "pending"
        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_EXPIRED = "expired"
    }

    /**
     * Buat topup pending dengan nominal unik (base + 1..99) supaya bisa dicocokkan
     * secara pasti oleh webhook berdasarkan nominal. Return dokumen Topup.
     */
    suspend fun createTopup(userId: String, baseAmount: Long): Topup? {
        if (userId.isEmpty() || baseAmount <= 0) return null
        return try {
            // Nominal unik dibuat server-side (callable) — klien tidak boleh baca
            // topups orang lain, jadi clash-check klien selalu ditolak rules.
            val result = Fungsi.getInstance("asia-southeast2").getHttpsCallable("createBalanceTopup")
                .call(mapOf("baseAmount" to baseAmount, "role" to "customer")).await()
            val data = result.getData() as? Map<*, *> ?: return null
            val topupId = data["topupId"] as? String ?: return null
            val amount = (data["amount"] as? Number)?.toLong() ?: return null
            val expiresAt = (data["expiresAt"] as? Number)?.toLong() ?: return null
            if (topupId.isBlank() || amount <= baseAmount || expiresAt <= 0L) return null
            Topup(
                id = topupId,
                userId = userId,
                role = "customer",
                baseAmount = baseAmount,
                amount = amount,
                status = STATUS_PENDING,
                createdAt = System.currentTimeMillis(),
                expiresAt = expiresAt
            )
        } catch (e: Exception) {
            android.util.Log.e("TopupRepo", "createTopup", e)
            null
        }
    }

    /** Pantau status topup (pending → confirmed/expired) secara real-time. */
    fun observeTopup(topupId: String): Flow<Topup?> = callbackFlow {
        if (topupId.isEmpty()) { trySend(null); awaitClose {}; return@callbackFlow }
        val listener = topupsRef.document(topupId)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null) { trySend(null); return@addSnapshotListener }
                if (snap == null || !snap.exists()) { trySend(null); return@addSnapshotListener }
                if (snap.metadata.isFromCache) return@addSnapshotListener
                trySend(
                Topup(
                    id = snap.id,
                    userId = snap.getString("userId") ?: "",
                    role = snap.getString("role") ?: "customer",
                    baseAmount = snap.getLong("baseAmount") ?: 0L,
                    amount = snap.getLong("amount") ?: 0L,
                    status = snap.getString("status") ?: STATUS_PENDING,
                    createdAt = snap.getLong("createdAt") ?: 0L,
                    expiresAt = snap.getLong("expiresAt") ?: 0L,
                    eventId = snap.getString("eventId") ?: "",
                    source = snap.getString("source") ?: ""
                )
            )
        }
        awaitClose { listener.remove() }
    }

    /** Pantau saldo customer (users/{uid}.balance) secara real-time. */
    fun observeBalance(userId: String): Flow<Long> = callbackFlow {
        if (userId.isEmpty()) { trySend(0L); awaitClose {}; return@callbackFlow }
        val listener = usersRef.document(userId).addSnapshotListener { snap, err ->
            if (err != null) { trySend(0L); return@addSnapshotListener }
            trySend(snap?.getLong("balance") ?: 0L)
        }
        awaitClose { listener.remove() }
    }
}
