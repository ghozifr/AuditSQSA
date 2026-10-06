package com.suruhaja.data.policy

/**
 * Aturan input kode OTP email (murni, tanpa Android) — dipakai AuthViewModel &
 * AuthFragment, dan diuji di OtpInputPolicyTest.
 *
 * Server tetap otoritatif: ini hanya menyaring input yang jelas salah sebelum
 * callable dipanggil (hemat kuota kirim/percobaan).
 */
object OtpInputPolicy {

    const val CODE_LENGTH = 6
    const val RESEND_LABEL = "Kirim ulang kode"

    private val SIX_DIGITS = Regex("^\\d{6}$")
    private val NON_DIGIT = Regex("[^0-9]")

    /** Kode harus tepat 6 digit angka (spasi di ujung dimaafkan). */
    fun isValidCode(raw: String?): Boolean = SIX_DIGITS.matches(raw?.trim().orEmpty())

    /** Ambil hanya angka dan potong ke 6 digit (untuk input yang ditempel). */
    fun normalize(raw: String?): String =
        NON_DIGIT.replace(raw.orEmpty(), "").take(CODE_LENGTH)

    /** Label tombol kirim ulang: "Kirim ulang kode (42s)" selama jeda. */
    fun resendLabel(secondsLeft: Int): String =
        if (secondsLeft <= 0) RESEND_LABEL else "$RESEND_LABEL (${secondsLeft}s)"
}
