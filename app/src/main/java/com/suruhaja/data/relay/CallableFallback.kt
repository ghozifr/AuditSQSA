package com.suruhaja.data.relay

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Pemanggil callable dengan JALUR CADANGAN.
 *
 * Alur: coba Firebase SDK dulu (perilaku normal). Kalau gagal karena MASALAH
 * JARINGAN — bukan izin/bug — ulangi lewat relay `/call/<nama>`. Dipakai device
 * yang tidak bisa menembak `cloudfunctions.net` sendiri (mis. operator by.U).
 *
 * Isi payload & token MILIK USER diteruskan apa adanya; relay tidak menambah apa pun.
 * Dipakai oleh [Fungsi] (shim), jadi semua callable di app ini ikut terlindungi.
 */
object CallableFallback {

    private const val REGION = "asia-southeast2"
    /**
     * Callable yang AMAN diulang walau jenis kegagalannya TIDAK PASTI (mis. timeout).
     *
     *   · baca murni → mengulang hanya membaca lagi;
     *   · OTP        → server punya penjaganya sendiri (jeda kirim + batas percobaan kode
     *                  + kode dihapus setelah terpakai), jadi kirim/verifikasi ulang tidak
     *                  menimbulkan efek ganda. SEDANGKAN tidak mengulang membuat user
     *                  terkunci di layar verifikasi saat operator memblokir
     *                  cloudfunctions.net dan kegagalannya berupa timeout.
     *
     * Callable uang/order TIDAK masuk daftar ini — pengulangan bisa dobel topup/order.
     */
    internal val AMAN_DIULANG = setOf(
        "quoteOrder", "quoteFoodOrder", "getReferralSummary",
        "requestEmailOtp", "verifyEmailOtp"
    )


    /**
     * Panggil `nama`. Hasilnya data mentah dari fungsi (bisa Map, String, null).
     * Lempar Exception kalau kedua jalur gagal.
     */
    /**
     * Pemanggil jalur langsung (Firebase SDK). Ditulis sebagai lambda supaya
     * pengujian bisa memaksa jenis kegagalan tertentu — di produksi selalu SDK.
     */
    internal var panggilLangsung: suspend (String, Any?) -> Any? = { nama, data ->
        FirebaseFunctions.getInstance(REGION)
            .getHttpsCallable(nama)
            .call(data)
            .await()
            .getData()
    }

    suspend fun call(nama: String, payload: Any?): Any? = withContext(Dispatchers.IO) {
        // Dispatchers.IO WAJIB: RelayClient memakai HttpURLConnection yang BLOKIRAN,
        // dan Android melarang jaringan di thread utama (NetworkOnMainThreadException).
        // Bug nyata: OTP dipanggil dari viewModelScope (Main) ⇒ relay ditolak sistem
        // sebelum keluar, user melihat "Jalur cadangan tidak terjangkau". Callable yang
        // lewat shim Fungsi tidak kena karena shim sudah menjalankannya di Dispatchers.IO.
        val aman = payload ?: emptyMap<String, Any?>()
        try {
            val hasil = panggilLangsung(nama, aman)
            RelayClient.catatSuksesLangsung()
            hasil
        } catch (e: Exception) {
            // Kapan jalur cadangan (relay) boleh dipakai?
            //
            //  · Callable aman-diulang (baca & OTP): kegagalan APA PUN yang bukan balasan
            //    server dianggap layak dicoba lewat relay — termasuk jenis error yang belum
            //    pernah kita lihat. Operator memblokir dengan cara berbeda-beda (DNS gagal,
            //    koneksi di-reset di tengah jalan, timeout), jadi menebak jenis error itu
            //    rapuh. Justru tebakan itulah yang dulu mengunci user di layar verifikasi
            //    dengan pesan "Gagal mengirim kode verifikasi".
            //
            //  · Callable uang/order: HANYA kalau kegagalannya PASTI belum sampai server,
            //    supaya tidak ada dobel order / dobel topup / dobel pembayaran.
            val bolehPakaiCadangan = if (AMAN_DIULANG.contains(nama)) {
                !RelayClient.balasanServerSungguhan(e)
            } else {
                RelayClient.isNetworkError(e) && RelayClient.pastiBelumSampai(e)
            }
            if (!bolehPakaiCadangan) throw e
            if (RelayClient.isNetworkError(e)) RelayClient.catatGagalJaringan()
            try {
                RelayClient.callable(nama, aman, tokenSiap())
            } catch (relay: RelayClient.RelayCallException) {
                // Kalau yang menolak adalah relay sendiri (bukan fungsi), error
                // jaringan yang asli lebih jujur untuk ditampilkan ke pengguna.
                // Jejak kegagalan relay tetap ditempelkan (suppressed) untuk diagnosa
                // di build debug — di rilis jejak ini tidak pernah ditampilkan.
                if (relay.dariRelay) {
                    e.addSuppressed(relay)
                    throw e
                }
                throw relay
            }
        }
    }

    /** Token user: dari Firebase Auth, atau sesi relay kalau login lewat relay. */
    private suspend fun tokenSiap(): String? = try {
        FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            ?: RelayClient.idTokenSiap()
    } catch (e: Exception) {
        RelayClient.idTokenSiap()
    }
}
