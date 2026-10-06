package com.suruhaja.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.suruhaja.MainActivity
import com.suruhaja.R

/**
 * Foreground service yang memberi notifikasi ke customer pada setiap fase order
 * (ride/send + food), walau aplikasi ditutup.
 *
 * - Dipantau via Firestore snapshot listener (bukan FCM), konsisten dgn driver/toko.
 * - Fase yang dinotifikasi:
 *     ride/send: accepted → pickup → completed (+ cancelled)
 *     food:      pending → accepted → ready → delivering → delivered (+ cancelled)
 * - Audio default Android (DEFAULT_ALL).
 * - Berhenti sendiri ketika tidak ada order aktif lagi.
 */
class OrderNotificationService : Service() {

    companion object {
        private const val TAG = "OrderNotifService"
        private const val CHANNEL_ID = "suruhaja_order_status"
        private const val NOTIF_ID = 2001

        fun start(context: Context) {
            val i = Intent(context, OrderNotificationService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                // Android 12+ ForegroundServiceStartNotAllowedException saat app
                // di background (mis. order dibuat dari notif/restore). Aman-skip.
                Log.w(TAG, "Cannot start notification service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OrderNotificationService::class.java))
        }
    }

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val uid: String get() = auth.currentUser?.uid ?: ""

    private var rideListener: ListenerRegistration? = null
    private var foodListener: ListenerRegistration? = null
    private val lastRideStatus = mutableMapOf<String, String>()
    private val lastFoodStatus = mutableMapOf<String, String>()

    // Chat: pantau pesan masuk di order aktif customer (ride/send + food).
    private val messageListeners = mutableMapOf<String, ListenerRegistration>()
    private val lastMsgAt = mutableMapOf<String, Long>()

    @Volatile private var hasActiveRide = false
    @Volatile private var hasActiveFood = false

    // Snapshot pertama (server) tiap listener sudah diterima. maybeStop() tidak boleh
    // stop service sebelum KEDUA listener selesai init — kalau tidak, saat customer
    // hanya punya food order (tanpa ride aktif), listener ride yang duluan callback
    // memanggil maybeStop() dan membunuh service sebelum listener food sempat pasang.
    @Volatile private var rideReady = false
    @Volatile private var foodReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildForegroundNotification())
        val u = uid
        if (u.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        observeRide(u)
        observeFood(u)
        return START_STICKY
    }

    private fun observeRide(u: String) {
        rideListener?.remove()
        rideListener = firestore.collection("orders")
            .whereEqualTo("userId", u)
            .whereIn("status", listOf("pending", "accepted", "pickup", "delivering", "completed", "cancelled"))
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null || snap == null) { rideReady = true; return@addSnapshotListener }
                if (snap.metadata.isFromCache) return@addSnapshotListener // server-only
                rideReady = true
                var active = false
                val activeKeys = mutableSetOf<String>()
                for (doc in snap.documents) {
                    val status = doc.getString("status") ?: "pending"
                    val id = doc.id
                    val prev = lastRideStatus[id]
                    if (prev != null && prev != status) notifyRideStatus(id, status)
                    lastRideStatus[id] = status
                    if (status == "pending" || status == "accepted" || status == "pickup" || status == "delivering") {
                        active = true
                        val key = "orders/$id"
                        activeKeys.add(key)
                        attachMessageListener(key, "orders", id, "Driver")
                    }
                }
                pruneMessageListeners("orders", activeKeys)
                hasActiveRide = active
                maybeStop()
            }
    }

    private fun observeFood(u: String) {
        foodListener?.remove()
        foodListener = firestore.collection("merchant_orders")
            .whereEqualTo("customerId", u)
            .whereIn("status", listOf("pending", "seeking_driver", "accepted", "preparing", "ready", "delivering", "delivered", "cancelled"))
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null || snap == null) { foodReady = true; return@addSnapshotListener }
                if (snap.metadata.isFromCache) return@addSnapshotListener
                foodReady = true
                var active = false
                val activeKeys = mutableSetOf<String>()
                for (doc in snap.documents) {
                    val status = doc.getString("status") ?: "pending"
                    val id = doc.id
                    val prev = lastFoodStatus[id]
                    if (prev != null && prev != status) notifyFoodStatus(id, status)
                    lastFoodStatus[id] = status
                    if (status != "delivered" && status != "cancelled") {
                        active = true
                        val key = "merchant_orders/$id"
                        activeKeys.add(key)
                        attachMessageListener(key, "merchant_orders", id, "Driver")
                    }
                }
                pruneMessageListeners("merchant_orders", activeKeys)
                hasActiveFood = active
                maybeStop()
            }
    }

    private fun maybeStop() {
        if (rideReady && foodReady && !hasActiveRide && !hasActiveFood) {
            Log.i(TAG, "Tidak ada order aktif — stop self")
            stopSelf()
        }
    }

    private fun notifyRideStatus(id: String, status: String) {
        val msg = when (status) {
            "accepted" -> "Driver menuju titik penjemputan Anda"
            "pickup" -> "Driver sudah berada di titik penjemputan"
            "delivering" -> "Driver sedang mengantar Anda"
            "completed" -> "Perjalanan selesai — sampai tujuan \uD83C\uDF89"
            "cancelled" -> "Pesanan dibatalkan"
            else -> return
        }
        postNotification(id, msg)
    }

    private fun notifyFoodStatus(id: String, status: String) {
        val msg = when (status) {
            "accepted" -> "Driver ditemukan — menunggu konfirmasi toko"
            "ready" -> "Driver sudah di toko — pesanan siap diambil"
            "delivering" -> "Driver sedang mengantar pesanan Anda"
            "delivered" -> "Pesanan sampai tujuan \uD83C\uDF89"
            "cancelled" -> "Pesanan dibatalkan"
            else -> return
        }
        postNotification(id, msg)
    }

    /** Pasang listener subcollection messages untuk satu order aktif (kalau belum ada). */
    private fun attachMessageListener(key: String, collection: String, orderId: String, peerName: String) {
        if (messageListeners.containsKey(key)) return
        val reg = firestore.collection(collection).document(orderId).collection("messages")
            .orderBy("sentAt", Query.Direction.ASCENDING)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                if (snap.metadata.isFromCache) return@addSnapshotListener
                val last = snap.documents.lastOrNull() ?: return@addSnapshotListener
                val senderId = last.getString("senderId") ?: ""
                val sentAt = last.getTimestamp("sentAt")?.toDate()?.time ?: 0L
                val prev = lastMsgAt[key]
                lastMsgAt[key] = maxOf(prev ?: 0L, sentAt)
                if (prev == null) return@addSnapshotListener   // snapshot baseline: jangan notif
                if (senderId == uid) return@addSnapshotListener  // pesan sendiri
                if (sentAt <= prev) return@addSnapshotListener
                val text = last.getString("text") ?: ""
                postChatNotification(orderId, "Pesan baru dari $peerName", text.ifEmpty { "…" })
            }
        messageListeners[key] = reg
    }

    /** Lepas listener pesan untuk order yang sudah tidak aktif. */
    private fun pruneMessageListeners(collection: String, activeKeys: Set<String>) {
        val prefix = "$collection/"
        messageListeners.keys.filter { it.startsWith(prefix) && it !in activeKeys }
            .forEach { key ->
                messageListeners.remove(key)?.remove()
                lastMsgAt.remove(key)
            }
    }

    private fun postChatNotification(orderId: String, title: String, text: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("orderId", orderId)
        }
        val requestCode = ("chat$orderId").hashCode() and 0x7fffffff
        val pi = PendingIntent.getActivity(
            this, requestCode, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(requestCode, notif)
        } catch (e: SecurityException) {
            Log.w(TAG, "Izin notifikasi belum diberikan", e)
        }
    }

    private fun postNotification(orderId: String, text: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("orderId", orderId)
        }
        val requestCode = orderId.hashCode() and 0x7fffffff
        val pi = PendingIntent.getActivity(
            this, requestCode, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Suruhaja")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL) // audio default Android
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(requestCode, notif)
        } catch (e: SecurityException) {
            Log.w(TAG, "Izin notifikasi belum diberikan", e)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Suruhaja")
            .setContentText("Memantau status pesanan Anda")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Status Pesanan", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifikasi perubahan status pesanan Anda"
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        rideListener?.remove()
        foodListener?.remove()
        rideListener = null
        foodListener = null
        messageListeners.values.forEach { it.remove() }
        messageListeners.clear()
        lastMsgAt.clear()
        super.onDestroy()
    }
}
