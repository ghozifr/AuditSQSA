package com.suruhaja.data.relay

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Pengganti drop-in `FirebaseFunctions.getInstance(region)`.
 *
 * SEMUA panggilan callable di app ini lewat sini, sehingga jalur cadangan (relay)
 * ikut terpakai di ISP yang memblokir `cloudfunctions.net` (mis. by.U/Telkomsel) —
 * tanpa mengubah bentuk rantai panggilan di pemanggil:
 *
 *     FirebaseFunctions.getInstance("asia-southeast2")  →  Fungsi.getInstance("asia-southeast2")
 *
 * `.getHttpsCallable("x").call(y).await()` dan `.call(y).addOnCompleteListener{…}`
 * tetap bekerja, karena yang dikembalikan `Task` sungguhan (TaskCompletionSource).
 *
 * Penggantian nama ini sengaja dilakukan di SATU pintu supaya callable yang
 * ditambahkan nanti otomatis ikut terlindungi — tidak ada yang terlewat.
 */
object Fungsi {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun getInstance(): Fungsi = this

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun getInstance(region: String): Fungsi = this

    fun getHttpsCallable(nama: String): Panggilan = Panggilan(nama)

    internal fun jalankan(nama: String, data: Any?, tcs: TaskCompletionSource<Hasil>) {
        scope.launch {
            try {
                tcs.setResult(Hasil(CallableFallback.call(nama, data)))
            } catch (e: Exception) {
                tcs.setException(e)
            }
        }
    }
}

/** Mirip `HttpsCallableReference`: cukup `call(...)` yang mengembalikan Task. */
class Panggilan internal constructor(private val nama: String) {

    fun call(): Task<Hasil> = call(null)

    fun call(data: Any?): Task<Hasil> {
        val tcs = TaskCompletionSource<Hasil>()
        Fungsi.jalankan(nama, data, tcs)
        return tcs.task
    }
}

/** Mirip `HttpsCallableResult`: cukup `getData()`. */
class Hasil internal constructor(private val data: Any?) {
    fun getData(): Any? = data
}
