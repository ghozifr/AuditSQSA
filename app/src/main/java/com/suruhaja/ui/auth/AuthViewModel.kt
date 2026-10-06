package com.suruhaja.ui.auth

import android.util.Patterns
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suruhaja.data.policy.OtpInputPolicy
import com.suruhaja.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    sealed class AuthState {
        data object Idle : AuthState()
        data object Authenticating : AuthState()
        data object NewUser : AuthState()        // User baru — minta nama
        data object Verified : AuthState()        // User existing — langsung home
        data object SavingName : AuthState()      // Menyimpan nama
        data object ResetEmailSent : AuthState()
        /**
         * Kode OTP sudah dikirim ke email; app menunggu kode 6 digit.
         * `email` sudah disamarkan server (bu***@gmail.com).
         * `devCode` hanya terisi kalau server memakai OTP_DEV_MODE (pengujian).
         */
        data class OtpRequired(
            val email: String,
            val cooldownSec: Int,
            val devCode: String? = null,
            /**
             * Peringatan yang tetap ditampilkan di layar OTP, mis. saat kode gagal
             * dikirim. Verifikasi WAJIB diselesaikan (tidak ada tombol lewati):
             * jalan keluar user = "Kirim ulang kode" atau keluar dari aplikasi.
             */
            val notice: String? = null
        ) : AuthState()
        data class Error(val message: String) : AuthState()
    }

    private val _state = MutableLiveData<AuthState>(AuthState.Idle)
    val state: LiveData<AuthState> = _state

    /**
     * Pesan lembut soal kode referral (berhasil atau gagal). Ditampilkan sekali
     * lalu dianggap sudah dibaca — kegagalan TIDAK pernah menggagalkan pendaftaran.
     */
    private val _referralNotice = MutableLiveData<String?>()
    val referralNotice: LiveData<String?> = _referralNotice

    fun konsumsiReferralNotice() {
        _referralNotice.value = null
    }

    /**
     * true = setelah verifikasi email masih perlu melengkapi profil (nama + HP).
     * Ini yang membedakan tujuan setelah OTP: pendaftaran -> isi profil,
     * login biasa -> langsung Home.
     */
    private var perluLengkapiProfil = false

    /** Daftar akun baru: buat akun -> (opsional) pakai kode referral -> kirim OTP -> verifikasi. */
    fun register(email: String, password: String, confirmPassword: String, referralCode: String = "") {
        if (!validateEmail(email)) return
        if (!validatePassword(password)) return
        if (password != confirmPassword) {
            _state.value = AuthState.Error("Password dan konfirmasi password tidak sama")
            return
        }
        _state.value = AuthState.Authenticating

        viewModelScope.launch {
            val result = authRepository.registerWithEmail(email.trim(), password)
            result.fold(
                onSuccess = {
                    perluLengkapiProfil = true
                    // Kode referral (opsional) dipakai SEKALI di sini; gagal pun
                    // pendaftaran tetap lanjut (server yang memutuskan sah/tidak).
                    pakaiKodeReferral(referralCode)
                    sendOtp()
                },
                onFailure = { e ->
                    _state.value = AuthState.Error(e.message ?: "Gagal mendaftar. Coba lagi.")
                }
            )
        }
    }

    /** Pakai kode referral setelah akun dibuat. Tidak pernah memblokir alur daftar. */
    private fun pakaiKodeReferral(kode: String) {
        val bersih = kode.trim().uppercase().replace(" ", "").replace("-", "")
        if (bersih.isEmpty()) return
        viewModelScope.launch {
            authRepository.redeemReferralCode(bersih).fold(
                onSuccess = {
                    _referralNotice.value =
                        "Kode referral terpakai! Voucher gratis ongkir masuk setelah order SuruhFood pertamamu."
                },
                onFailure = { e ->
                    _referralNotice.value = e.message ?: "Kode referral tidak bisa dipakai."
                }
            )
        }
    }

    /** Masuk dengan akun yang sudah ada. */
    fun login(email: String, password: String) {
        if (!validateEmail(email)) return
        if (password.isEmpty()) {
            _state.value = AuthState.Error("Masukkan password")
            return
        }
        _state.value = AuthState.Authenticating

        viewModelScope.launch {
            val result = authRepository.signInWithEmail(email.trim(), password)
            result.fold(
                onSuccess = { isNewUser ->
                    perluLengkapiProfil = isNewUser
                    // Kode verifikasi diminta di SETIAP login — termasuk akun yang
                    // sudah pernah terverifikasi (disamakan dengan web/iphone).
                    // TIDAK ada jalan pintas "sudah verified, langsung masuk":
                    // layar OTP selalu muncul dan wajib diselesaikan.
                    sendOtp()
                },
                onFailure = { e ->
                    _state.value = AuthState.Error(e.message ?: "Gagal masuk. Coba lagi.")
                }
            )
        }
    }

    /** Kirim (atau kirim ulang) kode OTP ke email akun yang sedang login. */
    fun sendOtp() {
        _state.value = AuthState.Authenticating
        viewModelScope.launch {
            authRepository.requestEmailOtp().fold(
                onSuccess = { otp ->
                    if (otp.alreadyVerified) {
                        lanjutSetelahVerifikasi()
                    } else {
                        _state.value = AuthState.OtpRequired(otp.maskedEmail, otp.cooldownSec, otp.devCode)
                    }
                },
                onFailure = { e ->
                    // Gagal mengirim (mis. kredensial email belum dipasang atau
                    // kena batas kirim) TIDAK boleh mengunci user: tetap tampilkan
                    // layar OTP beserta peringatannya, dengan opsi kirim ulang /
                    // lewati. Alur lama (tanpa OTP) tetap bisa dilanjutkan.
                    _state.value = AuthState.OtpRequired(
                        email = "",
                        cooldownSec = 0,
                        notice = e.message ?: "Gagal mengirim kode verifikasi"
                    )
                }
            )
        }
    }

    /** Verifikasi kode 6 digit dari email. */
    fun verifyOtp(code: String) {
        val bersih = OtpInputPolicy.normalize(code)
        if (!OtpInputPolicy.isValidCode(bersih)) {
            _state.value = AuthState.Error("Masukkan 6 digit kode dari email")
            return
        }
        _state.value = AuthState.Authenticating
        viewModelScope.launch {
            authRepository.verifyEmailOtp(bersih).fold(
                onSuccess = { lanjutSetelahVerifikasi() },
                onFailure = { e ->
                    _state.value = AuthState.Error(e.message ?: "Kode tidak valid")
                }
            )
        }
    }

    /**
     * Lewati verifikasi untuk sekarang: user tetap bisa memakai aplikasi
     * (verifikasi bersifat lembut, bukan penghalang). Statusnya terlihat di
     * Profil dan bisa diverifikasi kapan saja dengan keluar lalu masuk lagi.
     */
    // skipOtp() DIHAPUS: verifikasi OTP wajib diselesaikan (keputusan pemilik).

    /** Kirim link reset tanpa membuka apakah akun tersebut terdaftar. */
    fun sendPasswordReset(email: String) {
        if (!validateEmail(email)) return
        _state.value = AuthState.Authenticating
        viewModelScope.launch {
            authRepository.sendPasswordReset(email.trim()).fold(
                onSuccess = { _state.value = AuthState.ResetEmailSent },
                onFailure = {
                    _state.value = AuthState.Error(
                        it.message ?: "Gagal mengirim link reset password"
                    )
                }
            )
        }
    }

    /**
     * Simpan nama + nomor HP (dipakai saat pendaftaran), lalu ke home.
     */
    fun saveProfile(name: String, phone: String) {
        if (name.isBlank()) {
            _state.value = AuthState.Error("Nama tidak boleh kosong")
            return
        }

        val normalizedPhone = normalizePhone(phone)
        if (!isValidPhone(normalizedPhone)) {
            _state.value = AuthState.Error("Nomor HP tidak valid")
            return
        }

        _state.value = AuthState.SavingName

        viewModelScope.launch {
            val result = authRepository.updateUserProfile(name.trim(), normalizedPhone)
            result.fold(
                onSuccess = {
                    _state.value = AuthState.Verified
                },
                onFailure = { e ->
                    _state.value = AuthState.Error(e.message ?: "Gagal menyimpan profil")
                }
            )
        }
    }

    /** Tujuan layar setelah email terverifikasi (atau dilewati). */
    private fun lanjutSetelahVerifikasi() {
        _state.value = if (perluLengkapiProfil) AuthState.NewUser else AuthState.Verified
    }

    private fun normalizePhone(raw: String): String {
        val digits = raw.replace(Regex("[^0-9]"), "")
        if (digits.isEmpty()) return ""
        return when {
            digits.startsWith("0") -> "+62" + digits.substring(1)
            digits.startsWith("62") -> "+$digits"
            else -> "+62$digits"
        }
    }

    private fun isValidPhone(normalized: String): Boolean {
        val digits = normalized.replace(Regex("[^0-9]"), "")
        return digits.length >= 10
    }

    fun resetState() {
        _state.value = AuthState.Idle
    }

    private fun validateEmail(email: String): Boolean {
        if (email.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            _state.value = AuthState.Error("Email tidak valid")
            return false
        }
        return true
    }

    private fun validatePassword(password: String): Boolean {
        if (password.length < 6) {
            _state.value = AuthState.Error("Password minimal 6 karakter")
            return false
        }
        return true
    }
}
