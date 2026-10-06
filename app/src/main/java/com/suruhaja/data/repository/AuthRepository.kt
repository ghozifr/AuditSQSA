package com.suruhaja.data.repository

import com.suruhaja.data.relay.RelaySession
import com.suruhaja.data.relay.Fungsi
import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.suruhaja.BuildConfig
import com.suruhaja.data.relay.RelayClient
import com.suruhaja.data.relay.CallableFallback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) {
    fun getCurrentUserId(): String? = auth.currentUser?.uid

    fun isLoggedIn(): Boolean = auth.currentUser != null

    fun getCurrentUserPhone(): String = auth.currentUser?.phoneNumber ?: ""

    fun getCurrentUserEmail(): String = auth.currentUser?.email ?: ""

    fun signOut() = auth.signOut()

    /**
     * Daftar akun baru dengan email + password (tanpa verifikasi email, untuk testing).
     * Selalu user baru → return true supaya UI minta nama.
     */
    suspend fun registerWithEmail(email: String, password: String): Result<Boolean> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            RelayClient.catatSuksesLangsung()
            val user = result.user ?: return Result.failure(Exception("Gagal mendaftar"))
            createUserDoc(user.uid, email)
            Result.success(true)
        } catch (e: FirebaseAuthUserCollisionException) {
            Result.failure(Exception("Email sudah terdaftar. Silakan masuk."))
        } catch (e: FirebaseAuthWeakPasswordException) {
            Result.failure(Exception("Password terlalu lemah. Minimal 6 karakter."))
        } catch (e: Exception) {
            // ── Jalur cadangan: pendaftaran lewat relay (Google tak terjangkau) ──
            if (RelayClient.isNetworkError(e)) {
                RelayClient.catatGagalJaringan()
                val resp = withContext(Dispatchers.IO) { RelayClient.register(email, password) }
                if (resp != null && resp.optBoolean("ok")) {
                    val sesi = RelayClient.sesiDariRespons(resp)
                    if (sesi != null) {
                        RelayClient.simpanSesi(context, sesi)
                        // Tukar customToken relay ⇒ sesi Firebase Auth SUNGGUHAN, supaya
                        // aturan Firestore (signedIn()) berlaku dan data app tidak kosong.
                        RelaySession.sambungkanKeFirebase(resp.optString("customToken"))
                        // Dokumen users/{uid} TIDAK dibuat relay (relay baca-saja);
                        // dibuat saat Firestore terjangkau. UI tetap minta nama.
                        return Result.success(true)
                    }
                }
                when (resp?.optString("error")) {
                    "EMAIL_EXISTS" -> return Result.failure(Exception("Email sudah terdaftar. Silakan masuk."))
                    "WEAK_PASSWORD" -> return Result.failure(Exception("Password terlalu lemah. Minimal 6 karakter."))
                    "INVALID_EMAIL" -> return Result.failure(Exception("Format email tidak valid."))
                }
            }
            Result.failure(e)
        }
    }

    /**
     * Masuk dengan email + password. Return true bila dokumen users/{uid}
     * belum ada (user lama dari auth provider lain) supaya UI minta nama.
     */
    suspend fun signInWithEmail(email: String, password: String): Result<Boolean> {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            RelayClient.catatSuksesLangsung()
            val user = result.user ?: return Result.failure(Exception("Gagal masuk"))
            val doc = firestore.collection("users").document(user.uid).get().await()
            val isNewUser = !doc.exists()
            if (isNewUser) createUserDoc(user.uid, email)
            Result.success(isNewUser)
        } catch (e: FirebaseAuthInvalidUserException) {
            Result.failure(Exception("Email belum terdaftar."))
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            Result.failure(Exception("Password salah."))
        } catch (e: Exception) {
            // ── Jalur cadangan: relay kita yang menembak Google ────────────────
            if (RelayClient.isNetworkError(e)) {
                RelayClient.catatGagalJaringan()
                loginLewatRelay(email, password)?.let { return it }
            }
            Result.failure(e)
        }
    }

    /**
     * Masuk lewat relay (dipakai HANYA saat perangkat tak bisa mencapai Google).
     * Sesi relay disimpan; selama gangguan, pembacaan app memakai jalur relay.
     * `isNewUser` ditentukan dari keberadaan dokumen profil di relay (/me).
     */
    private suspend fun loginLewatRelay(email: String, password: String): Result<Boolean>? {
        val resp = withContext(Dispatchers.IO) { RelayClient.login(email, password) } ?: return null
        if (!resp.optBoolean("ok")) {
            return when (resp.optString("error")) {
                "EMAIL_NOT_FOUND", "INVALID_LOGIN_CREDENTIALS", "INVALID_PASSWORD" ->
                    Result.failure(Exception("Email atau password salah."))
                "USER_DISABLED" -> Result.failure(Exception("Akun ini dinonaktifkan."))
                else -> Result.failure(Exception("Gagal masuk (mode cadangan): " + resp.optString("error")))
            }
        }
        val sesi = RelayClient.sesiDariRespons(resp) ?: return null
        RelayClient.simpanSesi(context, sesi)
        val me = withContext(Dispatchers.IO) { RelayClient.me(sesi.idToken) }
        // Tukar customToken relay ⇒ sesi Firebase Auth SUNGGUHAN, supaya aturan
        // Firestore (signedIn()) berlaku dan data app tidak kosong.
        RelaySession.sambungkanKeFirebase(resp.optString("customToken"))
        val profilAda = me?.optBoolean("profileExists") == true
        return Result.success(!profilAda)
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        return try {
            auth.sendPasswordResetEmail(email).await()
            Result.success(Unit)
        } catch (_: FirebaseAuthInvalidUserException) {
            // Respons disamakan agar layar tidak membocorkan apakah email terdaftar.
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Hasil permintaan kode OTP (email sudah disamarkan server). */
    data class OtpRequest(
        val maskedEmail: String,
        val cooldownSec: Int,
        val alreadyVerified: Boolean,
        /** Hanya terisi kalau server dijalankan dengan OTP_DEV_MODE (pengujian). */
        val devCode: String?
    )

    /**
     * Status verifikasi email di Firebase Auth.
     *
     * CATATAN: TIDAK dipakai lagi untuk melewati OTP — kode verifikasi kini
     * diminta di SETIAP login (disamakan dengan web/iphone). Jangan tambahkan
     * jalan pintas "kalau sudah terverifikasi langsung masuk" di sini.
     */
    fun isEmailVerified(): Boolean = auth.currentUser?.isEmailVerified == true

    /**
     * Minta server mengirim kode OTP 6 digit ke email akun yang sedang login.
     * App tidak pernah membuat kode sendiri — kode dibuat & dikirim server.
     *
     * `paksa = true` = minta kode di SETIAP login, termasuk akun yang sudah
     * terverifikasi. Tanpa flag ini server membalas `alreadyVerified` dan
     * langkah OTP terlewat begitu saja.
     */
    suspend fun requestEmailOtp(): Result<OtpRequest> {
        return try {
            // Jalur cadangan (relay) dipakai otomatis kalau cloudfunctions.net
            // tidak terjangkau dari perangkat ini (mis. operator by.U).
            val data = CallableFallback.call(
                "requestEmailOtp",
                mapOf(
                    "appVersionCode" to BuildConfig.VERSION_CODE,
                    "paksa" to true
                )
            ) as? Map<*, *>
                ?: return Result.failure(Exception("Respons server tidak valid"))
            Result.success(
                OtpRequest(
                    maskedEmail = (data["email"] as? String).orEmpty(),
                    cooldownSec = (data["cooldownSec"] as? Number)?.toInt() ?: 60,
                    alreadyVerified = data["alreadyVerified"] == true,
                    devCode = data["devCode"] as? String
                )
            )
        } catch (e: Exception) {
            Result.failure(Exception(friendlyFunctionMessage(e, "Gagal mengirim kode verifikasi")))
        }
    }

    /** Cocokkan kode OTP; server yang memutuskan benar/salah. */
    suspend fun verifyEmailOtp(code: String): Result<Unit> {
        return try {
            CallableFallback.call(
                "verifyEmailOtp",
                mapOf(
                    "code" to code.trim(),
                    "appVersionCode" to BuildConfig.VERSION_CODE
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(friendlyFunctionMessage(e, "Kode tidak valid")))
        }
    }

    // ── Referral (khusus SuruhFood) ─────────────────────────────────────────
    /** Ringkasan referral untuk kartu di Profil (semua angka datang dari server). */
    data class ReferralFriend(val nama: String, val ordersCounted: Int, val status: String)

    data class ReferralSummary(
        val code: String,
        val promoAktif: Boolean,
        val promoAlasan: String,
        val promoBerakhir: Long,
        val gratisOngkirMaks: Long,
        val hadiahSaldo: Long,
        val syaratTeman: Int,
        val capOrderPerTeman: Int,
        val temanTerdaftar: Int,
        val temanSelesai: Int,
        val orderTerhitung: Int,
        val targetOrder: Int,
        val tercapai: Boolean,
        val hadiahDibayar: Boolean,
        val sudahPakaiKode: Boolean,
        val teman: List<ReferralFriend>
    )

    /** Kode referral milik user + progres objektif. Server membuat kodenya kalau belum ada. */
    suspend fun getReferralSummary(): Result<ReferralSummary> {
        return try {
            val result = Fungsi.getInstance("asia-southeast2")
                .getHttpsCallable("getReferralSummary")
                .call(mapOf("appVersionCode" to BuildConfig.VERSION_CODE)).await()
            val data = result.getData() as? Map<*, *>
                ?: return Result.failure(Exception("Respons server tidak valid"))
            val promo = data["promo"] as? Map<*, *> ?: emptyMap<String, Any>()
            val progres = data["progres"] as? Map<*, *> ?: emptyMap<String, Any>()
            val temanRaw = data["teman"] as? List<*> ?: emptyList<Any>()
            val angka: (Any?) -> Long = { (it as? Number)?.toLong() ?: 0L }
            val bulat: (Any?) -> Int = { (it as? Number)?.toInt() ?: 0 }
            Result.success(
                ReferralSummary(
                    code = (data["code"] as? String).orEmpty(),
                    promoAktif = promo["aktif"] == true,
                    promoAlasan = (promo["alasan"] as? String).orEmpty(),
                    promoBerakhir = angka(promo["berakhir"]),
                    gratisOngkirMaks = angka(promo["gratisOngkirMaks"]),
                    hadiahSaldo = angka(promo["hadiahSaldo"]),
                    syaratTeman = bulat(promo["syaratTeman"]),
                    capOrderPerTeman = bulat(promo["capOrderPerTeman"]),
                    temanTerdaftar = bulat(progres["temanTerdaftar"]),
                    temanSelesai = bulat(progres["selesai"]),
                    orderTerhitung = bulat(progres["orderTerhitung"]),
                    targetOrder = bulat(progres["targetOrder"]),
                    tercapai = progres["tercapai"] == true,
                    hadiahDibayar = data["hadiahDibayar"] == true,
                    sudahPakaiKode = data["sudahPakaiKode"] == true,
                    teman = temanRaw.mapNotNull { baris ->
                        val r = baris as? Map<*, *> ?: return@mapNotNull null
                        ReferralFriend(
                            nama = (r["nama"] as? String).orEmpty(),
                            ordersCounted = bulat(r["ordersCounted"]),
                            status = (r["status"] as? String).orEmpty()
                        )
                    }
                )
            )
        } catch (e: Exception) {
            Result.failure(Exception(friendlyFunctionMessage(e, "Gagal memuat info referral")))
        }
    }

    /**
     * Pakai kode referral — HANYA saat mendaftar (server menolak kalau sudah pernah).
     * Kegagalan di sini tidak boleh menggagalkan pendaftaran.
     */
    suspend fun redeemReferralCode(code: String): Result<Unit> {
        return try {
            Fungsi.getInstance("asia-southeast2")
                .getHttpsCallable("redeemReferralCode")
                .call(
                    mapOf(
                        "code" to code.trim().uppercase(),
                        "appVersionCode" to BuildConfig.VERSION_CODE
                    )
                ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(friendlyFunctionMessage(e, "Kode referral gagal dipakai")))
        }
    }

    /**
     * Pesan dari server sudah Bahasa Indonesia; jangan tampilkan kode teknis
     * (INTERNAL / UNAVAILABLE / FAILED_PRECONDITION) ke user.
     */
    private fun friendlyFunctionMessage(e: Exception, fallback: String): String {
        val raw = e.message?.trim().orEmpty()
        val teknis = raw.isEmpty() ||
            raw.matches(Regex("[A-Z_]+")) ||
            raw.contains("INTERNAL") ||
            raw.contains("UNAVAILABLE") ||
            raw.contains("PERMISSION_DENIED")
        val pesan = if (teknis) fallback else raw
        // Build DEBUG: sertakan sebab teknisnya supaya masalah jaringan bisa didiagnosa
        // langsung dari layar HP (tidak ada adb). Build rilis tetap bersih untuk user.
        return if (BuildConfig.DEBUG) "$pesan [" + sebabTeknis(e) + "]" else pesan
    }

    /**
     * Ringkas sebab teknis sebuah kegagalan: jenis exception + kode SDK + jenis
     * kegagalan relay. Dipakai HANYA untuk pesan di build debug.
     */
    private fun sebabTeknis(e: Throwable): String {
        val b = StringBuilder()
        var t: Throwable? = e
        var n = 0
        while (t != null && n < 3) {
            if (n > 0) b.append(" <== ")
            b.append(t.javaClass.simpleName)
            (t as? FirebaseFunctionsException)?.let { b.append(':').append(it.code.name) }
            t.suppressed.firstOrNull()?.let {
                b.append(" |relay:").append(it.javaClass.simpleName).append('(')
                    .append((it.message ?: "").take(60)).append(')')
            }
            t = t.cause
            n++
        }
        return b.toString()
    }

    private suspend fun createUserDoc(uid: String, email: String) {
        val userData = mapOf(
            "uid" to uid,
            "email" to email,
            "phone" to "",
            "name" to "",
            "balance" to 0L,
            "points" to 0L,
            "createdAt" to System.currentTimeMillis()
        )
        firestore.collection("users")
            .document(uid)
            .set(userData)
            .await()
    }

    /**
     * Update nama + nomor HP user (dipakai saat pendaftaran, setelah isi profil).
     */
    suspend fun updateUserProfile(name: String, phone: String): Result<Boolean> {
        return try {
            val uid = auth.currentUser?.uid ?: return Result.failure(Exception("User tidak ditemukan"))
            firestore.collection("users")
                .document(uid)
                .set(mapOf("name" to name, "phone" to phone), com.google.firebase.firestore.SetOptions.merge())
                .await()
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
